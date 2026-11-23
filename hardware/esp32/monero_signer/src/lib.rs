// SPDX-License-Identifier: GPL-3.0-or-later
#![cfg_attr(not(feature = "std"), no_std)]

extern crate alloc;

#[cfg(all(test, feature = "std"))]
mod tests;

use alloc::{string::ToString, vec, vec::Vec};
use core::{slice, str};

use curve25519_dalek::constants::ED25519_BASEPOINT_POINT;
use monero_wallet::{
    address::{AddressType, MoneroAddress, Network},
    ed25519::{CompressedPoint, Point, Scalar},
    interface::FeeRate,
    ringct::{clsag::Decoys, RctType},
    send::{Change, SignableTransaction},
    OutputWithDecoys,
};
use rand_chacha::ChaCha20Rng;
use rand_core::{RngCore, SeedableRng};
use zeroize::Zeroizing;

const MAX_ARCHIVE_BYTES: usize = 128 * 1024;
const MAX_INPUTS: usize = 16;
const RING_SIZE: usize = 16;

#[derive(Clone, Copy, Debug)]
enum Error {
    Encoding = 1,
    Unsupported = 2,
    ReviewMismatch = 3,
    InputNotOwned = 4,
    Signing = 5,
    Capacity = 6,
}

type Result<T> = core::result::Result<T, Error>;

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8]> {
        let (head, tail) = self.0.split_at_checked(count).ok_or(Error::Encoding)?;
        self.0 = tail;
        Ok(head)
    }

    fn byte(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn fixed_u64(&mut self) -> Result<u64> {
        Ok(u64::from_le_bytes(
            self.take(8)?.try_into().map_err(|_| Error::Encoding)?,
        ))
    }

    fn varint(&mut self) -> Result<u64> {
        let mut value = 0u64;
        for shift in (0..64).step_by(7) {
            let next = self.byte()?;
            let low = next & 127;
            if shift == 63 && low > 1 {
                return Err(Error::Encoding);
            }
            value |= u64::from(low) << shift;
            if next < 128 {
                if shift != 0 && low == 0 {
                    return Err(Error::Encoding);
                }
                return Ok(value);
            }
        }
        Err(Error::Encoding)
    }

    fn count(&mut self, limit: usize) -> Result<usize> {
        let count = usize::try_from(self.varint()?).map_err(|_| Error::Capacity)?;
        if count > limit {
            return Err(Error::Capacity);
        }
        Ok(count)
    }

    fn point(&mut self) -> Result<Point> {
        let encoded: [u8; 32] = self.take(32)?.try_into().map_err(|_| Error::Encoding)?;
        let point = CompressedPoint::from(encoded)
            .decompress()
            .ok_or(Error::Encoding)?;
        // Do not multiply a host-supplied torsion point by a wallet secret.
        point.key_image().ok_or(Error::Encoding)?;
        Ok(point)
    }

    fn destination(&mut self, network: Network) -> Result<(MoneroAddress, u64)> {
        let length = self.count(106)?;
        let original = str::from_utf8(self.take(length)?).map_err(|_| Error::Encoding)?;
        let amount = self.varint()?;
        let spend = self.point()?;
        let view = self.point()?;
        let subaddress = self.byte()?;
        let integrated = self.byte()?;
        if subaddress != 0 || integrated != 0 {
            return Err(Error::Unsupported);
        }
        let address = MoneroAddress::new(network, AddressType::Legacy, spend, view);
        if !original.is_empty() && original != address.to_string() {
            return Err(Error::ReviewMismatch);
        }
        Ok((address, amount))
    }

    fn source(&mut self, spend: &Scalar, view: &Scalar) -> Result<OutputWithDecoys> {
        if self.count(RING_SIZE)? != RING_SIZE {
            return Err(Error::Unsupported);
        }
        let mut ring = Vec::with_capacity(RING_SIZE);
        let mut offsets = Vec::with_capacity(RING_SIZE);
        let mut previous = 0u64;
        for index in 0..RING_SIZE {
            if self.varint()? != 2 {
                return Err(Error::Encoding);
            }
            let absolute = self.varint()?;
            let delta = absolute.checked_sub(previous).ok_or(Error::Encoding)?;
            if index != 0 && delta == 0 {
                return Err(Error::Encoding);
            }
            offsets.push(delta);
            previous = absolute;
            ring.push([self.point()?, self.point()?]);
        }
        let real = usize::try_from(self.fixed_u64()?).map_err(|_| Error::Encoding)?;
        let key = ring.get(real).ok_or(Error::Encoding)?[0];
        let tx_key = self.point()?;
        let additional = self.count(64)?;
        // Primary-address receipts use the primary transaction key, including
        // transactions which also sent funds to unrelated subaddresses.
        self.take(additional * 32)?;
        let output_index = self.fixed_u64()?;
        let amount = self.fixed_u64()?;
        if self.byte()? != 1 || amount == 0 {
            return Err(Error::Encoding);
        }
        let mask = self.take(32)?;
        self.take(128)?; // Multisig metadata is unused; no multisig signing.

        let view_scalar = Zeroizing::new((*view).into());
        let shared = Zeroizing::new(
            (tx_key.into().mul_by_cofactor() * &*view_scalar)
                .compress()
                .to_bytes(),
        );
        let mut derivation = Zeroizing::new(Vec::from(&shared[..]));
        let mut index = output_index;
        while index >= 128 {
            derivation.push((index as u8 & 127) | 128);
            index >>= 7;
        }
        derivation.push(index as u8);
        let offset = Zeroizing::new(Scalar::hash(&derivation[..]));
        let private: Zeroizing<curve25519_dalek::Scalar> =
            Zeroizing::new((*spend).into() + (*offset).into());
        if Point::from(ED25519_BASEPOINT_POINT * *private) != key {
            return Err(Error::InputNotOwned);
        }

        // Use Monero Oxide's commitment/decoy constructors through its binary
        // reader. They reject noncanonical scalars and invalid decoy sets.
        let decoys = Decoys::new(offsets, real as u8, ring).ok_or(Error::Encoding)?;
        let mut encoded = Zeroizing::new(Vec::with_capacity(128 + RING_SIZE * 72));
        encoded.extend(key.compress().to_bytes());
        offset.write(&mut *encoded).map_err(|_| Error::Encoding)?;
        encoded.extend(mask);
        encoded.extend(amount.to_le_bytes());
        decoys.write(&mut *encoded).map_err(|_| Error::Encoding)?;
        let output = OutputWithDecoys::read(&mut &encoded[..]).map_err(|_| Error::Encoding)?;
        if output.commitment().commit() != decoys.signer_ring_members()[1] {
            return Err(Error::InputNotOwned);
        }
        Ok(output)
    }
}

