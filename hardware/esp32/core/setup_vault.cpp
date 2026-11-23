// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/setup_vault.hpp"
#include "core/hardware_pin_policy.hpp"
#include "core/monero_address.hpp"
#include "core/rules.hpp"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <new>

namespace airlock {
namespace {
constexpr std::size_t flags_offset = 8, words_offset = 9, name_size_offset = 10, name_offset = 11;
constexpr std::size_t name_capacity = 48, pin_salt_offset = 59, pin_hash_offset = 75;
constexpr std::size_t key_salt_offset = 107, nonce_offset = 123, length_offset = 135;
constexpr std::size_t header_size = 137, plain_size = 291, tag_offset = 428, checksum_offset = 444;
constexpr std::array<std::uint8_t, 8> magic{'B', 'J', 'J', 'W', 'L', 'T', '1', 0};
constexpr unsigned has_pin = 1, has_passphrase = 2, was_imported = 4, mobile_pin = 8;
bool equal(const std::uint8_t* left, const std::uint8_t* right, std::size_t size) {
    unsigned difference = 0;
    for (std::size_t i = 0; i < size; ++i)
        difference |= left[i] ^ right[i];
    return difference == 0;
}
bool supported_words(std::size_t words) {
    return words == 12 || words == 18 || words == 24;
}
crypto::Salt salt_at(const WalletRecord& record, std::size_t offset) {
    crypto::Salt salt{};
    std::copy_n(record.begin() + offset, salt.size(), salt.begin());
    return salt;
}
bool pin_verifier(const crypto::Key& key, std::array<std::uint8_t, 32>& output) {
    crypto::Sensitive<42> message;
    std::memcpy(message.value.data(), "BJJ/PIN/v1", 10);
    std::copy(key.value.begin(), key.value.end(), message.value.begin() + 10);
    return crypto::sha256({message.value.data(), message.value.size()}, output);
}
bool encryption_key(std::string_view phrase, const crypto::Key& pin_key, const crypto::Salt& salt,
                    crypto::Key& key, void (*yield)()) {
    crypto::Sensitive<320> message;
    constexpr std::string_view domain = "BJJ/WALLET/v1";
    auto* data = message.value.data();
    std::memcpy(data, domain.data(), domain.size());
    data[domain.size()] = static_cast<std::uint8_t>(phrase.size() >> 8);
    data[domain.size() + 1] = static_cast<std::uint8_t>(phrase.size());
    std::memcpy(data + domain.size() + 2, phrase.data(), phrase.size());
    const auto position = domain.size() + 2 + phrase.size();
    std::copy(pin_key.value.begin(), pin_key.value.end(), data + position);
    std::array<std::uint8_t, 32> digest{};
    const bool ok =
        crypto::sha256({data, position + 32}, digest) &&
        (yield ? crypto::keystore_key_cooperative({digest.data(), digest.size()}, salt, key, yield)
               : crypto::keystore_key({digest.data(), digest.size()}, salt, key));
    wipe(digest.data(), digest.size());
    return ok;
}
VaultError decode(const WalletRecord& record, WalletProfile& profile) {
    profile = {};
    std::array<std::uint8_t, 32> digest{};
    if (!crypto::sha256({record.data(), checksum_offset}, digest) ||
        !equal(digest.data(), record.data() + checksum_offset, digest.size()) ||
        !std::equal(magic.begin(), magic.end(), record.begin()))
        return VaultError::InvalidData;
    if (!crypto::sha256({record.data() + key_salt_offset, checksum_offset - key_salt_offset},
                        profile.public_id))
        return VaultError::Crypto;
    const auto flags = record[flags_offset];
    const auto name_size = record[name_size_offset];
    if (flags > 15 || name_size == 0 || name_size > name_capacity ||
        !supported_words(record[words_offset]) ||
        (record[length_offset] * 256U + record[length_offset + 1]) != plain_size ||
        ((flags & mobile_pin) && !(flags & has_pin)))
        return VaultError::InvalidData;
    const std::string_view name(reinterpret_cast<const char*>(record.data() + name_offset),
                                name_size);
    if (!valid_wallet_name(name) ||
        !std::all_of(record.begin() + name_offset + name_size, record.begin() + pin_salt_offset,
                     [](auto b) { return b == 0; }))
        return VaultError::InvalidData;
    if (!(flags & has_pin) &&
        !std::all_of(record.begin() + pin_hash_offset, record.begin() + key_salt_offset,
                     [](auto b) { return b == 0; }))
        return VaultError::InvalidData;
    profile.name = name;
    profile.record_id = digest;
    profile.word_count = record[words_offset];
    profile.pin_enabled = flags & has_pin;
    profile.passphrase_enabled = flags & has_passphrase;
    profile.imported = flags & was_imported;
    profile.mobile_access = flags & mobile_pin ? MobileAccess::Pin : MobileAccess::DeviceApproval;
    return VaultError::None;
}
// Version 2 is an atomic device envelope. The original wallet record remains
// byte-for-byte readable; its PIN-derived encryption key is wrapped by the
// device PIN, allowing PIN rotation without asking for every wallet phrase.
constexpr std::size_t device_header = 168;
constexpr std::array<std::uint8_t, 8> device_magic{'B', 'J', 'J', 'D', 'E', 'V', '2', 0};
struct DeviceData {
    std::vector<std::uint8_t> original;
    std::array<std::uint8_t, device_header> header{};
    std::vector<WalletRecord> records;
};
std::uint64_t number(const std::uint8_t* p, unsigned size) {
    std::uint64_t n = 0;
    for (unsigned i = 0; i < size; ++i)
        n = (n << 8) | p[i];
    return n;
}
void number(std::uint8_t* p, unsigned size, std::uint64_t n) {
    while (size) {
        p[--size] = n & 255;
        n >>= 8;
    }
}
VaultError read_device(SetupStore& store, DeviceData& d) {
    const auto read = store.read(d.original);
    if (read == StoreRead::Missing)
        return VaultError::Missing;
    if (read != StoreRead::Found)
        return VaultError::Storage;
    if (d.original.size() == wallet_record_bytes) {
        WalletRecord r{};
        std::copy(d.original.begin(), d.original.end(), r.begin());
        WalletProfile p;
        const auto e = decode(r, p);
        if (e != VaultError::None)
            return e;
        d.header[8] = p.pin_enabled;
        d.header[9] = p.mobile_access == MobileAccess::Pin;
        d.header[11] = 1;
        d.records.push_back(r);
        return VaultError::None;
    }
    if (d.original.size() < min_device_record_bytes ||
        d.original.size() > max_device_record_bytes ||
        !std::equal(device_magic.begin(), device_magic.end(), d.original.begin()))
        return VaultError::InvalidData;
    std::copy_n(d.original.begin(), device_header, d.header.begin());
    const auto count = d.header[11];
    if (count > max_saved_wallets || (!count && (!d.header[10] || !d.header[100])) ||
        d.header[8] > 1 || d.header[9] > 1 || d.header[10] > 2 || (!d.header[8] && d.header[9]) ||
        d.original.size() != device_header + count * wallet_record_bytes + 32 ||
        number(d.header.data() + 16, 8) > (30000ULL << 48) || d.header[100] > 1 ||
        d.header[101] > 1 ||
        !std::all_of(d.header.begin() + 102, d.header.begin() + 104, [](auto b) { return b == 0; }))
        return VaultError::InvalidData;
    std::array<std::uint8_t, 32> hash{};
    if (!crypto::sha256({d.original.data(), d.original.size() - 32}, hash) ||
        !equal(hash.data(), d.original.data() + d.original.size() - 32, 32))
        return VaultError::InvalidData;
    for (unsigned i = 0; i < count; ++i) {
        WalletRecord r{};
        std::copy_n(d.original.begin() + device_header + i * wallet_record_bytes,
                    wallet_record_bytes, r.begin());
        WalletProfile p;
        const auto e = decode(r, p);
        if (e != VaultError::None)
            return e;
        d.records.push_back(r);
    }
    return VaultError::None;
}
VaultError encode_device(DeviceData& d, std::vector<std::uint8_t>& bytes) {
    std::copy(device_magic.begin(), device_magic.end(), d.header.begin());
    d.header[11] = static_cast<std::uint8_t>(d.records.size());
    bytes.assign(d.header.begin(), d.header.end());
    for (const auto& r : d.records)
        bytes.insert(bytes.end(), r.begin(), r.end());
    std::array<std::uint8_t, 32> hash{};
    if (!crypto::sha256({bytes.data(), bytes.size()}, hash))
        return VaultError::Crypto;
    bytes.insert(bytes.end(), hash.begin(), hash.end());
    return VaultError::None;
}
VaultError save_device(SetupStore& store, DeviceData& d) {
    std::vector<std::uint8_t> bytes;
    const auto encoded = encode_device(d, bytes);
    if (encoded != VaultError::None)
        return encoded;
    if (!store.replace({d.original.data(), d.original.size()}, {bytes.data(), bytes.size()}))
        return VaultError::Storage;
    std::vector<std::uint8_t> checked;
    if (store.read(checked) != StoreRead::Found || checked != bytes)
        return VaultError::Storage;
    d.original = std::move(bytes);
    return VaultError::None;
}
// Move the legacy KDF salt/verifier into an authenticated encrypted header.
// An old PIN must not retain a public verifier after device PIN rotation.
bool protect_legacy_pin(DeviceData& d, const crypto::Key& base) {
    auto& r = d.records.front();
    if (r[flags_offset] & has_pin) {
        crypto::Nonce nonce{};
        std::copy_n(r.begin() + nonce_offset, 12, nonce.begin());
        crypto::Tag tag{};
        constexpr std::string_view domain = "BJJ/LEGACY/PIN/v2";
        if (!crypto::seal(base, nonce, {r.data() + pin_salt_offset, 48},
                          {d.header.data() + 104, 48}, tag, crypto::bytes(domain)))
            return false;
        std::copy(tag.begin(), tag.end(), d.header.begin() + 152);
        std::fill_n(r.begin() + pin_salt_offset, 48, 0);
        std::array<std::uint8_t, 32> checksum{};
        if (!crypto::sha256({r.data(), checksum_offset}, checksum))
            return false;
        std::copy(checksum.begin(), checksum.end(), r.begin() + checksum_offset);
    }
    d.header[100] = 1;
    return true;
}
bool restore_legacy_pin(WalletRecord& r, const DeviceData& d, const crypto::Key& base) {
    if (!(r[flags_offset] & has_pin))
        return true;
    crypto::Nonce nonce{};
    std::copy_n(r.begin() + nonce_offset, 12, nonce.begin());
    crypto::Tag tag{};
    std::copy_n(d.header.begin() + 152, 16, tag.begin());
    constexpr std::string_view domain = "BJJ/LEGACY/PIN/v2";
    return crypto::open(base, nonce, {d.header.data() + 104, 48}, tag,
                        {r.data() + pin_salt_offset, 48}, crypto::bytes(domain));
}
bool wrap_device(DeviceData& d, const crypto::Key& base, std::string_view pin, MobileAccess policy,
                 crypto::RandomSource& random, void (*yield)()) {
    d.header[8] = !pin.empty();
    d.header[9] = !pin.empty() && policy == MobileAccess::Pin;
    d.header[10] = valid_hardware_pin(pin) && policy == MobileAccess::Pin ? 2 : 1;
    if (!random.fill({d.header.data() + 24, 16}) || !random.fill({d.header.data() + 40, 12}))
        return false;
    crypto::Salt salt{};
    std::copy_n(d.header.begin() + 24, 16, salt.begin());
    crypto::Nonce nonce{};
    std::copy_n(d.header.begin() + 40, 12, nonce.begin());
    crypto::Key key;
    if (!(yield ? crypto::keystore_key_cooperative(crypto::bytes(pin), salt, key, yield)
                : crypto::keystore_key(crypto::bytes(pin), salt, key)))
        return false;
    crypto::Tag tag{};
    // Authenticate device policy; counters are independent of PIN rotation.
    if (!crypto::seal(key, nonce, {base.value.data(), 32}, {d.header.data() + 52, 32}, tag,
                      {d.header.data() + 8, 3}))
        return false;
    std::copy(tag.begin(), tag.end(), d.header.begin() + 84);
    return true;
}
bool unwrap_device(const DeviceData& d, std::string_view pin, crypto::Key& base, void (*yield)()) {
    crypto::Salt salt{};
    std::copy_n(d.header.begin() + 24, 16, salt.begin());
    crypto::Nonce nonce{};
    std::copy_n(d.header.begin() + 40, 12, nonce.begin());
    crypto::Tag tag{};
    std::copy_n(d.header.begin() + 84, 16, tag.begin());
    crypto::Key key;
    if (!(yield ? crypto::keystore_key_cooperative(crypto::bytes(pin), salt, key, yield)
                : crypto::keystore_key(crypto::bytes(pin), salt, key)))
        return false;
    return crypto::open(key, nonce, {d.header.data() + 52, 32}, tag, {base.value.data(), 32},
                        {d.header.data() + 8, 3});
}
} // namespace
bool valid_wallet_name(std::string_view name) {
    return !name.empty() && name.size() <= name_capacity && valid_text(name) &&
           name.find_first_not_of(' ') != std::string_view::npos;
}
bool valid_setup_pin(std::string_view pin) {
    return valid_hardware_pin(pin);
}
bool valid_transaction_phrase(std::string_view phrase) {
    return phrase.size() <= 256 &&
           validate_credential(phrase, {8, false, false, false, false}) == InputError::None &&
           phrase.find_first_not_of(' ') != std::string_view::npos;
}
VaultError SetupVault::load(WalletRecord& record, WalletProfile& profile, std::size_t index,
                            const crypto::Key* key) {
    DeviceData device;
    const auto status = read_device(store_, device);
    if (status != VaultError::None)
        return status;
    if (index >= device.records.size())
        return VaultError::InvalidData;
    record = device.records[index];
    const auto decoded = decode(record, profile);
    profile.pin_enabled = device.header[8];
    profile.pin_policy_current = device.header[10] == 2 && device.header[8] && device.header[9];
    profile.shared_transaction_phrase = device.header[101];
    profile.mobile_access = device.header[9] ? MobileAccess::Pin : MobileAccess::DeviceApproval;
    if (decoded == VaultError::None && key && device.header[100] &&
        !restore_legacy_pin(record, device, *key))
        return VaultError::Crypto;
    return decoded;
}
VaultError SetupVault::public_wallets(std::string& generation, std::vector<PublicWallet>& wallets) {
    generation.clear();
    wallets.clear();
    DeviceData device;
    const auto read = read_device(store_, device);
    if (read != VaultError::None)
        return read;
    std::vector<std::uint8_t> snapshot{'B', 'J', 'J', '/', 'S', 'Y', 'N', 'C', 1};
    std::vector<PublicWallet> result;
    constexpr char hex[] = "0123456789abcdef";
    for (const auto& record : device.records) {
        WalletProfile profile;
        const auto status = decode(record, profile);
        if (status != VaultError::None)
            return status;
        std::array<std::uint8_t, 32> id{};
        const auto identity = public_wallet_identity(profile, id);
        if (identity != VaultError::None)
            return identity;
        PublicWallet wallet;
        wallet.name = profile.name;
        wallet.word_count = profile.word_count;
        wallet.passphrase_enabled = profile.passphrase_enabled;
        wallet.imported = profile.imported;
        for (auto byte : id) {
            wallet.id += hex[byte >> 4];
            wallet.id += hex[byte & 15];
        }
        if (store_.read_monero_enabled(wallet.id, wallet.monero_enabled) == StoreRead::Error)
            return VaultError::Storage;
        if (std::any_of(result.begin(), result.end(),
                        [&](const auto& w) { return w.id == wallet.id; }))
            return VaultError::InvalidData;
        snapshot.insert(snapshot.end(), profile.public_id.begin(), profile.public_id.end());
        snapshot.insert(snapshot.end(), id.begin(), id.end());
        snapshot.push_back(wallet.name.size());
        snapshot.insert(snapshot.end(), wallet.name.begin(), wallet.name.end());
        snapshot.push_back(wallet.monero_enabled);
        result.push_back(std::move(wallet));
    }
    std::array<std::uint8_t, 32> hash{};
    if (!crypto::sha256({snapshot.data(), snapshot.size()}, hash))
        return VaultError::Crypto;
    for (auto byte : hash) {
        generation += hex[byte >> 4];
        generation += hex[byte & 15];
    }
    wallets = std::move(result);
    return VaultError::None;
}
VaultError SetupVault::monero_enabled(std::size_t index, bool& enabled) {
    std::string generation;
    std::vector<PublicWallet> wallets;
    const auto status = public_wallets(generation, wallets);
    if (status != VaultError::None)
        return status;
    if (index >= wallets.size())
        return VaultError::Missing;
    enabled = wallets[index].monero_enabled;
    return VaultError::None;
}
VaultError SetupVault::set_monero_enabled(const UnlockSession& session, std::size_t index,
                                          bool enabled) {
    if (!session.authorized)
        return VaultError::WrongPin;
    std::string generation;
    std::vector<PublicWallet> wallets;
    const auto status = public_wallets(generation, wallets);
    if (status != VaultError::None)
        return status;
    if (index >= wallets.size())
        return VaultError::Missing;
    if (wallets[index].monero_enabled == enabled)
        return VaultError::None;
    return store_.write_monero_enabled(wallets[index].id, enabled) ? VaultError::None
                                                                   : VaultError::Storage;
}
VaultError SetupVault::inspect(WalletProfile& profile) {
    profile = {};
    WalletRecord record{};
    return load(record, profile);
}
VaultError SetupVault::generate_words(SetupRequest& request) {
    if (request.imported || !supported_words(request.word_count))
        return VaultError::InvalidData;
    request.mnemonic.clear();
    const auto status = crypto::generate_mnemonic(request.word_count, random_, request.mnemonic);
    if (status == crypto::HdError::Random)
        return VaultError::Random;
    return status == crypto::HdError::None ? VaultError::None : VaultError::Crypto;
}
VaultError SetupVault::prepare(const SetupRequest& request, SetupCandidate& candidate) {
    candidate.record.fill(0);
    candidate.backup_words.clear();
    candidate.backup_passphrase.clear();
    candidate.profile = {};
    WalletProfile existing;
    const auto state = inspect(existing);
    if (!preparing_additional_) {
        if (state == VaultError::None)
            return VaultError::AlreadyConfigured;
        if (state != VaultError::Missing)
            return state;
    }
    if (!valid_wallet_name(request.name) || !supported_words(request.word_count) ||
        !valid_transaction_phrase(request.transaction_phrase.view()) ||
        request.passphrase.view().size() > 256 || !valid_text(request.passphrase.view()) ||
        (!preparing_additional_ &&
         (!valid_setup_pin(request.pin.view()) || request.mobile_access != MobileAccess::Pin)) ||
        (request.mobile_access != MobileAccess::Pin &&
         request.mobile_access != MobileAccess::DeviceApproval) ||
        (request.pin.view().empty() && request.mobile_access == MobileAccess::Pin))
        return VaultError::InvalidData;
    Credential generated;
    std::string_view mnemonic = request.mnemonic.view();
    if (!request.imported && mnemonic.empty()) {
        const auto status = crypto::generate_mnemonic(request.word_count, random_, generated);
        if (status == crypto::HdError::Random)
            return VaultError::Random;
        if (status != crypto::HdError::None)
            return VaultError::Crypto;
        mnemonic = generated.view();
    }
    crypto::Entropy entropy;
    if (crypto::mnemonic_entropy(mnemonic, entropy) != crypto::HdError::None ||
        entropy.size / 4 * 3 != request.word_count)
        return VaultError::InvalidData;
    auto& record = candidate.record;
    std::copy(magic.begin(), magic.end(), record.begin());
    record[flags_offset] = (!request.pin.view().empty() ? has_pin : 0) |
                           (!request.passphrase.view().empty() ? has_passphrase : 0) |
                           (request.imported ? was_imported : 0) |
                           (request.mobile_access == MobileAccess::Pin ? mobile_pin : 0);
    record[words_offset] = static_cast<std::uint8_t>(request.word_count);
    record[name_size_offset] = static_cast<std::uint8_t>(request.name.size());
    std::copy(request.name.begin(), request.name.end(), record.begin() + name_offset);
    if (!random_.fill({record.data() + pin_salt_offset, 16}) ||
        !random_.fill({record.data() + key_salt_offset, 16}) ||
        !random_.fill({record.data() + nonce_offset, 12}))
        return VaultError::Random;
    crypto::Key pin_key, key;
    if (!request.pin.view().empty()) {
        if (!(yield_ ? crypto::keystore_key_cooperative(crypto::bytes(request.pin.view()),
                                                        salt_at(record, pin_salt_offset), pin_key,
                                                        yield_)
                     : crypto::keystore_key(crypto::bytes(request.pin.view()),
                                            salt_at(record, pin_salt_offset), pin_key)))
            return VaultError::Crypto;
        std::array<std::uint8_t, 32> verifier{};
        if (!pin_verifier(pin_key, verifier))
            return VaultError::Crypto;
        std::copy(verifier.begin(), verifier.end(), record.begin() + pin_hash_offset);
    }
    if (preparing_key_)
        pin_key.value = preparing_key_->value;
    if (!encryption_key(request.transaction_phrase.view(), pin_key,
                        salt_at(record, key_salt_offset), key, yield_))
        return VaultError::Crypto;
    record[length_offset] = static_cast<std::uint8_t>(plain_size >> 8);
    record[length_offset + 1] = static_cast<std::uint8_t>(plain_size);
    crypto::Sensitive<plain_size> plain;
    plain.value[0] = static_cast<std::uint8_t>(entropy.size);
    std::copy_n(entropy.data.value.begin(), entropy.size, plain.value.begin() + 1);
    plain.value[33] = static_cast<std::uint8_t>(request.passphrase.view().size() >> 8);
    plain.value[34] = static_cast<std::uint8_t>(request.passphrase.view().size());
    std::memcpy(plain.value.data() + 35, request.passphrase.view().data(),
                request.passphrase.view().size());
    crypto::Nonce nonce{};
    std::copy_n(record.begin() + nonce_offset, nonce.size(), nonce.begin());
    crypto::Tag tag{};
    if (!crypto::seal(key, nonce, {plain.value.data(), plain.value.size()},
                      {record.data() + header_size, plain_size}, tag, {record.data(), header_size}))
        return VaultError::Crypto;
    std::copy(tag.begin(), tag.end(), record.begin() + tag_offset);
    std::array<std::uint8_t, 32> digest{};
    if (!crypto::sha256({record.data(), checksum_offset}, digest))
        return VaultError::Crypto;
    std::copy(digest.begin(), digest.end(), record.begin() + checksum_offset);
    if (decode(record, candidate.profile) != VaultError::None)
        return VaultError::InvalidData;
    if (!candidate.backup_words.assign(mnemonic) ||
        !candidate.backup_passphrase.assign(request.passphrase.view()))
        return VaultError::Crypto;
    return cache_public(candidate.profile, mnemonic, request.passphrase.view());
}
VaultError SetupVault::commit(const WalletRecord& record, bool shared) {
    WalletProfile profile;
    if (decode(record, profile) != VaultError::None)
        return VaultError::InvalidData;
    const auto state = inspect(profile);
    if (state == VaultError::None)
        return VaultError::AlreadyConfigured;
    if (state != VaultError::Missing)
        return state;
    if (shared) {
        if (decode(record, profile) != VaultError::None)
            return VaultError::InvalidData;
        DeviceData d;
        d.header[8] = profile.pin_enabled;
        d.header[9] = profile.mobile_access == MobileAccess::Pin;
        d.header[101] = 1;
        d.records.push_back(record);
        std::vector<std::uint8_t> bytes;
        const auto encoded = encode_device(d, bytes);
        if (encoded != VaultError::None)
            return encoded;
        if (!store_.commit_if_empty({bytes.data(), bytes.size()}))
            return VaultError::Storage;
        std::vector<std::uint8_t> persisted;
        return store_.read(persisted) == StoreRead::Found && persisted == bytes
                   ? VaultError::None
                   : VaultError::Storage;
    }
    if (!store_.commit_if_empty({record.data(), record.size()}))
        return VaultError::Storage;
    WalletRecord persisted{};
    return load(persisted, profile) == VaultError::None &&
                   equal(persisted.data(), record.data(), record.size())
               ? VaultError::None
               : VaultError::Storage;
}
VaultError SetupVault::unlock(std::string_view pin, UnlockSession& session) {
    session.clear();
    DeviceData device;
    const auto read = read_device(store_, device);
    if (read != VaultError::None)
        return read;
    // This compatibility entry point only opens unmigrated records. All device
    // unlocks go through the persistent retry gate after migration.
    if (device.header[10])
        return VaultError::WrongPin;
    WalletRecord record{};
    WalletProfile profile;
    const auto status = load(record, profile);
    if (status != VaultError::None)
        return status;
    if (record[flags_offset] & has_pin) {
        if (!valid_setup_pin(pin) && !valid_legacy_hardware_pin(pin))
            return VaultError::WrongPin;
        crypto::Key key;
        if (!(yield_ ? crypto::keystore_key_cooperative(
                           crypto::bytes(pin), salt_at(record, pin_salt_offset), key, yield_)
                     : crypto::keystore_key(crypto::bytes(pin), salt_at(record, pin_salt_offset),
                                            key)))
            return VaultError::Crypto;
        std::array<std::uint8_t, 32> verifier{};
        if (!pin_verifier(key, verifier))
            return VaultError::Crypto;
        if (!equal(verifier.data(), record.data() + pin_hash_offset, verifier.size()))
            return VaultError::WrongPin;
        session.pin_key.value = key.value;
    } else if (!pin.empty())
        return VaultError::WrongPin;
    session.authorized = true;
    return VaultError::None;
}
VaultError SetupVault::recover(const UnlockSession& session, std::string_view transaction_phrase,
                               crypto::Entropy& entropy, Credential& passphrase,
                               std::size_t index) {
    entropy.clear();
    passphrase.clear();
    if (!session.authorized)
        return VaultError::WrongPin;
    if (!valid_transaction_phrase(transaction_phrase))
        return VaultError::WrongPhrase;
    WalletRecord record{};
    WalletProfile profile;
    const auto status = load(record, profile, index, &session.pin_key);
    if (status != VaultError::None)
        return status;
    return decrypt_record(record, profile, session, transaction_phrase, entropy, passphrase);
}
VaultError SetupVault::monero_view_qr(const UnlockSession& session, std::string_view phrase,
                                      std::size_t index, PublicWalletMode mode, Credential& qr,
                                      bool include_view_key) {
    qr.clear();
    if (!session.authorized)
        return VaultError::WrongPin;
    if (!valid_transaction_phrase(phrase))
        return VaultError::WrongPhrase;
    if (mode != PublicWalletMode::Standard && mode != PublicWalletMode::Hidden)
        return VaultError::InvalidData;
    WalletRecord record{};
    WalletProfile profile;
    auto status = load(record, profile, index, &session.pin_key);
    if (status != VaultError::None)
        return status;
    crypto::Entropy entropy;
    Credential passphrase, mnemonic;
    status = decrypt_record(record, profile, session, phrase, entropy, passphrase);
    if (status != VaultError::None)
        return status;
    crypto::Seed seed;
    const auto seed_passphrase =
        mode == PublicWalletMode::Hidden ? passphrase.view() : std::string_view{};
    if (crypto::mnemonic_from_entropy({entropy.data.value.data(), entropy.size}, mnemonic) !=
            crypto::HdError::None ||
        crypto::wallet_core_seed(mnemonic.view(), seed_passphrase, seed) != crypto::HdError::None)
        return VaultError::Crypto;

    std::array<std::uint8_t, 32> wallet_id{};
    status = public_wallet_identity(profile, wallet_id);
    if (status != VaultError::None)
        return status;
    std::string address;
    crypto::Key view;
    if (crypto::derive_monero_view_wallet(seed, crypto::MoneroNetwork::Stagenet, random_, address,
                                          view) != crypto::HdError::None)
        return VaultError::Crypto;
    const auto hex = [](const auto& bytes, auto* out) {
        constexpr char digits[] = "0123456789abcdef";
        for (auto byte : bytes) {
            *out++ = digits[byte >> 4];
            *out++ = digits[byte & 15];
        }
        *out = 0;
    };
    char id[65];
    crypto::Sensitive<65> key;
    hex(wallet_id, id);
    crypto::Sensitive<384> payload;
    auto* text = reinterpret_cast<char*>(payload.value.data());
    const char* mode_name = mode == PublicWalletMode::Standard ? "STANDARD" : "HIDDEN";
    if (include_view_key)
        hex(view.value, key.value.data());
    const auto size = include_view_key
                          ? std::snprintf(text, payload.value.size(),
                                          "{\"wallet_id\":\"%s\",\"mode\":\"%s\",\"network\":"
                                          "\"stagenet\",\"address\":\"%s\","
                                          "\"private_view_key\":\"%s\",\"restore_height\":0}",
                                          id, mode_name, address.c_str(),
                                          reinterpret_cast<const char*>(key.value.data()))
                          : std::snprintf(text, payload.value.size(),
                                          "{\"wallet_id\":\"%s\",\"mode\":\"%s\",\"network\":"
                                          "\"stagenet\",\"address\":\"%s\"}",
                                          id, mode_name, address.c_str());
    if (size < 0 || static_cast<std::size_t>(size) >= payload.value.size() ||
        !qr.assign({text, static_cast<std::size_t>(size)}))
        return VaultError::Crypto;
    return VaultError::None;
}
VaultError SetupVault::decrypt_record(const WalletRecord& record, const WalletProfile& profile,
                                      const UnlockSession& session,
                                      std::string_view transaction_phrase, crypto::Entropy& entropy,
                                      Credential& passphrase) {
    crypto::Key key;
    if (!encryption_key(transaction_phrase, session.pin_key, salt_at(record, key_salt_offset), key,
                        yield_))
        return VaultError::Crypto;
    crypto::Nonce nonce{};
    std::copy_n(record.begin() + nonce_offset, nonce.size(), nonce.begin());
    crypto::Tag tag{};
    std::copy_n(record.begin() + tag_offset, tag.size(), tag.begin());
    crypto::Sensitive<plain_size> plain;
    if (!crypto::open(key, nonce, {record.data() + header_size, plain_size}, tag,
                      {plain.value.data(), plain.value.size()}, {record.data(), header_size}))
        return VaultError::WrongPhrase;
    const auto size = plain.value[0];
    const auto pass_size = plain.value[33] * 256U + plain.value[34];
    if (size < 16 || size > 32 || size % 4 || size / 4 * 3 != profile.word_count ||
        pass_size > 256 || (pass_size != 0) != profile.passphrase_enabled)
        return VaultError::InvalidData;
    const std::string_view pass(reinterpret_cast<char*>(plain.value.data() + 35), pass_size);
    if (!valid_text(pass) || !passphrase.assign(pass))
        return VaultError::InvalidData;
    entropy.size = size;
    std::copy_n(plain.value.begin() + 1, size, entropy.data.value.begin());
    return VaultError::None;
}
VaultError SetupVault::receiving_addresses(const UnlockSession& session,
                                           std::string_view transaction_phrase,
                                           std::vector<ReceivingAddress>& output,
                                           std::size_t index) {
    output.clear();
    crypto::Seed seed;
    std::string cardano;
    {
        crypto::Entropy entropy;
        Credential passphrase, mnemonic;
        const auto status = recover(session, transaction_phrase, entropy, passphrase, index);
        if (status != VaultError::None)
            return status;
        if (crypto::mnemonic_from_entropy({entropy.data.value.data(), entropy.size}, mnemonic) !=
                crypto::HdError::None ||
            crypto::wallet_core_seed(mnemonic.view(), passphrase.view(), seed) !=
                crypto::HdError::None)
            return VaultError::Crypto;
        if (!store_.public_cache_enabled() &&
            cardano_receiving_address(mnemonic.view(), passphrase.view(), cardano) !=
                crypto::HdError::None)
            return VaultError::Crypto;
        WalletProfile profile;
        WalletRecord record{};
        if (load(record, profile, index) != VaultError::None)
            return VaultError::Storage;
        const auto cached = cache_public(profile, mnemonic.view(), passphrase.view());
        if (cached != VaultError::None)
            return cached;
    }
    if (store_.public_cache_enabled()) {
        std::string generation;
        std::vector<PublicAccount> rows;
        const auto error = public_accounts(index, generation, rows);
        if (error != VaultError::None)
            return error;
        for (const auto& row : rows)
            if (row.mode == PublicWalletMode::Hidden)
                output.push_back({row.name, row.address});
        return VaultError::None;
    }
    std::vector<ReceivingAddress> addresses;
    for (const auto& spec : network_accounts) {
        if (spec.first_account_path.empty())
            continue;
        std::string address;
        if (derive_receiving_address(spec.network, seed, random_, address) != crypto::HdError::None)
            return VaultError::Crypto;
        if (yield_)
            yield_();
        addresses.push_back({std::string(spec.name), std::move(address)});
    }
    addresses.push_back({"Cardano", std::move(cardano)});
    output = std::move(addresses);
    return VaultError::None;
}
VaultError SetupVault::device_status(std::uint64_t now, std::vector<WalletProfile>& profiles,
                                     std::uint64_t& retry_ms, WalletProfile* device_profile) {
    profiles.clear();
    retry_ms = 0;
    DeviceData d;
    const auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (device_profile) {
        *device_profile = {};
        device_profile->pin_enabled = d.header[8];
        device_profile->pin_policy_current = d.header[10] == 2 && d.header[8] && d.header[9];
        device_profile->shared_transaction_phrase = d.header[101];
        device_profile->mobile_access =
            d.header[9] ? MobileAccess::Pin : MobileAccess::DeviceApproval;
    }
    const auto pending = number(d.header.data() + 16, 8);
    if (!retry_loaded_) {
        retry_loaded_ = true;
        retry_until_ = now + pending;
    }
    if (pending && now >= retry_until_) {
        number(d.header.data() + 16, 8, 0);
        const auto saved = save_device(store_, d);
        if (saved != VaultError::None)
            return saved;
    }
    retry_ms = retry_until_ > now ? retry_until_ - now : 0;
    for (const auto& r : d.records) {
        WalletProfile p;
        const auto error = decode(r, p);
        if (error != VaultError::None)
            return error;
        p.pin_enabled = d.header[8];
        p.pin_policy_current = d.header[10] == 2 && d.header[8] && d.header[9];
        p.shared_transaction_phrase = d.header[101];
        p.mobile_access = d.header[9] ? MobileAccess::Pin : MobileAccess::DeviceApproval;
        profiles.push_back(std::move(p));
    }
    return VaultError::None;
}
VaultError SetupVault::device_unlock(std::string_view pin, std::uint64_t now,
                                     UnlockSession& session, std::uint64_t& retry_ms, bool remote) {
    session.clear();
    std::vector<WalletProfile> profiles;
    auto e = device_status(now, profiles, retry_ms);
    if (e != VaultError::None)
        return e;
    if (retry_ms)
        return VaultError::Locked;
    DeviceData d;
    e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (d.header[10]) {
        if ((d.header[8] &&
             !(d.header[10] == 2 ? valid_setup_pin(pin)
                                 : (valid_setup_pin(pin) || valid_legacy_hardware_pin(pin)))) ||
            (!d.header[8] && !pin.empty()))
            e = VaultError::WrongPin;
        else if (!unwrap_device(d, pin, session.pin_key, yield_))
            e = VaultError::WrongPin;
        else
            session.authorized = true;
    } else
        e = unlock(pin, session);
    if (e == VaultError::WrongPin) {
        const auto failures =
            std::min<std::uint64_t>(number(d.header.data() + 12, 4) + 1, 0xffffffffULL);
        const auto shift = std::min<std::uint64_t>((failures - 1) / 2, 48);
        retry_ms =
            remote ? (failures < 3 ? 0 : (10000ULL << std::min<std::uint64_t>(failures - 3, 48)))
                   : (30000ULL << shift);
        number(d.header.data() + 12, 4, failures);
        number(d.header.data() + 16, 8, retry_ms);
        const auto saved = save_device(store_, d);
        if (saved != VaultError::None) {
            retry_ms = 0;
            return saved;
        }
        retry_until_ = now + retry_ms;
        return e;
    }
    if (e != VaultError::None)
        return e;
    if (!d.header[10] &&
        !wrap_device(d, session.pin_key, pin,
                     d.header[9] ? MobileAccess::Pin : MobileAccess::DeviceApproval, random_,
                     yield_)) {
        session.clear();
        return VaultError::Crypto;
    }
    if (!d.header[100] && !protect_legacy_pin(d, session.pin_key)) {
        session.clear();
        return VaultError::Crypto;
    }
    number(d.header.data() + 12, 4, 0);
    number(d.header.data() + 16, 8, 0);
    e = save_device(store_, d);
    if (e != VaultError::None)
        session.clear();
    retry_until_ = 0;
    return e;
}
VaultError SetupVault::change_pin(const UnlockSession& session, std::string_view pin,
                                  MobileAccess policy) {
    if (!session.authorized)
        return VaultError::WrongPin;
    if (!valid_setup_pin(pin) || policy != MobileAccess::Pin)
        return VaultError::InvalidData;
    DeviceData d;
    auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (!wrap_device(d, session.pin_key, pin, policy, random_, yield_))
        return VaultError::Crypto;
    return save_device(store_, d);
}
VaultError SetupVault::verify_phrase(const UnlockSession& session, std::string_view phrase,
                                     std::size_t index) {
    crypto::Entropy entropy;
    Credential pass;
    return recover(session, phrase, entropy, pass, index);
}
VaultError SetupVault::prepare_additional(const SetupRequest& request, const UnlockSession& session,
                                          SetupCandidate& candidate) {
    if (!session.authorized)
        return VaultError::WrongPin;
    if (!request.pin.view().empty() || request.mobile_access != MobileAccess::DeviceApproval)
        return VaultError::InvalidData;
    DeviceData d;
    auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (d.records.size() >= max_saved_wallets)
        return VaultError::Full;
    preparing_additional_ = true;
    preparing_key_ = &session.pin_key;
    e = prepare(request, candidate);
    preparing_additional_ = false;
    preparing_key_ = nullptr;
    candidate.profile.pin_enabled = d.header[8];
    candidate.profile.shared_transaction_phrase = d.header[101];
    candidate.profile.mobile_access =
        d.header[9] ? MobileAccess::Pin : MobileAccess::DeviceApproval;
    return e;
}
VaultError SetupVault::append(const WalletRecord& record, const UnlockSession& session) {
    if (!session.authorized)
        return VaultError::WrongPin;
    WalletProfile profile;
    if (decode(record, profile) != VaultError::None)
        return VaultError::InvalidData;
    DeviceData d;
    auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (!d.header[10])
        return VaultError::WrongPin; // must migrate by unlocking first
    if (d.records.size() >= max_saved_wallets)
        return VaultError::Full;
    d.header[101] = 0;
    d.records.push_back(record);
    return save_device(store_, d);
}
VaultError SetupVault::verify_common_phrase(const UnlockSession& session, std::string_view phrase) {
    if (!session.authorized)
        return VaultError::WrongPin;
    DeviceData d;
    auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (!d.header[101] || d.records.empty())
        return VaultError::InvalidData;
    for (std::size_t i = 0; i < d.records.size(); ++i) {
        e = verify_phrase(session, phrase, i);
        if (e != VaultError::None)
            return e;
    }
    return VaultError::None;
}
VaultError SetupVault::append_reusing_common(const WalletRecord& record,
                                             const UnlockSession& session,
                                             std::string_view phrase) {
    DeviceData d;
    auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    e = verify_common_phrase(session, phrase);
    if (e != VaultError::None)
        return e;
    if (d.records.size() >= max_saved_wallets)
        return VaultError::Full;
    WalletProfile profile;
    if (decode(record, profile) != VaultError::None || profile.pin_enabled)
        return VaultError::InvalidData;
    crypto::Entropy entropy;
    Credential pass;
    e = decrypt_record(record, profile, session, phrase, entropy, pass);
    if (e != VaultError::None)
        return e;
    d.records.push_back(record);
    return save_device(store_, d);
}
VaultError SetupVault::rephrase_record(const UnlockSession& session, std::string_view old_phrase,
                                       std::string_view new_phrase, std::size_t index,
                                       WalletRecord& output) {
    crypto::Entropy entropy;
    Credential pass, mnemonic;
    const auto recovered = recover(session, old_phrase, entropy, pass, index);
    if (recovered != VaultError::None)
        return recovered;
    if (crypto::mnemonic_from_entropy({entropy.data.value.data(), entropy.size}, mnemonic) !=
        crypto::HdError::None)
        return VaultError::Crypto;
    WalletRecord current{};
    WalletProfile profile;
    auto e = load(current, profile, index);
    if (e != VaultError::None)
        return e;
    auto request = std::make_unique<SetupRequest>();
    request->name = profile.name;
    request->word_count = profile.word_count;
    request->imported = profile.imported;
    request->mnemonic.assign(mnemonic.view());
    request->passphrase.assign(pass.view());
    request->transaction_phrase.assign(new_phrase);
    auto candidate = std::make_unique<SetupCandidate>();
    std::array<std::uint8_t, 32> stable_id{};
    e = public_wallet_identity(profile, stable_id);
    if (e != VaultError::None)
        return e;
    preparing_additional_ = true;
    preparing_key_ = &session.pin_key;
    preparing_wallet_id_ = &stable_id;
    e = prepare(*request, *candidate);
    preparing_additional_ = false;
    preparing_key_ = nullptr;
    preparing_wallet_id_ = nullptr;
    if (e == VaultError::None)
        output = candidate->record;
    return e;
}
VaultError SetupVault::change_phrase(const UnlockSession& session, std::string_view old_phrase,
                                     std::string_view new_phrase, std::size_t index,
                                     bool shared_single) {
    if (!session.authorized)
        return VaultError::WrongPin;
    if (!valid_transaction_phrase(new_phrase))
        return VaultError::InvalidData;
    DeviceData d;
    auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (index >= d.records.size() || (shared_single && d.records.size() != 1))
        return VaultError::InvalidData;
    e = rephrase_record(session, old_phrase, new_phrase, index, d.records[index]);
    d.header[101] = shared_single;
    return e == VaultError::None ? save_device(store_, d) : e;
}
VaultError SetupVault::change_phrases(const UnlockSession& session, const WalletPhrases& phrases,
                                      std::string_view new_phrase) {
    return update_phrases(session, phrases, new_phrase, nullptr);
}
VaultError SetupVault::append_shared(const WalletRecord& record, const UnlockSession& session,
                                     const WalletPhrases& phrases, std::string_view new_phrase) {
    return update_phrases(session, phrases, new_phrase, &record);
}
VaultError SetupVault::update_phrases(const UnlockSession& session, const WalletPhrases& phrases,
                                      std::string_view new_phrase,
                                      const WalletRecord* append_record) {
    if (!session.authorized)
        return VaultError::WrongPin;
    if (!valid_transaction_phrase(new_phrase))
        return VaultError::InvalidData;
    DeviceData d;
    auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (phrases.size() != d.records.size())
        return VaultError::InvalidData;
    if (append_record) {
        if (!d.header[10] || !d.header[100])
            return VaultError::WrongPin;
        if (d.records.size() >= max_saved_wallets)
            return VaultError::Full;
        WalletProfile profile;
        if (decode(*append_record, profile) != VaultError::None || profile.pin_enabled)
            return VaultError::InvalidData;
        crypto::Entropy entropy;
        Credential pass;
        e = decrypt_record(*append_record, profile, session, new_phrase, entropy, pass);
        if (e != VaultError::None)
            return e;
    }
    std::array<bool, max_saved_wallets> seen{};
    for (const auto& phrase : phrases) {
        if (!phrase || phrase->index >= d.records.size() || seen[phrase->index])
            return VaultError::InvalidData;
        seen[phrase->index] = true;
    }
    // Each recovery reads the unchanged persisted record. Failure at any wallet
    // discards all prepared ciphertexts, leaving every wallet untouched.
    for (const auto& phrase : phrases) {
        e = rephrase_record(session, phrase->current.view(), new_phrase, phrase->index,
                            d.records[phrase->index]);
        if (e != VaultError::None)
            return e;
    }
    if (append_record)
        d.records.push_back(*append_record);
    d.header[101] = 1;
    return save_device(store_, d);
}
VaultError SetupVault::delete_wallet(const UnlockSession& session, std::string_view phrase,
                                     std::size_t index,
                                     const std::array<std::uint8_t, 32>& expected_id) {
    if (!session.authorized)
        return VaultError::WrongPin;
    DeviceData d;
    auto e = read_device(store_, d);
    if (e != VaultError::None)
        return e;
    if (!d.header[10] || !d.header[100] || index >= d.records.size())
        return VaultError::InvalidData;
    WalletProfile selected;
    e = decode(d.records[index], selected);
    if (e != VaultError::None)
        return e;
    // Bind confirmation to the exact ciphertext that was selected. A stale
    // index must never remove another wallet, even if both share a phrase/name.
    if (selected.record_id != expected_id)
        return VaultError::InvalidData;
    e = verify_phrase(session, phrase, index);
    if (e != VaultError::None)
        return e;
    if (index == 0)
        std::fill(d.header.begin() + 104, d.header.end(), 0); // orphaned legacy verifier
    d.records.erase(d.records.begin() + index);
    // Keep the device PIN/wrapping key even when the last wallet is removed.
    // Existing records are copied byte for byte; the NVS blob is replaced once.
    return save_device(store_, d);
}
void run_setup_job(SetupVault& vault, SetupJob& job, SetupResult& result) {
    result.token = job.token;
    result.work = job.work;
    if (job.work == SetupWork::Open) {
        result.error =
            vault.device_status(job.now, result.profiles, result.retry_ms, &result.device_profile);
    } else if (job.work == SetupWork::PublicAddresses) {
        std::string generation;
        std::vector<PublicAccount> rows;
        result.error = vault.public_accounts(job.wallet_index, generation, rows);
        if (result.error == VaultError::None)
            for (const auto& row : rows)
                if (row.mode == PublicWalletMode::Hidden)
                    result.addresses.push_back({row.name, row.address});
    } else if (job.work == SetupWork::Unlock) {
        result.session = std::make_unique<UnlockSession>();
        result.error =
            vault.device_unlock(job.pin.view(), job.now, *result.session, result.retry_ms);
        if (result.error == VaultError::None)
            result.error = vault.device_status(job.now, result.profiles, result.retry_ms,
                                               &result.device_profile);
        if (result.error != VaultError::None)
            result.session.reset();
    } else if (job.work == SetupWork::VerifyCommon) {
        result.error =
            vault.verify_common_phrase(job.address_session, job.transaction_phrase.view());
    } else if (job.work == SetupWork::AppendReuse) {
        result.error =
            vault.append_reusing_common(job.record, job.address_session, job.new_phrase.view());
    } else if (job.work == SetupWork::AppendShared) {
        result.error = vault.append_shared(job.record, job.address_session, job.phrases,
                                           job.new_phrase.view());
    } else if (job.work == SetupWork::DeleteWallet) {
        result.error = vault.delete_wallet(job.address_session, job.transaction_phrase.view(),
                                           job.wallet_index, job.expected_id);
    } else if (job.work == SetupWork::VerifyPhrase) {
        result.error = vault.verify_phrase(job.address_session, job.transaction_phrase.view(),
                                           job.wallet_index);
    } else if (job.work == SetupWork::ChangePin) {
        result.error = vault.change_pin(job.address_session, job.pin.view(), job.mobile_access);
    } else if (job.work == SetupWork::ChangePhrases) {
        result.error =
            vault.change_phrases(job.address_session, job.phrases, job.new_phrase.view());
    } else if (job.work == SetupWork::ChangePhrase) {
        result.error = vault.change_phrase(job.address_session, job.transaction_phrase.view(),
                                           job.new_phrase.view(), job.wallet_index, job.shared);
    } else if (job.work == SetupWork::PrepareAdditional) {
        result.candidate = std::make_unique<SetupCandidate>();
        result.error = job.request ? vault.prepare_additional(*job.request, job.address_session,
                                                              *result.candidate)
                                   : VaultError::InvalidData;
    } else if (job.work == SetupWork::Append) {
        result.error = vault.append(job.record, job.address_session);
    } else if (job.work == SetupWork::GenerateWords) {
        result.error = job.request ? vault.generate_words(*job.request) : VaultError::Storage;
        if (result.error == VaultError::None)
            result.request = std::move(job.request);
    } else if (job.work == SetupWork::Prepare) {
        result.candidate.reset(new (std::nothrow) SetupCandidate);
        result.error = result.candidate && job.request
                           ? vault.prepare(*job.request, *result.candidate)
                           : VaultError::Storage;
        if (result.error != VaultError::None)
            result.candidate.reset();
    } else if (job.work == SetupWork::Commit)
        result.error = vault.commit(job.record, job.shared);
    else if (job.work == SetupWork::ReceivingAddresses) {
        if (!job.address_session.authorized) {
            result.error = VaultError::WrongPin;
            return;
        }
        result.error = vault.receiving_addresses(job.address_session, job.transaction_phrase.view(),
                                                 result.addresses, job.wallet_index);
    } else
        result.error = VaultError::InvalidData;
}
} // namespace airlock
