//! Public, deterministic synthetic outputs. No wallet or network is accessed.
use super::*;
use monero_wallet::{
    ed25519::Commitment,
    ringct::RctPrunable,
    transaction::{Input, Transaction},
};

fn scalar(n: u64) -> Scalar {
    Scalar::from(curve25519_dalek::Scalar::from(n))
}
fn ds(s: Scalar) -> curve25519_dalek::Scalar {
    s.into()
}
fn dp(p: Point) -> curve25519_dalek::EdwardsPoint {
    p.into()
}
fn point(s: Scalar) -> Point {
    Point::from(ED25519_BASEPOINT_POINT * ds(s))
}
fn varint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 128 {
        out.push(value as u8 | 128);
        value >>= 7;
    }
    out.push(value as u8);
}
fn destination(out: &mut Vec<u8>, spend: Scalar, view: Scalar, amount: u64) -> String {
    let address = MoneroAddress::new(
        Network::Stagenet,
        AddressType::Legacy,
        point(spend),
        point(view),
    )
    .to_string();
    varint(out, address.len() as u64);
    out.extend(address.as_bytes());
    varint(out, amount);
    out.extend(point(spend).compress().to_bytes());
    out.extend(point(view).compress().to_bytes());
    out.extend([0, 0]);
    address
}

struct Fixture {
    archive: Vec<u8>,
    recipient: String,
    ring: Vec<[CompressedPoint; 2]>,
}
const AMOUNT: u64 = 2_000_000_000_000;
const FEE: u64 = 50_000_000;
fn fixture() -> Fixture {
    let (spend, view, tx_secret) = (scalar(17), scalar(23), scalar(31));
    let shared = (dp(point(tx_secret)).mul_by_cofactor() * ds(view))
        .compress()
        .to_bytes();
    let mut derivation = shared.to_vec();
    derivation.push(0);
    let offset = Scalar::hash(&derivation);
    let key = point(Scalar::from(ds(spend) + ds(offset)));
    let total = 5_000_000_000_000;
    let commitment = Commitment::new(scalar(43), total).commit();
    let mut ring = Vec::new();
    let mut archive = Vec::new();
    varint(&mut archive, 2);
    varint(&mut archive, 1);
    varint(&mut archive, 1);
    varint(&mut archive, 16);
    for index in 0..16 {
        let pair = if index == 7 {
            [key, commitment]
        } else {
            [
                point(scalar(100 + index)),
                Commitment::new(scalar(200 + index), total + index).commit(),
            ]
        };
        ring.push([pair[0].compress(), pair[1].compress()]);
        varint(&mut archive, 2);
        varint(&mut archive, 1000 + index * 10);
        archive.extend(pair[0].compress().to_bytes());
        archive.extend(pair[1].compress().to_bytes());
    }
    archive.extend(7u64.to_le_bytes());
    archive.extend(point(tx_secret).compress().to_bytes());
    varint(&mut archive, 0);
    archive.extend(0u64.to_le_bytes());
    archive.extend(total.to_le_bytes());
    archive.push(1);
    archive.extend(<[u8; 32]>::from(scalar(43)));
    archive.extend([0; 128]);
    destination(&mut archive, spend, view, total - AMOUNT - FEE);
    varint(&mut archive, 2);
    let recipient = destination(&mut archive, scalar(47), scalar(53), AMOUNT);
    destination(&mut archive, spend, view, total - AMOUNT - FEE);
    varint(&mut archive, 1);
    varint(&mut archive, 0);
    varint(&mut archive, 0);
    archive.extend(0u64.to_le_bytes());
    archive.push(3);
    for n in [0, 3, 4, 1] {
        varint(&mut archive, n);
    }
    destination(&mut archive, scalar(47), scalar(53), AMOUNT);
    archive.extend([0; 4]);
    varint(&mut archive, 1);
    varint(&mut archive, 0);
    Fixture {
        archive,
        recipient,
        ring,
    }
}

#[test]
fn real_archive_produces_verifiable_clsag_and_bulletproof_plus() {
    let fixture = fixture();
    let (raw, txid) = sign_archive(
        &fixture.archive,
        &Zeroizing::new(scalar(17)),
        &Zeroizing::new(scalar(23)),
        &Zeroizing::new([61; 32]),
        Network::Stagenet,
        Review {
            destination: &fixture.recipient,
            amount: AMOUNT,
            fee: FEE,
        },
    )
    .unwrap();
    let mut reader = raw.as_slice();
    let tx = Transaction::read(&mut reader).unwrap();
    assert!(reader.is_empty());
    assert_eq!(tx.hash(), txid);
    let hash = tx.signature_hash().unwrap();
    let Transaction::V2 {
        prefix,
        proofs: Some(proofs),
    } = tx
    else {
        panic!("expected RingCT")
    };
    assert_eq!(proofs.base.fee, FEE);
    assert_eq!(prefix.outputs.len(), 2);
    let Input::ToKey {
        key_image,
        key_offsets,
        ..
    } = &prefix.inputs[0]
    else {
        panic!("expected ring input")
    };
    assert_eq!(key_offsets.len(), 16);
    let RctPrunable::Clsag {
        clsags,
        pseudo_outs,
        bulletproof,
    } = &proofs.prunable
    else {
        panic!("expected CLSAG")
    };
    assert_eq!(clsags.len(), 1);
    clsags[0]
        .verify(fixture.ring.clone(), key_image, &pseudo_outs[0], &hash)
        .unwrap();
    assert!(bulletproof.verify(
        &mut ChaCha20Rng::from_seed([71; 32]),
        &proofs.base.commitments
    ));
    let mut altered = hash;
    altered[0] ^= 1;
    assert!(clsags[0]
        .verify(fixture.ring.clone(), key_image, &pseudo_outs[0], &altered)
        .is_err());
    let mut altered_ring = fixture.ring;
    altered_ring[7][0] = point(scalar(99)).compress();
    assert!(clsags[0]
        .verify(altered_ring, key_image, &pseudo_outs[0], &hash)
        .is_err());
    let mut altered_commitments = proofs.base.commitments.clone();
    altered_commitments[0] = point(scalar(101)).compress();
    assert!(!bulletproof.verify(&mut ChaCha20Rng::from_seed([73; 32]), &altered_commitments));
    let outputs: curve25519_dalek::EdwardsPoint = proofs
        .base
        .commitments
        .iter()
        .map(|p| dp(p.decompress().unwrap()))
        .sum();
    let fee = dp(Commitment::new(scalar(0), FEE).commit());
    assert_eq!(dp(pseudo_outs[0].decompress().unwrap()), outputs + fee);
}

#[test]
fn rejects_changed_review_wrong_wallet_and_truncated_archive() {
    let fixture = fixture();
    let run = |archive: &[u8], spend, amount, fee| {
        sign_archive(
            archive,
            &Zeroizing::new(scalar(spend)),
            &Zeroizing::new(scalar(23)),
            &Zeroizing::new([61; 32]),
            Network::Stagenet,
            Review {
                destination: &fixture.recipient,
                amount,
                fee,
            },
        )
    };
    assert!(run(&fixture.archive, 17, AMOUNT + 1, FEE).is_err());
    assert!(run(&fixture.archive, 17, AMOUNT, FEE + 1).is_err());
    assert!(run(&fixture.archive, 19, AMOUNT, FEE).is_err());
    for length in [
        0,
        1,
        10,
        fixture.archive.len() / 2,
        fixture.archive.len() - 1,
    ] {
        assert!(run(&fixture.archive[..length], 17, AMOUNT, FEE).is_err());
    }
}