struct Review<'a> {
    destination: &'a str,
    amount: u64,
    fee: u64,
}

fn sign_archive(
    plaintext: &[u8],
    spend: &Zeroizing<Scalar>,
    view: &Zeroizing<Scalar>,
    entropy: &Zeroizing<[u8; 32]>,
    network: Network,
    review: Review<'_>,
) -> Result<(Vec<u8>, [u8; 32])> {
    if plaintext.len() > MAX_ARCHIVE_BYTES || review.amount == 0 || review.fee == 0 {
        return Err(Error::Capacity);
    }

    let recipient =
        MoneroAddress::from_str(network, review.destination).map_err(|_| Error::Encoding)?;
    let own_address = MoneroAddress::new(
        network,
        AddressType::Legacy,
        Point::from(ED25519_BASEPOINT_POINT * (**spend).into()),
        Point::from(ED25519_BASEPOINT_POINT * (**view).into()),
    );
    if recipient == own_address || *recipient.kind() != AddressType::Legacy {
        return Err(Error::Unsupported);
    }

    let mut reader = Reader(plaintext);
    if reader.varint()? != 2 || reader.varint()? != 1 {
        return Err(Error::Unsupported);
    }
    let count = reader.count(MAX_INPUTS)?;
    let mut inputs = Vec::with_capacity(count);
    let mut total = 0u64;
    for _ in 0..count {
        let source = reader.source(spend, view)?;
        total = total
            .checked_add(source.commitment().amount)
            .ok_or(Error::Encoding)?;
        inputs.push(source);
    }
    let change = reader.destination(network)?;
    if change.0 != own_address || change.1 == 0 || reader.count(2)? != 2 {
        return Err(Error::Unsupported);
    }
    let payment = (recipient, review.amount);
    let first = reader.destination(network)?;
    let second = reader.destination(network)?;
    if !((first == payment && second == change) || (second == payment && first == change)) {
        return Err(Error::ReviewMismatch);
    }
    if reader.count(MAX_INPUTS)? != count {
        return Err(Error::Encoding);
    }
    for _ in 0..count {
        reader.varint()?;
    }

    let extra_len = reader.count(44)?;
    let mut extra = reader.take(extra_len)?;
    // wallet2 may serialize its provisional transaction public key. The
    // signer independently derives the key for the transaction it produces.
    if extra.len() >= 33 && extra[0] == 1 {
        extra = &extra[33..];
    }
    // Support no payment ID and wallet2's dummy encrypted short payment ID.
    if !extra.is_empty() && extra != [2, 9, 1, 0, 0, 0, 0, 0, 0, 0, 0] {
        return Err(Error::Unsupported);
    }
    if reader.fixed_u64()? != 0
        || reader.byte()? != 3
        || reader.varint()? != 0
        || reader.varint()? != 3
        || reader.varint()? != 4
        || reader.varint()? != 1
        || reader.destination(network)? != payment
        || reader.take(4)? != [0; 4]
        || reader.varint()? != 1
        || reader.varint()? != 0
    {
        return Err(Error::Unsupported);
    }

    // The archive's remaining wallet transfer cache does not participate in
    // signing. The real ring member's key and commitment were checked above.
    if total
        .checked_sub(review.amount)
        .and_then(|value| value.checked_sub(change.1))
        != Some(review.fee)
    {
        return Err(Error::ReviewMismatch);
    }

    let mut rng = ChaCha20Rng::from_seed(**entropy);
    let mut outgoing = Zeroizing::new([0; 32]);
    rng.fill_bytes(&mut *outgoing);
    // Two exact outputs and no implicit change preserve the wallet2 quote.
    // The actual fee is the difference between inputs and these two outputs.
    let plan = SignableTransaction::new(
        RctType::ClsagBulletproofPlus,
        outgoing,
        inputs,
        vec![payment, change],
        Change::fingerprintable(None),
        Vec::new(),
        FeeRate::new(1, 1).ok_or(Error::Signing)?,
    )
    .map_err(|_| Error::Signing)?;
    let signed = plan.sign(&mut rng, spend).map_err(|_| Error::Signing)?;
    let monero_wallet::transaction::Transaction::V2 {
        proofs: Some(ref proofs),
        ..
    } = signed
    else {
        return Err(Error::Signing);
    };
    if proofs.base.fee != review.fee {
        return Err(Error::ReviewMismatch);
    }
    Ok((signed.serialize(), signed.hash()))
}

