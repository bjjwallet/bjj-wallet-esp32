// SPDX-License-Identifier: GPL-3.0-or-later
// Native port of core/service.py, core/database.py and js/Utils.js.
#include "simulator/native_wallet.hpp"
#include "core/cardano_icarus.hpp"
#include "core/json_memory.hpp"
#include "coin_registry.hpp"
#include <TrustWalletCore/TWHDWallet.h>
#include <TrustWalletCore/TWMnemonic.h>
#include <TrustWalletCore/TWAnyAddress.h>
#include <TrustWalletCore/TWDerivationPath.h>
#include <TrustWalletCore/TWBase58.h>
#include <Security/Security.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <sqlite3.h>
#include "cJSON.h"
#include <array>
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <vector>
#include <sys/file.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace airlock {
namespace {
using Path = std::filesystem::path;
struct StringDelete {
    void operator()(TWString* p) const {
        if (p) {
            wipe(const_cast<char*>(TWStringUTF8Bytes(p)), TWStringSize(p));
            TWStringDelete(p);
        }
    }
};
using String = std::unique_ptr<TWString, StringDelete>;
String tw(std::string_view value) {
    return String(TWStringCreateWithRawBytes(reinterpret_cast<const std::uint8_t*>(value.data()),
                                             value.size()));
}
std::string_view str(const String& value) {
    return value ? std::string_view(TWStringUTF8Bytes(value.get()), TWStringSize(value.get()))
                 : std::string_view{};
}
using Wallet = std::unique_ptr<TWHDWallet, decltype(&TWHDWalletDelete)>;
using Key = std::unique_ptr<TWPrivateKey, decltype(&TWPrivateKeyDelete)>;
using PublicKey = std::unique_ptr<TWPublicKey, decltype(&TWPublicKeyDelete)>;
using Address = std::unique_ptr<TWAnyAddress, decltype(&TWAnyAddressDelete)>;
using Derivation = std::unique_ptr<TWDerivationPath, decltype(&TWDerivationPathDelete)>;
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
using Cipher = std::unique_ptr<EVP_CIPHER_CTX, decltype(&EVP_CIPHER_CTX_free)>;
using Database = std::unique_ptr<sqlite3, decltype(&sqlite3_close)>;
using Statement = std::unique_ptr<sqlite3_stmt, decltype(&sqlite3_finalize)>;

using JsonText = std::unique_ptr<char, decltype(&json_free)>;
bool random_bytes(void* p, std::size_t size) {
    return SecRandomCopyBytes(kSecRandomDefault, size, static_cast<std::uint8_t*>(p)) ==
           errSecSuccess;
}

bool write_file(const Path& path, std::string_view value) {
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0)
        return false;
    std::size_t offset = 0;
    bool ok = true;
    while (offset < value.size()) {
        const auto n = write(fd, value.data() + offset, value.size() - offset);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            ok = false;
            break;
        }
        offset += static_cast<std::size_t>(n);
    }
    if (ok)
        ok = fsync(fd) == 0;
    if (close(fd) != 0)
        ok = false;
    return ok;
}
bool sync_directory(const Path& path) {
    const int fd = open(path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0)
        return false;
    const bool ok = fsync(fd) == 0;
    close(fd);
    return ok;
}
std::string read_file(const Path& path, std::size_t limit) {
    const int fd = open(path.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0)
        return {};
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 0 ||
        static_cast<std::uint64_t>(st.st_size) > limit) {
        close(fd);
        return {};
    }
    std::string result(static_cast<std::size_t>(st.st_size), '\0');
    std::size_t offset = 0;
    while (offset < result.size()) {
        const auto n = read(fd, result.data() + offset, result.size() - offset);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            close(fd);
            return {};
        }
        offset += static_cast<std::size_t>(n);
    }
    close(fd);
    return result;
}
std::string current(const Path& root) {
    const auto name = read_file(root / "CURRENT", 32);
    if (name.size() != 32 || name.find_first_not_of("0123456789abcdef") != std::string::npos)
        return {};
    return name;
}
std::string base64(const unsigned char* data, std::size_t size) {
    std::string out(4 * ((size + 2) / 3), '\0');
    // EVP writes the terminating zero too; std::string owns that byte.
    EVP_EncodeBlock(reinterpret_cast<unsigned char*>(out.data()), data, static_cast<int>(size));
    return out;
}
bool unbase64(const char* text, std::vector<unsigned char>& out) {
    if (!text)
        return false;
    const std::string_view value(text);
    if (value.empty() || value.size() > 16384 || value.size() % 4)
        return false;
    out.resize(value.size() / 4 * 3);
    int n = EVP_DecodeBlock(out.data(), reinterpret_cast<const unsigned char*>(text),
                            static_cast<int>(value.size()));
    if (n < 0)
        return false;
    if (value.back() == '=')
        --n;
    if (value[value.size() - 2] == '=')
        --n;
    if (n < 0)
        return false;
    out.resize(static_cast<std::size_t>(n));
    return base64(out.data(), out.size()) == value; // canonical Base64 only
}
const char* field(cJSON* json, const char* key) {
    const auto* value = cJSON_GetObjectItemCaseSensitive(json, key);
    return cJSON_IsString(value) ? value->valuestring : nullptr;
}
bool derive(std::string_view password, const unsigned char* salt, unsigned char* key) {
    return PKCS5_PBKDF2_HMAC(password.data(), static_cast<int>(password.size()), salt, 16,
                             config::keystore_iterations, EVP_sha256(), 32, key) == 1;
}
bool encrypt_store(const Path& path, std::string_view mnemonic, std::string_view passphrase,
                   std::string_view password, bool imported) {
    Json plain(cJSON_CreateObject(), cJSON_Delete);
    const std::string_view empty("");
    // Input views come from null-terminated Secret buffers (or empty literal).
    if (!plain || !cJSON_AddStringToObject(plain.get(), "mnemonic", mnemonic.data()) ||
        !cJSON_AddStringToObject(plain.get(), "passphrase",
                                 passphrase.empty() ? empty.data() : passphrase.data()) ||
        !cJSON_AddBoolToObject(plain.get(), "isImported", imported))
        return false;
    JsonText serialized(cJSON_PrintUnformatted(plain.get()), json_free);
    if (!serialized)
        return false;
    const auto length = std::strlen(serialized.get());
    std::array<unsigned char, 16> salt{};
    std::array<unsigned char, 12> nonce{};
    std::array<unsigned char, 32> key{};
    if (!random_bytes(salt.data(), salt.size()) || !random_bytes(nonce.data(), nonce.size()) ||
        !derive(password, salt.data(), key.data())) {
        wipe(key.data(), key.size());
        return false;
    }
    Cipher ctx(EVP_CIPHER_CTX_new(), EVP_CIPHER_CTX_free);
    std::vector<unsigned char> ciphertext(length + 16);
    int n = 0, tail = 0;
    bool ok =
        ctx &&
        EVP_EncryptInit_ex(ctx.get(), EVP_aes_256_gcm(), nullptr, key.data(), nonce.data()) == 1 &&
        EVP_EncryptUpdate(ctx.get(), ciphertext.data(), &n,
                          reinterpret_cast<const unsigned char*>(serialized.get()),
                          static_cast<int>(length)) == 1 &&
        EVP_EncryptFinal_ex(ctx.get(), ciphertext.data() + n, &tail) == 1 &&
        EVP_CIPHER_CTX_ctrl(ctx.get(), EVP_CTRL_GCM_GET_TAG, 16, ciphertext.data() + length) == 1;
    wipe(key.data(), key.size());
    if (!ok || static_cast<std::size_t>(n + tail) != length)
        return false;
    Json store(cJSON_CreateObject(), cJSON_Delete);
    if (!store || !cJSON_AddNumberToObject(store.get(), "version", 1) ||
        !cJSON_AddStringToObject(store.get(), "cipher", "aes-256-gcm") ||
        !cJSON_AddStringToObject(store.get(), "kdf", "pbkdf2-sha256") ||
        !cJSON_AddStringToObject(store.get(), "salt", base64(salt.data(), salt.size()).c_str()) ||
        !cJSON_AddStringToObject(store.get(), "nonce",
                                 base64(nonce.data(), nonce.size()).c_str()) ||
        !cJSON_AddStringToObject(store.get(), "ciphertext",
                                 base64(ciphertext.data(), ciphertext.size()).c_str()))
        return false;
    JsonText encoded(cJSON_PrintUnformatted(store.get()), json_free);
    return encoded && write_file(path, encoded.get());
}
bool decrypt_store(const Path& path, std::string_view password, Credential& mnemonic,
                   Credential& passphrase, bool& imported) {
    mnemonic.clear();
    passphrase.clear();
    imported = false;
    const auto encoded = read_file(path, 16384);
    Json store(parse_json(encoded), cJSON_Delete);
    if (!store || !cJSON_IsObject(store.get()))
        return false;
    const char* cipher = field(store.get(), "cipher");
    const char* kdf = field(store.get(), "kdf");
    const auto* version = cJSON_GetObjectItemCaseSensitive(store.get(), "version");
    if (!cipher || !kdf || std::strcmp(cipher, "aes-256-gcm") ||
        std::strcmp(kdf, "pbkdf2-sha256") || !cJSON_IsNumber(version) || version->valuedouble != 1)
        return false;
    std::vector<unsigned char> salt, nonce, ciphertext;
    if (!unbase64(field(store.get(), "salt"), salt) || salt.size() != 16 ||
        !unbase64(field(store.get(), "nonce"), nonce) || nonce.size() != 12 ||
        !unbase64(field(store.get(), "ciphertext"), ciphertext) || ciphertext.size() <= 16)
        return false;
    std::array<unsigned char, 32> key{};
    if (!derive(password, salt.data(), key.data())) {
        wipe(key.data(), key.size());
        return false;
    }
    // Cleared on every path, including authentication failure before JSON parse.
    std::array<unsigned char, 8192> plain{};
    const auto length = ciphertext.size() - 16;
    if (length >= plain.size()) {
        wipe(key.data(), key.size());
        return false;
    }
    Cipher ctx(EVP_CIPHER_CTX_new(), EVP_CIPHER_CTX_free);
    int n = 0, tail = 0;
    bool ok =
        ctx &&
        EVP_DecryptInit_ex(ctx.get(), EVP_aes_256_gcm(), nullptr, key.data(), nonce.data()) == 1 &&
        EVP_DecryptUpdate(ctx.get(), plain.data(), &n, ciphertext.data(),
                          static_cast<int>(length)) == 1 &&
        EVP_CIPHER_CTX_ctrl(ctx.get(), EVP_CTRL_GCM_SET_TAG, 16, ciphertext.data() + length) == 1 &&
        EVP_DecryptFinal_ex(ctx.get(), plain.data() + n, &tail) == 1;
    wipe(key.data(), key.size());
    if (!ok || static_cast<std::size_t>(n + tail) != length) {
        wipe(plain.data(), plain.size());
        return false;
    }
    Json data(parse_json({reinterpret_cast<char*>(plain.data()), length}), cJSON_Delete);
    wipe(plain.data(), plain.size());
    const auto* words = data ? field(data.get(), "mnemonic") : nullptr;
    const auto* phrase = data ? field(data.get(), "passphrase") : nullptr;
    const auto* flag = data ? cJSON_GetObjectItemCaseSensitive(data.get(), "isImported") : nullptr;
    if (!words || !phrase || !cJSON_IsBool(flag))
        return false;
    if (!mnemonic.assign(words) || !passphrase.assign(phrase)) {
        mnemonic.clear();
        passphrase.clear();
        return false;
    }
    imported = cJSON_IsTrue(flag);
    return true;
}

