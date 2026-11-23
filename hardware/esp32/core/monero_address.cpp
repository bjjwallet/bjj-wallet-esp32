// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/monero_address.hpp"
#include "core/monero_archive_crypto.hpp"
#ifdef AIRLOCK_MONERO_TRANSFER_SIGNER
#include "monero_signer/ffi.h"
int32_t airlock_monero_sign_on_worker(const AirlockMoneroSignRequest&, uint8_t*, size_t, size_t&,
                                      uint8_t*);
#endif
#include <TrezorCrypto/ed25519-donna/ed25519-donna.h>
#include <TrezorCrypto/sha3.h>
#include <mbedtls/chacha20.h>
#include <algorithm>
#include <cstring>
#include <limits>
#include <set>

namespace airlock::crypto {
namespace {
class ArchiveReader {
  public:
    explicit ArchiveReader(Bytes data) : data_(data) {}
    std::size_t remaining() const {
        return data_.size;
    }

    bool integer(std::uint64_t& value) {
        value = 0;
        for (unsigned shift = 0; shift < 64 && remaining(); shift += 7) {
            const auto byte = *data_.data;
            skip(1);
            const auto low = byte & 0x7fU;
            if (shift == 63 && low > 1)
                return false;
            value |= std::uint64_t{low} << shift;
            if (!(byte & 0x80U))
                return shift == 0 || low != 0;
        }
        return false;
    }

    bool index(std::uint32_t& value) {
        std::uint64_t wide = 0;
        if (!integer(wide) || wide > std::numeric_limits<std::uint32_t>::max())
            return false;
        value = static_cast<std::uint32_t>(wide);
        return true;
    }

    bool point(MoneroPoint& value) {
        if (remaining() < value.size())
            return false;
        std::memcpy(value.data(), data_.data, value.size());
        return skip(value.size());
    }

    bool skip(std::size_t size) {
        if (size > remaining())
            return false;
        data_.data += size;
        data_.size -= size;
        return true;
    }