#[repr(C)]
pub struct SignRequest {
    plaintext: *const u8,
    plaintext_len: usize,
    spend: *const [u8; 32],
    view: *const [u8; 32],
    entropy: *const [u8; 32],
    destination: *const u8,
    destination_len: usize,
    amount: u64,
    fee: u64,
    stagenet: bool,
}

// The C++ caller supplies disjoint buffers that remain valid throughout the
// call. No key material or derived transaction keys are returned to it.
#[no_mangle]
pub unsafe extern "C" fn airlock_monero_sign_archive(
    request: &SignRequest,
    raw: *mut u8,
    capacity: usize,
    written: &mut usize,
    txid: &mut [u8; 32],
) -> i32 {
    *written = 0;
    txid.fill(0);
    if request.plaintext_len > MAX_ARCHIVE_BYTES || request.destination_len != 95 {
        return Error::Capacity as i32;
    }
    let parse_secret = |bytes: &[u8; 32]| -> Result<Zeroizing<Scalar>> {
        let scalar = Scalar::read(&mut &bytes[..]).map_err(|_| Error::Encoding)?;
        if scalar.into() == curve25519_dalek::Scalar::ZERO {
            return Err(Error::Encoding);
        }
        Ok(Zeroizing::new(scalar))
    };
    let result = (|| {
        let plaintext = unsafe { slice::from_raw_parts(request.plaintext, request.plaintext_len) };
        let destination =
            unsafe { slice::from_raw_parts(request.destination, request.destination_len) };
        let destination = str::from_utf8(destination).map_err(|_| Error::Encoding)?;
        let spend = parse_secret(unsafe { &*request.spend })?;
        let view = parse_secret(unsafe { &*request.view })?;
        let entropy = Zeroizing::new(unsafe { *request.entropy });
        let network = if request.stagenet {
            Network::Stagenet
        } else {
            Network::Mainnet
        };
        sign_archive(
            plaintext,
            &spend,
            &view,
            &entropy,
            network,
            Review {
                destination,
                amount: request.amount,
                fee: request.fee,
            },
        )
    })();
    match result {
        Ok((transaction, hash)) if transaction.len() <= capacity => {
            unsafe {
                raw.copy_from_nonoverlapping(transaction.as_ptr(), transaction.len());
            }
            *written = transaction.len();
            *txid = hash;
            0
        }
        Ok(_) => Error::Capacity as i32,
        Err(error) => error as i32,
    }
}

#[cfg(not(feature = "std"))]
mod platform {
    use core::alloc::{GlobalAlloc, Layout};

    extern "C" {
        fn airlock_monero_alloc(size: usize, alignment: usize) -> *mut u8;
        fn airlock_monero_free(pointer: *mut u8, size: usize);
        fn airlock_monero_panic(file: *const u8, len: usize, line: u32) -> !;
    }

    struct Allocator;

    unsafe impl GlobalAlloc for Allocator {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            unsafe { airlock_monero_alloc(layout.size(), layout.align()) }
        }

        unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
            unsafe { airlock_monero_free(pointer, layout.size()) }
        }
    }

    #[global_allocator]
    static ALLOCATOR: Allocator = Allocator;

    #[panic_handler]
    fn panic(info: &core::panic::PanicInfo<'_>) -> ! {
        let (file, line) = info
            .location()
            .map_or(("", 0), |loc| (loc.file(), loc.line()));
        unsafe { airlock_monero_panic(file.as_ptr(), file.len(), line) }
    }
}