constexpr const char* schema =
    "PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; BEGIN IMMEDIATE;"
    "CREATE TABLE wallets_xpub (id INTEGER PRIMARY KEY AUTOINCREMENT, coin TEXT, symbol TEXT, name "
    "TEXT, address TEXT, extended_public_key TEXT, path TEXT, decimals INTEGER, blockchain TEXT, "
    "curve TEXT, is_main INTEGER);"
    "CREATE TABLE wallets_address (id INTEGER PRIMARY KEY AUTOINCREMENT, coin TEXT, symbol TEXT, "
    "name TEXT, address TEXT, path TEXT, address_index INTEGER, decimals INTEGER, blockchain TEXT, "
    "curve TEXT, is_main INTEGER);";
bool add_account(sqlite3* db, const CoinRecord& coin, std::string_view path, const String& address,
                 const String& xpub, int index, bool main) {
    const bool extended = xpub && !str(xpub).empty();
    const char* query = extended ? "INSERT INTO wallets_xpub "
                                   "(coin,symbol,name,address,extended_public_key,path,decimals,"
                                   "blockchain,curve,is_main) VALUES (?,?,?,?,?,?,?,?,?,?)"
                                 : "INSERT INTO wallets_address "
                                   "(coin,symbol,name,address,path,address_index,decimals,"
                                   "blockchain,curve,is_main) VALUES (?,?,?,?,?,?,?,?,?,?)";
    sqlite3_stmt* raw = nullptr;
    if (sqlite3_prepare_v2(db, query, -1, &raw, nullptr) != SQLITE_OK)
        return false;
    Statement statement(raw, sqlite3_finalize);
    auto bind = [&](int i, std::string_view value) {
        return sqlite3_bind_text(raw, i, value.data(), static_cast<int>(value.size()),
                                 SQLITE_TRANSIENT) == SQLITE_OK;
    };
    bool ok =
        bind(1, coin.id) && bind(2, coin.symbol) && bind(3, coin.name) && bind(4, str(address));
    if (extended)
        ok = ok && bind(5, str(xpub)) && bind(6, path);
    else
        ok = ok && bind(5, path) && sqlite3_bind_int(raw, 6, index) == SQLITE_OK;
    return ok && sqlite3_bind_int(raw, 7, coin.decimals) == SQLITE_OK && bind(8, coin.blockchain) &&
           bind(9, coin.curve) && sqlite3_bind_int(raw, 10, main) == SQLITE_OK &&
           sqlite3_step(raw) == SQLITE_DONE;
}
bool accounts(sqlite3* db, std::string_view mnemonic, std::string_view phrase, bool main,
              WalletResult& result) {
    auto words = tw(mnemonic), pass = tw(phrase);
    Wallet wallet(TWHDWalletCreateWithMnemonic(words.get(), pass.get()), TWHDWalletDelete);
    if (!wallet)
        return false;
    for (const auto& coin : coin_registry) {
        const bool secp = std::string_view(coin.curve) == "secp256k1";
        const bool ed = std::string_view(coin.curve) == "ed25519";
        const bool nano = std::string_view(coin.curve) == "ed25519Blake2bNano";
        const bool nist = std::string_view(coin.curve) == "nist256p1";
        const bool cardano = std::string_view(coin.curve) == "ed25519ExtendedCardano";
        // Fail instead of publishing a silently incomplete set of accounts.
        if (!secp && !ed && !nano && !nist && !cardano) {
            ++result.failed_coins;
            return false;
        }
        const auto type = static_cast<TWCoinType>(coin.coin);
        const auto count = secp   ? 1U
                           : main ? config::hidden_ed25519_accounts
                                  : config::standard_ed25519_accounts;
        for (std::size_t i = 0; i < count; ++i) {
            std::string path = coin.path;
            String xpub(nullptr);
            PublicKey pub(nullptr, TWPublicKeyDelete);
            if (secp) {
                auto path_string = tw(path);
                Derivation derivation(TWDerivationPathCreateWithString(path_string.get()),
                                      TWDerivationPathDelete);
                if (!derivation) {
                    ++result.failed_coins;
                    break;
                }
                // The native API already uses the enum required by public sync.
                xpub.reset(TWHDWalletGetExtendedPublicKeyAccount(
                    wallet.get(), TWDerivationPathPurpose(derivation.get()), type,
                    TWDerivationDefault, static_cast<TWHDVersion>(coin.version),
                    TWDerivationPathAccount(derivation.get())));
                if (!xpub || str(xpub).empty()) {
                    ++result.failed_coins;
                    break;
                }
                pub.reset(TWHDWalletGetPublicKeyFromExtended(xpub.get(), type, path_string.get()));
            } else {
                // Nano/Nimiq use hardened indices; NEO/Ontology/Cardano retain
                // the non-hardened address index declared in their registry.
                path.replace(path.find_last_of('/') + 1, std::string::npos,
                             std::to_string(i) + ((ed || nano) ? "'" : ""));
                auto path_string = tw(path);
                Key key(cardano ? cardano_icarus_key(wallet.get(), phrase, path_string.get())
                                : TWHDWalletGetKey(wallet.get(), type, path_string.get()),
                        TWPrivateKeyDelete);
                if (key)
                    pub.reset(TWPrivateKeyGetPublicKey(key.get(), type));
            }
            if (!pub) {
                ++result.failed_coins;
                break;
            }
            Address address(secp ? TWAnyAddressCreateWithPublicKeyDerivation(pub.get(), type,
                                                                             TWDerivationDefault)
                                 : TWAnyAddressCreateWithPublicKey(pub.get(), type),
                            TWAnyAddressDelete);
            String text(address ? TWAnyAddressDescription(address.get()) : nullptr);
            if (!text || str(text).empty()) {
                ++result.failed_coins;
                break;
            }
            if ((nano || nist || cardano || std::string_view(coin.id) == "nimiq") &&
                !TWAnyAddressIsValid(text.get(), type)) {
                ++result.failed_coins;
                break;
            }
            if (!add_account(db, coin, path, text, xpub, static_cast<int>(i), main))
                return false;
            ++result.accounts;
        }
    }
    return result.failed_coins == 0;
}
bool join_words(const WalletJob& job, Credential& mnemonic) {
    std::array<char, 1025> buffer{};
    std::size_t offset = 0;
    if (!valid_word_count(job.word_count))
        return false;
    for (std::size_t i = 0; i < job.word_count; ++i) {
        const auto word = job.words[i].view();
        if (word.empty() || offset + word.size() + 1 >= buffer.size()) {
            wipe(buffer.data(), buffer.size());
            return false;
        }
        if (i)
            buffer[offset++] = ' ';
        std::memcpy(buffer.data() + offset, word.data(), word.size());
        offset += word.size();
    }
    const bool ok = mnemonic.assign({buffer.data(), offset});
    wipe(buffer.data(), buffer.size());
    return ok;
}
} // namespace