  private:
    Bytes data_;
};

struct Scalar {
    bignum256modm value{};
    ~Scalar() {
        wipe(value, sizeof value);
    }
};

bool monero_public_key(const Key& material, Key& scalar, std::uint8_t* public_key) {
    bignum256modm reduced{};
    expand256_modm(reduced, material.value.data(), material.value.size());
    contract256_modm(scalar.value.data(), reduced);
    const bool valid =
        std::any_of(scalar.value.begin(), scalar.value.end(), [](auto byte) { return byte != 0; });
    if (valid) {
        ge25519 point{};
        ge25519_scalarmult_base_niels(&point, ge25519_niels_base_multiples, reduced);
        ge25519_pack(public_key, &point);
    }
    wipe(reduced, sizeof reduced);
    return valid;
}

HdError derive_monero_keys(const Seed& seed, RandomSource& random, Key& spend, Key& view,
                           std::uint8_t* public_spend, std::uint8_t* public_view) {
    DerivedKey node;
    const auto error = derive_key({seed.value.data(), seed.value.size()}, Curve::Ed25519,
                                  "m/44'/128'/0'", random, node);
    if (error != HdError::None)
        return error;
    Key view_material;
    if (!monero_public_key(node.private_key, spend, public_spend))
        return HdError::Crypto;
    keccak_256(spend.value.data(), spend.value.size(), view_material.value.data());
    if (!monero_public_key(view_material, view, public_view))
        return HdError::Crypto;
    return HdError::None;
}

void hash_scalar(const std::uint8_t* data, std::size_t size, Scalar& scalar) {
    Key digest;
    keccak_256(data, size, digest.value.data());
    expand256_modm(scalar.value, digest.value.data(), digest.value.size());
}

bool verify_archive_signature(Bytes ciphertext, const MoneroPoint& public_view,
                              const std::uint8_t* signature) {
    Scalar challenge, response, expected;
    expand256_modm(challenge.value, signature, 32);
    expand256_modm(response.value, signature + 32, 32);
    std::array<std::uint8_t, 64> canonical{};
    contract256_modm(canonical.data(), challenge.value);
    contract256_modm(canonical.data() + 32, response.value);
    if (std::memcmp(canonical.data(), signature, canonical.size()) != 0 ||
        std::none_of(canonical.begin(), canonical.begin() + 32,
                     [](auto byte) { return byte != 0; }))
        return false;
    ge25519 pub{}, commitment{};
    if (!ge25519_unpack_vartime(&pub, public_view.data()))
        return false;
    ge25519_double_scalarmult_vartime(&commitment, &pub, challenge.value, response.value);
    std::array<std::uint8_t, 96> transcript{};
    keccak_256(ciphertext.data, ciphertext.size, transcript.data());
    std::copy(public_view.begin(), public_view.end(), transcript.begin() + 32);
    ge25519_pack(transcript.data() + 64, &commitment);
    constexpr MoneroPoint identity{1};
    if (std::equal(identity.begin(), identity.end(), transcript.begin() + 64))
        return false;
    hash_scalar(transcript.data(), transcript.size(), expected);
    MoneroPoint computed{};
    contract256_modm(computed.data(), expected.value);
    return std::equal(computed.begin(), computed.end(), canonical.begin());
}

bool derive_output_secret(const MoneroOutput& output, const MoneroPoint& transaction_key,
                          const Scalar& view, const Scalar& subaddress_spend, Scalar& secret) {
    ge25519 transaction{}, cleared{}, derivation{};
    MoneroPoint packed{};
    constexpr MoneroPoint identity{1};
    if (!ge25519_unpack_vartime(&transaction, transaction_key.data()))
        return false;
    ge25519_pack(packed.data(), &transaction);
    if (packed != transaction_key)
        return false;
    ge25519_mul8(&cleared, &transaction);
    ge25519_pack(packed.data(), &cleared);
    if (packed == identity)
        return false;
    // Clear the cofactor before applying the private view scalar to peer data.
    ge25519_scalarmult(&derivation, &cleared, view.value);
    Sensitive<37> data;
    ge25519_pack(data.value.data(), &derivation);
    wipe(&derivation, sizeof derivation);
    auto index = output.output_index;
    std::size_t size = 32;
    do {
        data.value[size++] = (index & 0x7f) | (index >= 128 ? 0x80 : 0);
        index >>= 7;
    } while (index);
    hash_scalar(data.value.data(), size, secret);
    add256_modm(secret.value, secret.value, subaddress_spend.value);
    ge25519 point{};
    ge25519_scalarmult_base_wrapper(&point, secret.value);
    ge25519_pack(packed.data(), &point);
    return packed != identity && packed == output.public_key;
}

enum class ArchiveDecryptError { None, Invalid, Crypto };
constexpr std::size_t monero_archive_iv_size = 8, monero_archive_signature_size = 64;

ArchiveDecryptError decrypt_archive(Bytes encrypted, std::string_view magic, const Key& view,
                                    const MoneroPoint& public_view,
                                    std::vector<std::uint8_t>& plaintext, void (*yield)()) {
    const auto overhead = magic.size() + monero_archive_iv_size + monero_archive_signature_size;
    if (!encrypted.data || encrypted.size <= overhead ||
        std::memcmp(encrypted.data, magic.data(), magic.size()) != 0)
        return ArchiveDecryptError::Invalid;
    const auto* iv = encrypted.data + magic.size();
    const auto* signature = encrypted.data + encrypted.size - monero_archive_signature_size;
    if (!verify_archive_signature({iv, static_cast<std::size_t>(signature - iv)}, public_view,
                                  signature))
        return ArchiveDecryptError::Invalid;
    Key key;
    if (!monero_archive_key({view.value.data(), view.value.size()}, key, yield))
        return ArchiveDecryptError::Crypto;
    plaintext.resize(encrypted.size - overhead);
    // The original ChaCha20 uses an eight-byte nonce and a 64-bit counter.
    // The high counter word is zero for the supported archive sizes.
    std::array<std::uint8_t, 12> nonce{};
    std::copy_n(iv, monero_archive_iv_size, nonce.begin() + 4);
    return mbedtls_chacha20_crypt(key.value.data(), nonce.data(), 0, plaintext.size(),
                                  iv + monero_archive_iv_size, plaintext.data()) == 0
               ? ArchiveDecryptError::None
               : ArchiveDecryptError::Crypto;
}

bool generate_nonce(RandomSource& random, Scalar& nonce) {
    Key candidate, reduced;
    for (unsigned attempt = 0; attempt < 128; ++attempt) {
        if (!random.fill({candidate.value.data(), candidate.value.size()}))
            return false;
        candidate.value.back() &= 0x1f;
        expand256_modm(nonce.value, candidate.value.data(), candidate.value.size());
        contract256_modm(reduced.value.data(), nonce.value);
        if (reduced.value == candidate.value &&
            std::any_of(reduced.value.begin(), reduced.value.end(),
                        [](auto byte) { return byte != 0; }))
            return true;
    }
    return false;
}

bool sign_image(const MoneroPoint& public_key, const Scalar& secret, RandomSource& random,
                MoneroSignedKeyImage& proof) {
    // Monero wallet2::export_key_images signs the key image with a ring of
    // size one. The challenge hashes image || k*G || k*Hp(output_key).
    MoneroPoint digest{};
    keccak_256(public_key.data(), public_key.size(), digest.data());
    ge25519 mapped{}, hash_point{}, point{};
    ge25519_fromfe_frombytes_vartime(&mapped, digest.data());
    ge25519_mul8(&hash_point, &mapped);
    ge25519_scalarmult(&point, &hash_point, secret.value);
    ge25519_pack(proof.key_image.data(), &point);

    Scalar nonce, challenge, response;
    if (!generate_nonce(random, nonce))
        return false;
    std::array<std::uint8_t, 96> transcript{};
    std::copy(proof.key_image.begin(), proof.key_image.end(), transcript.begin());
    ge25519_scalarmult_base_wrapper(&point, nonce.value);
    ge25519_pack(transcript.data() + 32, &point);
    ge25519_scalarmult(&point, &hash_point, nonce.value);
    ge25519_pack(transcript.data() + 64, &point);
    hash_scalar(transcript.data(), transcript.size(), challenge);
    mulsub256_modm(response.value, challenge.value, secret.value, nonce.value);
    contract256_modm(proof.signature.data(), challenge.value);
    contract256_modm(proof.signature.data() + 32, response.value);
    return true;
}

// A standard address has eight full Base58 blocks and one five-byte tail.
std::string encode_address(const std::array<std::uint8_t, 69>& bytes) {
    constexpr char alphabet[] = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    std::string result(95, alphabet[0]);
    for (std::size_t input = 0, output = 0; input < bytes.size(); input += 8, output += 11) {
        const auto count = std::min<std::size_t>(8, bytes.size() - input);
        std::uint64_t value = 0;
        for (std::size_t i = 0; i < count; ++i)
            value = (value << 8) | bytes[input + i];
        auto end = std::min(result.size(), output + 11);
        while (value) {
            result[--end] = alphabet[value % 58];
            value /= 58;
        }
    }
    return result;
}
} // namespace

MoneroKeyImageError parse_monero_output_archive(Bytes plaintext, const MoneroPoint& public_spend,
                                                const MoneroPoint& public_view,
                                                std::vector<MoneroOutput>& result) {
    result.clear();
    if (plaintext.size > monero_max_output_archive_bytes)
        return MoneroKeyImageError::TooManyOutputs;
    if (!plaintext.data)
        return MoneroKeyImageError::InvalidOutputs;
    ArchiveReader archive(plaintext);
    MoneroPoint spend{}, view{};
    std::uint64_t tuple_size = 0, offset = 0, total = 0, count = 0;
    if (!archive.point(spend) || !archive.point(view) || spend != public_spend ||
        view != public_view || !archive.integer(tuple_size) || tuple_size != 3 ||
        !archive.integer(offset) || offset != 0 || !archive.integer(total) ||
        !archive.integer(count) || count != total)
        return MoneroKeyImageError::InvalidOutputs;
    if (count > monero_max_key_images)
        return MoneroKeyImageError::TooManyOutputs;
    if (count > archive.remaining() / 72)
        return MoneroKeyImageError::InvalidOutputs;

    std::vector<MoneroOutput> parsed;
    parsed.reserve(static_cast<std::size_t>(count));
    for (std::size_t i = 0; i < count; ++i) {
        MoneroOutput output;
        std::uint64_t version = 0, ignored = 0, additional = 0;
        if (!archive.integer(version) || version != 1 || !archive.point(output.public_key) ||
            !archive.index(output.output_index) || !archive.integer(ignored) ||
            !archive.point(output.transaction_key) || !archive.skip(1) ||
            !archive.integer(ignored) || !archive.integer(additional) ||
            additional > archive.remaining() / MoneroPoint{}.size())
            return MoneroKeyImageError::InvalidOutputs;
        if (additional) {
            if (output.output_index >= additional)
                return MoneroKeyImageError::InvalidOutputs;
            const auto key_size = output.public_key.size();
            if (!archive.skip(output.output_index * key_size) ||
                !archive.point(output.additional_key.emplace()) ||
                !archive.skip((additional - output.output_index - 1) * key_size))
                return MoneroKeyImageError::InvalidOutputs;
        }
        if (!archive.index(output.subaddress_major) || !archive.index(output.subaddress_minor))
            return MoneroKeyImageError::InvalidOutputs;
        parsed.push_back(output);
    }
    if (archive.remaining())
        return MoneroKeyImageError::InvalidOutputs;
    result = std::move(parsed);
    return MoneroKeyImageError::None;
}

HdError derive_monero_address(const Seed& seed, MoneroNetwork network, RandomSource& random,
                              std::string& address) {
    Key view;
    return derive_monero_view_wallet(seed, network, random, address, view);
}

HdError derive_monero_view_wallet(const Seed& seed, MoneroNetwork network, RandomSource& random,
                                  std::string& address, Key& view) {
    address.clear();
    wipe(view.value.data(), view.value.size());
    std::array<std::uint8_t, 69> encoded{};
    switch (network) {
        case MoneroNetwork::Mainnet:
            encoded[0] = 18;
            break;
        case MoneroNetwork::Stagenet:
            encoded[0] = 24;
            break;
        default:
            return HdError::InvalidPath;
    }
    Key spend;
    const auto error =
        derive_monero_keys(seed, random, spend, view, encoded.data() + 1, encoded.data() + 33);
    if (error != HdError::None)
        return error;

    std::array<std::uint8_t, 32> digest{};
    keccak_256(encoded.data(), 65, digest.data());
    std::copy_n(digest.begin(), 4, encoded.begin() + 65);
    address = encode_address(encoded);
    return HdError::None;
}

MoneroKeyImageError sign_monero_key_images(const Seed& seed,
                                           const std::vector<MoneroOutput>& outputs,
                                           RandomSource& random,
                                           std::vector<MoneroSignedKeyImage>& result) {
    result.clear();
    if (outputs.size() > monero_max_key_images)
        return MoneroKeyImageError::TooManyOutputs;
    if (outputs.empty())
        return MoneroKeyImageError::None;
    Key spend_key, view_key;
    MoneroPoint public_spend{}, public_view{};
    if (derive_monero_keys(seed, random, spend_key, view_key, public_spend.data(),
                           public_view.data()) != HdError::None)
        return MoneroKeyImageError::Crypto;
    Scalar spend, view;
    expand256_modm(spend.value, spend_key.value.data(), spend_key.value.size());
    expand256_modm(view.value, view_key.value.data(), view_key.value.size());
    std::set<MoneroPoint> seen;
    std::vector<MoneroSignedKeyImage> proofs;
    proofs.reserve(outputs.size());
    for (const auto& output : outputs) {
        if (!seen.insert(output.public_key).second)
            return MoneroKeyImageError::InvalidOutputs;
        Scalar subaddress_spend, secret;
        std::copy_n(spend.value, bignum256modm_limb_size, subaddress_spend.value);
        if (output.subaddress_major || output.subaddress_minor) {
            Sensitive<48> material;
            constexpr std::array<std::uint8_t, 8> domain{'S', 'u', 'b', 'A', 'd', 'd', 'r', 0};
            std::copy(domain.begin(), domain.end(), material.value.begin());
            std::copy(view_key.value.begin(), view_key.value.end(), material.value.begin() + 8);
            for (unsigned byte = 0; byte < 4; ++byte) {
                material.value[40 + byte] = output.subaddress_major >> (8 * byte);
                material.value[44 + byte] = output.subaddress_minor >> (8 * byte);
            }
            Scalar offset;
            hash_scalar(material.value.data(), material.value.size(), offset);
            add256_modm(subaddress_spend.value, spend.value, offset.value);
        }
        if (!derive_output_secret(output, output.transaction_key, view, subaddress_spend, secret) &&
            (!output.additional_key ||
             !derive_output_secret(output, *output.additional_key, view, subaddress_spend, secret)))
            return MoneroKeyImageError::InvalidOutputs;
        if (!sign_image(output.public_key, secret, random, proofs.emplace_back()))
            return MoneroKeyImageError::Crypto;
    }
    result = std::move(proofs);
    return MoneroKeyImageError::None;
}

bool monero_transfer_supported() {
#ifdef AIRLOCK_MONERO_TRANSFER_SIGNER
    return true;
#else
    return false;
#endif
}

#ifdef AIRLOCK_MONERO_TRANSFER_SIGNER
MoneroTransferError sign_monero_transfer_archive(const Seed& seed, Bytes encrypted,
                                                 std::string_view recipient, std::uint64_t amount,
                                                 std::uint64_t fee, RandomSource& random,
                                                 MoneroSignedTransfer& result, void (*yield)()) {
    result = {};
    if (encrypted.size > monero_max_unsigned_bytes || recipient.size() != 95)
        return MoneroTransferError::Capacity;
    Key spend, view, entropy;
    MoneroPoint public_spend{}, public_view{};
    if (derive_monero_keys(seed, random, spend, view, public_spend.data(), public_view.data()) !=
        HdError::None)
        return MoneroTransferError::Crypto;
    std::vector<std::uint8_t> plaintext;
    const auto status = decrypt_archive(encrypted, "Monero unsigned tx set\005", view, public_view,
                                        plaintext, yield);
    if (status != ArchiveDecryptError::None ||
        !random.fill({entropy.value.data(), entropy.value.size()})) {
        wipe(plaintext.data(), plaintext.size());
        return status == ArchiveDecryptError::Invalid ? MoneroTransferError::InvalidArchive
                                                      : MoneroTransferError::Crypto;
    }

    result.transaction.resize(16 * 1024);
    const AirlockMoneroSignRequest request{plaintext.data(),
                                           plaintext.size(),
                                           spend.value.data(),
                                           view.value.data(),
                                           entropy.value.data(),
                                           reinterpret_cast<const std::uint8_t*>(recipient.data()),
                                           recipient.size(),
                                           amount,
                                           fee,
                                           true};
    std::size_t written = 0;
    const auto error = airlock_monero_sign_on_worker(
        request, result.transaction.data(), result.transaction.size(), written, result.txid.data());
    wipe(plaintext.data(), plaintext.size());
    if (!error && written && written <= result.transaction.size()) {
        result.transaction.resize(written);
        return MoneroTransferError::None;
    }
    result = {};
    switch (error) {
        case 1:
            return MoneroTransferError::InvalidArchive;
        case 2:
            return MoneroTransferError::Unsupported;
        case 3:
            return MoneroTransferError::ReviewMismatch;
        case 4:
            return MoneroTransferError::InputNotOwned;
        case 6:
            return MoneroTransferError::Capacity;
        default:
            return MoneroTransferError::Crypto;
    }
}
#else
MoneroTransferError sign_monero_transfer_archive(const Seed&, Bytes, std::string_view,
                                                 std::uint64_t, std::uint64_t, RandomSource&,
                                                 MoneroSignedTransfer& result, void (*)()) {
    result = {};
    return MoneroTransferError::Unsupported;
}
#endif

MoneroKeyImageError sign_monero_output_archive(const Seed& seed, Bytes encrypted,
                                               RandomSource& random,
                                               std::vector<MoneroSignedKeyImage>& result,
                                               void (*yield)()) {
    result.clear();
    constexpr std::string_view magic = "Monero output export\004";
    constexpr auto overhead = magic.size() + monero_archive_iv_size + monero_archive_signature_size;
    if (encrypted.size > monero_max_output_archive_bytes + overhead)
        return MoneroKeyImageError::TooManyOutputs;
    if (!encrypted.data || encrypted.size < overhead + 68 ||
        std::memcmp(encrypted.data, magic.data(), magic.size()) != 0)
        return MoneroKeyImageError::InvalidOutputs;

    Key spend, view;
    MoneroPoint public_spend{}, public_view{};
    if (derive_monero_keys(seed, random, spend, view, public_spend.data(), public_view.data()) !=
        HdError::None)
        return MoneroKeyImageError::Crypto;
    std::vector<std::uint8_t> plaintext;
    const auto decrypted = decrypt_archive(encrypted, magic, view, public_view, plaintext, yield);
    std::vector<MoneroOutput> outputs;
    auto parsed = MoneroKeyImageError::InvalidOutputs;
    if (decrypted == ArchiveDecryptError::None)
        parsed = parse_monero_output_archive({plaintext.data(), plaintext.size()}, public_spend,
                                             public_view, outputs);
    else if (decrypted == ArchiveDecryptError::Crypto)
        parsed = MoneroKeyImageError::Crypto;
    wipe(plaintext.data(), plaintext.size());
    if (parsed != MoneroKeyImageError::None)
        return parsed;
    return sign_monero_key_images(seed, outputs, random, result);
}
} // namespace airlock::crypto