NativeWallet::NativeWallet(Path directory) : directory_(std::move(directory)) {
    init_json();
    std::error_code ec;
    std::filesystem::create_directories(directory_, ec);
    if (ec || std::filesystem::is_symlink(directory_))
        return;
    // Resolve platform aliases such as macOS /tmp -> /private/tmp once. Public
    // SQLite reads can then forbid symlinks in every component of the DB path.
    directory_ = std::filesystem::canonical(directory_, ec);
    if (ec)
        return;
    if (chmod(directory_.c_str(), 0700) != 0)
        return;
    const int fd =
        open((directory_ / ".lock").c_str(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0)
        return;
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        close(fd);
        return;
    }
    if (std::filesystem::exists(directory_ / "CURRENT") && current(directory_).empty()) {
        close(fd);
        return;
    }
    lock_fd_ = fd;
}
NativeWallet::~NativeWallet() {
    if (worker_.joinable())
        worker_.join();
    if (lock_fd_ >= 0)
        close(lock_fd_);
}
bool NativeWallet::exists() const {
    return !current(directory_).empty();
}
std::string NativeWallet::language() const {
    return read_file(directory_ / ".LANGUAGE", 8);
}
bool NativeWallet::save_language(std::string_view code) {
    if (!available() || (code != "en" && code != "zh" && code != "pt-BR" && code != "fr" &&
                         code != "it" && code != "de" && code != "es" && code != "ja"))
        return false;
    const auto staging = directory_ / ".LANGUAGE.new";
    // A stale settings staging file contains no wallet data and is never read.
    const int fd =
        open(staging.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0)
        return false;
    const bool ok =
        write(fd, code.data(), code.size()) == static_cast<ssize_t>(code.size()) && fsync(fd) == 0;
    close(fd);
    if (!ok || rename(staging.c_str(), (directory_ / ".LANGUAGE").c_str()) != 0)
        return false;
    return sync_directory(directory_);
}
bool NativeWallet::submit(WalletJob job) {
    {
        std::lock_guard<std::mutex> guard(public_mutex_);
        if (!available() || busy_.exchange(true))
            return false;
        ++public_revision_;
    }
    if (worker_.joinable())
        worker_.join();
    {
        std::lock_guard<std::mutex> guard(mutex_);
        result_.reset();
    }
    worker_ = std::thread([this, job = std::move(job)]() {
        auto result = execute(job);
        {
            std::lock_guard<std::mutex> guard(mutex_);
            result_.emplace(std::move(result));
            busy_ = false;
        }
    });
    return true;
}
std::optional<WalletResult> NativeWallet::poll() {
    std::lock_guard<std::mutex> guard(mutex_);
    auto next = std::move(result_);
    result_.reset();
    return next;
}
PublicWalletError NativeWallet::public_generation(std::string& generation) const {
    generation.clear();
    std::lock_guard<std::mutex> guard(public_mutex_);
    if (!available())
        return PublicWalletError::Unavailable;
    if (busy())
        return PublicWalletError::Busy;
    try {
        const auto name = current(directory_);
        if (name.empty() ||
            !std::filesystem::is_directory(std::filesystem::symlink_status(directory_ / name)) ||
            !std::filesystem::is_regular_file(
                std::filesystem::symlink_status(directory_ / name / "wallet.db")))
            return PublicWalletError::Unavailable;
        generation = name + ":" + std::to_string(public_revision_);
        return PublicWalletError::None;
    } catch (...) {
        return PublicWalletError::Unavailable;
    }
}
PublicWalletError NativeWallet::public_accounts(std::string_view generation,
                                                const PublicWalletQuery& query,
                                                std::vector<PublicAccount>& output) const {
    output.clear();
    std::lock_guard<std::mutex> guard(public_mutex_);
    if (!available())
        return PublicWalletError::Unavailable;
    if (busy())
        return PublicWalletError::Busy;
    if ((query.mode != PublicWalletMode::Standard && query.mode != PublicWalletMode::Hidden) ||
        query.coins.size() > 128)
        return PublicWalletError::InvalidParams;
    for (const auto& name : query.coins)
        if (std::none_of(std::begin(coin_registry), std::end(coin_registry),
                         [&](const auto& coin) { return name == coin.id; }))
            return PublicWalletError::InvalidParams;
    try {
        const auto name = current(directory_);
        if (name.empty() || generation != name + ":" + std::to_string(public_revision_))
            return PublicWalletError::GenerationChanged;
        const auto snapshot = directory_ / name;
        const auto database_path = snapshot / "wallet.db";
        if (!std::filesystem::is_directory(std::filesystem::symlink_status(snapshot)) ||
            !std::filesystem::is_regular_file(std::filesystem::symlink_status(database_path)))
            return PublicWalletError::Unavailable;
        if (query.coins.empty())
            return PublicWalletError::None;
        sqlite3* raw = nullptr;
        if (sqlite3_open_v2(database_path.c_str(), &raw,
                            SQLITE_OPEN_READONLY | SQLITE_OPEN_NOFOLLOW, nullptr) != SQLITE_OK) {
            if (raw)
                sqlite3_close(raw);
            return PublicWalletError::Unavailable;
        }
        Database db(raw, sqlite3_close);
        if (sqlite3_exec(raw, "BEGIN", nullptr, nullptr, nullptr) != SQLITE_OK)
            return PublicWalletError::Unavailable;
        std::string filter = " WHERE is_main = ? AND coin IN (?";
        for (std::size_t i = 1; i < query.coins.size(); ++i)
            filter += ",?";
        filter += ")";
        std::vector<PublicAccount> rows;
        for (const bool extended : {true, false}) {
            std::string sql = extended ? "SELECT "
                                         "coin,symbol,name,address,extended_public_key,path,"
                                         "blockchain,curve,decimals,is_main,NULL FROM wallets_xpub"
                                       : "SELECT "
                                         "coin,symbol,name,address,'',path,blockchain,curve,"
                                         "decimals,is_main,address_index FROM wallets_address";
            sql += filter;
            if (!extended && !query.include_all_addresses)
                sql += " AND address_index = 0";
            sql += extended ? " ORDER BY coin,id" : " ORDER BY coin,address_index,id";
            sqlite3_stmt* stmt = nullptr;
            if (sqlite3_prepare_v2(raw, sql.c_str(), -1, &stmt, nullptr) != SQLITE_OK)
                return PublicWalletError::Unavailable;
            Statement statement(stmt, sqlite3_finalize);
            if (sqlite3_bind_int(stmt, 1, query.mode == PublicWalletMode::Hidden ? 1 : 0) !=
                SQLITE_OK)
                return PublicWalletError::Unavailable;
            for (std::size_t i = 0; i < query.coins.size(); ++i)
                if (sqlite3_bind_text(stmt, static_cast<int>(i + 2), query.coins[i].c_str(),
                                      static_cast<int>(query.coins[i].size()),
                                      SQLITE_TRANSIENT) != SQLITE_OK)
                    return PublicWalletError::Unavailable;
            int status;
            while ((status = sqlite3_step(stmt)) == SQLITE_ROW) {
                if (rows.size() >= 4096)
                    return PublicWalletError::InvalidData;
                PublicAccount row;
                std::array<std::string*, 8> fields{&row.coin,       &row.symbol, &row.name,
                                                   &row.address,    &row.xpub,   &row.path,
                                                   &row.blockchain, &row.curve};
                for (std::size_t i = 0; i < fields.size(); ++i) {
                    if (sqlite3_column_type(stmt, static_cast<int>(i)) != SQLITE_TEXT ||
                        sqlite3_column_bytes(stmt, static_cast<int>(i)) > 1024)
                        return PublicWalletError::InvalidData;
                    const auto* value = sqlite3_column_text(stmt, static_cast<int>(i));
                    if (!value)
                        return PublicWalletError::InvalidData;
                    fields[i]->assign(reinterpret_cast<const char*>(value),
                                      sqlite3_column_bytes(stmt, static_cast<int>(i)));
                    if (!valid_text(*fields[i]))
                        return PublicWalletError::InvalidData;
                }
                if (sqlite3_column_type(stmt, 8) != SQLITE_INTEGER ||
                    sqlite3_column_int64(stmt, 8) < 0 || sqlite3_column_int64(stmt, 8) > 255 ||
                    sqlite3_column_type(stmt, 9) != SQLITE_INTEGER ||
                    sqlite3_column_int64(stmt, 9) !=
                        (query.mode == PublicWalletMode::Hidden ? 1 : 0))
                    return PublicWalletError::InvalidData;
                row.decimals = static_cast<unsigned>(sqlite3_column_int(stmt, 8));
                row.mode = query.mode;
                const auto* coin =
                    std::find_if(std::begin(coin_registry), std::end(coin_registry),
                                 [&](const auto& candidate) { return row.coin == candidate.id; });
                if (coin == std::end(coin_registry) || row.symbol != coin->symbol ||
                    row.name != coin->name || row.blockchain != coin->blockchain ||
                    row.curve != coin->curve ||
                    row.decimals != static_cast<unsigned>(coin->decimals))
                    return PublicWalletError::InvalidData;
                if (extended) {
                    if (row.path != coin->path || row.curve != "secp256k1")
                        return PublicWalletError::InvalidData;
                    // Validate the BIP32 key-data marker BEFORE calling any key
                    // API: a corrupt public column must never export an xprv.
                    auto xpub = tw(row.xpub), path = tw(row.path);
                    auto* decoded = TWBase58DecodeNoCheck(xpub.get());
                    bool public_key = decoded && TWDataSize(decoded) == 82;
                    if (public_key) {
                        const auto* bytes = TWDataBytes(decoded);
                        const auto version = (std::uint32_t(bytes[0]) << 24) |
                                             (std::uint32_t(bytes[1]) << 16) |
                                             (std::uint32_t(bytes[2]) << 8) | bytes[3];
                        public_key = version == coin->version && bytes[4] == 3 &&
                                     bytes[9] == 0x80 && bytes[10] == 0 && bytes[11] == 0 &&
                                     bytes[12] == 0 && (bytes[45] == 2 || bytes[45] == 3);
                    }
                    if (decoded) {
                        wipe(TWDataBytes(decoded), TWDataSize(decoded));
                        TWDataDelete(decoded);
                    }
                    if (!public_key)
                        return PublicWalletError::InvalidData;
                    const auto type = static_cast<TWCoinType>(coin->coin);
                    PublicKey key(TWHDWalletGetPublicKeyFromExtended(xpub.get(), type, path.get()),
                                  TWPublicKeyDelete);
                    Address address(key ? TWAnyAddressCreateWithPublicKeyDerivation(
                                              key.get(), type, TWDerivationDefault)
                                        : nullptr,
                                    TWAnyAddressDelete);
                    String value(address ? TWAnyAddressDescription(address.get()) : nullptr);
                    if (str(value) != row.address)
                        return PublicWalletError::InvalidData;
                } else {
                    if (sqlite3_column_type(stmt, 10) != SQLITE_INTEGER ||
                        sqlite3_column_int64(stmt, 10) < 0 ||
                        sqlite3_column_int64(stmt, 10) >= 0x80000000LL)
                        return PublicWalletError::InvalidData;
                    row.address_index = static_cast<std::uint32_t>(sqlite3_column_int64(stmt, 10));
                    std::string expected = coin->path;
                    const bool hardened = expected.back() == '\'';
                    expected.resize(expected.find_last_of('/') + 1);
                    expected += std::to_string(*row.address_index);
                    if (hardened)
                        expected += '\'';
                    if (row.path != expected)
                        return PublicWalletError::InvalidData;
                }
                rows.push_back(std::move(row));
            }
            if (status != SQLITE_DONE)
                return PublicWalletError::Unavailable;
        }
        if (current(directory_) != name)
            return PublicWalletError::GenerationChanged;
        if (!validate_public_accounts(query, rows))
            return PublicWalletError::InvalidData;
        output = std::move(rows);
        return PublicWalletError::None;
    } catch (...) {
        return PublicWalletError::Unavailable;
    }
}
bool NativeWallet::unlock(std::string_view password, bool hidden, Credential& mnemonic,
                          Credential& phrase, bool& imported) const {
    mnemonic.clear();
    phrase.clear();
    imported = false;
    const auto generation = current(directory_);
    if (generation.empty())
        return false;
    return decrypt_store(directory_ / generation / (hidden ? "keystore.json" : "keystore_24.json"),
                         password, mnemonic, phrase, imported);
}
WalletResult NativeWallet::execute(const WalletJob& job) {
    WalletResult result;
    result.token = job.token;
    result.kind = job.kind;
    try {
        // Match WalletService._reset_environment: a confirmed create/import
        // replaces the active wallet when processing starts, even if recovery
        // subsequently fails validation. Never touch the reference wallet.
        const auto previous = current(directory_);
        if (!previous.empty() && !std::filesystem::is_directory(
                                     std::filesystem::symlink_status(directory_ / previous))) {
            result.error = WalletError::Storage;
            return result;
        }
        std::filesystem::remove(directory_ / "CURRENT");
        if (!previous.empty()) {
            for (const auto* file : {"keystore.json", "keystore_24.json", "wallet.db"})
                std::filesystem::remove(directory_ / previous / file);
            std::filesystem::remove(directory_ / previous);
        }
        if (!sync_directory(directory_)) {
            result.error = WalletError::Storage;
            return result;
        }
        Credential mnemonic;
        if (job.kind == WalletWork::Generate) {
            std::array<std::uint8_t, 32> entropy{};
            if (!random_bytes(entropy.data(), entropy.size())) {
                result.error = WalletError::Random;
                return result;
            }
            // Original construction: SHA256(OS entropy || SHA256(phrase-password-monotonic_ns)).
            const auto ticks = std::chrono::duration_cast<std::chrono::nanoseconds>(
                                   std::chrono::steady_clock::now().time_since_epoch())
                                   .count();
            const auto clock_text = std::to_string(ticks);
            std::array<std::uint8_t, 64> combined{};
            std::copy(entropy.begin(), entropy.end(), combined.begin());
            unsigned size = 0;
            auto* digest = EVP_MD_CTX_new();
            bool hashed = digest && EVP_DigestInit_ex(digest, EVP_sha256(), nullptr) == 1 &&
                          EVP_DigestUpdate(digest, job.passphrase.view().data(),
                                           job.passphrase.view().size()) == 1 &&
                          EVP_DigestUpdate(digest, "-", 1) == 1 &&
                          EVP_DigestUpdate(digest, job.password.view().data(),
                                           job.password.view().size()) == 1 &&
                          EVP_DigestUpdate(digest, "-", 1) == 1 &&
                          EVP_DigestUpdate(digest, clock_text.data(), clock_text.size()) == 1 &&
                          EVP_DigestFinal_ex(digest, combined.data() + 32, &size) == 1 &&
                          size == 32;
            EVP_MD_CTX_free(digest);
            hashed = hashed &&
                     EVP_Digest(combined.data(), combined.size(), entropy.data(), &size,
                                EVP_sha256(), nullptr) == 1 &&
                     size == 32;
            wipe(combined.data(), combined.size());
            if (!hashed) {
                wipe(entropy.data(), entropy.size());
                result.error = WalletError::Crypto;
                return result;
            }
            auto* data = TWDataCreateWithBytes(entropy.data(), entropy.size());
            auto pass = tw(job.passphrase.view());
            Wallet wallet(data ? TWHDWalletCreateWithEntropy(data, pass.get()) : nullptr,
                          TWHDWalletDelete);
            wipe(entropy.data(), entropy.size());
            if (data) {
                wipe(TWDataBytes(data), TWDataSize(data));
                TWDataDelete(data);
            }
            if (!wallet) {
                result.error = WalletError::Crypto;
                return result;
            }
            String generated(TWHDWalletMnemonic(wallet.get()));
            if (!mnemonic.assign(str(generated))) {
                result.error = WalletError::Crypto;
                return result;
            }
            auto remaining = mnemonic.view();
            for (std::size_t i = 0; i < result.words.size(); ++i) {
                const auto end = remaining.find(' ');
                const auto word = remaining.substr(0, end);
                if (!is_mnemonic_word(word) || !result.words[i].assign(word) ||
                    (i < 23 && end == std::string_view::npos)) {
                    result.error = WalletError::Crypto;
                    return result;
                }
                remaining =
                    end == std::string_view::npos ? std::string_view{} : remaining.substr(end + 1);
            }
            if (!remaining.empty()) {
                result.error = WalletError::Crypto;
                return result;
            }
        } else {
            if (!join_words(job, mnemonic)) {
                result.error = WalletError::InvalidMnemonic;
                return result;
            }
            auto check = tw(mnemonic.view());
            if (!TWMnemonicIsValid(check.get())) {
                result.error = WalletError::InvalidMnemonic;
                return result;
            }
        }
        if (!valid_text(job.password.view()) || !valid_text(job.passphrase.view())) {
            result.error = WalletError::Crypto;
            return result;
        }
        const bool imported = job.kind == WalletWork::Import;
        std::array<unsigned char, 16> id{};
        if (!random_bytes(id.data(), id.size())) {
            result.error = WalletError::Random;
            return result;
        }
        const char* digits = "0123456789abcdef";
        std::string name;
        for (auto b : id) {
            name += digits[b >> 4];
            name += digits[b & 15];
        }
        const auto snapshot = directory_ / name;
        if (!std::filesystem::create_directory(snapshot) || chmod(snapshot.c_str(), 0700) != 0) {
            result.error = WalletError::Storage;
            return result;
        }
        if (!encrypt_store(snapshot / "keystore.json", mnemonic.view(), job.passphrase.view(),
                           job.password.view(), imported) ||
            !encrypt_store(snapshot / "keystore_24.json", mnemonic.view(), "", job.password.view(),
                           imported)) {
            result.error = WalletError::Storage;
            return result;
        }
        // Read both files back and authenticate before replacing any active data.
        for (const bool hidden : {true, false}) {
            Credential words, phrase;
            bool flag = false;
            if (!decrypt_store(snapshot / (hidden ? "keystore.json" : "keystore_24.json"),
                               job.password.view(), words, phrase, flag) ||
                !words.equals(mnemonic.view()) ||
                !phrase.equals(hidden ? job.passphrase.view() : "") || flag != imported) {
                result.error = WalletError::Storage;
                return result;
            }
        }
        sqlite3* raw = nullptr;
        if (sqlite3_open_v2((snapshot / "wallet.db").c_str(), &raw,
                            SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nullptr) != SQLITE_OK) {
            if (raw)
                sqlite3_close(raw);
            result.error = WalletError::Storage;
            return result;
        }
        Database db(raw, sqlite3_close);
        if (chmod((snapshot / "wallet.db").c_str(), 0600) != 0 ||
            sqlite3_exec(raw, schema, nullptr, nullptr, nullptr) != SQLITE_OK ||
            !accounts(raw, mnemonic.view(), job.passphrase.view(), true, result) ||
            !accounts(raw, mnemonic.view(), "", false, result) || result.accounts == 0 ||
            sqlite3_exec(raw, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) {
            result.error = WalletError::Accounts;
            return result;
        }
        db.reset();
        if (!sync_directory(snapshot)) {
            result.error = WalletError::Storage;
            return result;
        }
        const auto pointer = directory_ / ("CURRENT." + name);
        if (!write_file(pointer, name)) {
            result.error = WalletError::Storage;
            return result;
        }
        std::filesystem::rename(pointer, directory_ / "CURRENT");
        if (!sync_directory(directory_))
            result.error = WalletError::Storage;
        // Both modes are ready before the UI displays the original backup intro.
    } catch (...) {
        result.error = WalletError::Storage;
    }
    return result;
}
} // namespace airlock
