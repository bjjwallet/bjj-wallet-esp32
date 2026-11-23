// SPDX-License-Identifier: GPL-3.0-or-later
#include "simulator/native_wallet.hpp"
#include <openssl/evp.h>
#include <sqlite3.h>
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <thread>
#include <vector>
#include <unistd.h>

using namespace airlock;
namespace {
int checks = 0;
#define CHECK(x)                                                                                   \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(x)) {                                                                                \
            std::cerr << "Native wallet check failed at line " << __LINE__ << ": " #x "\n";        \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
constexpr auto phrase = "Public-Passphrase1!";
constexpr auto password = "Public-Password1!";
constexpr auto mnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
WalletJob imported(std::uint64_t token) {
    WalletJob job;
    job.kind = WalletWork::Import;
    job.token = token;
    job.word_count = 12;
    job.passphrase.assign(phrase);
    job.password.assign(password);
    for (std::size_t i = 0; i < 11; ++i)
        job.words[i].assign("abandon");
    job.words[11].assign("about");
    return job;
}
WalletResult wait(NativeWallet& service) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    for (;;) {
        if (auto result = service.poll())
            return std::move(*result);
        CHECK(std::chrono::steady_clock::now() < until);
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
}
std::string read(const std::filesystem::path& path) {
    std::ifstream stream(path);
    return {std::istreambuf_iterator<char>(stream), {}};
}
void verify_database(const std::filesystem::path& path) {
    sqlite3* db = nullptr;
    CHECK(sqlite3_open_v2(path.c_str(), &db, SQLITE_OPEN_READONLY, nullptr) == SQLITE_OK);
    sqlite3_stmt* statement = nullptr;
    const char* query = "SELECT "
                        "coin,symbol,name,address,extended_public_key,path,decimals,blockchain,"
                        "curve,is_main,-1 FROM wallets_xpub "
                        "UNION ALL SELECT "
                        "coin,symbol,name,address,'',path,decimals,blockchain,curve,is_main,"
                        "address_index FROM wallets_address";
    CHECK(sqlite3_prepare_v2(db, query, -1, &statement, nullptr) == SQLITE_OK);
    std::vector<std::string> rows;
    std::size_t added_rows = 0;
    int status = 0;
    while ((status = sqlite3_step(statement)) == SQLITE_ROW) {
        const std::string_view coin(
            reinterpret_cast<const char*>(sqlite3_column_text(statement, 0)));
        if (coin == "nano" || coin == "nimiq" || coin == "neo" || coin == "ontology" ||
            coin == "cardano") {
            ++added_rows;
            continue; // Independently verified by coin_support_matrix.
        }
        std::string row;
        for (int i = 0; i < 11; ++i) {
            if (i)
                row += '\t';
            const auto* value = sqlite3_column_text(statement, i);
            CHECK(value != nullptr);
            row += reinterpret_cast<const char*>(value);
        }
        rows.push_back(row + '\n');
    }
    CHECK(status == SQLITE_DONE);
    CHECK(rows.size() == 2692);
    CHECK(added_rows == 550);
    sqlite3_finalize(statement);
    sqlite3_close(db);
    std::sort(rows.begin(), rows.end());
    std::string canonical;
    for (const auto& row : rows)
        canonical += row;
    std::array<unsigned char, 32> hash{};
    unsigned int size = 0;
    CHECK(EVP_Digest(canonical.data(), canonical.size(), hash.data(), &size, EVP_sha256(),
                     nullptr) == 1 &&
          size == 32);
    const char* hex = "0123456789abcdef";
    std::string actual;
    for (auto byte : hash) {
        actual += hex[byte >> 4];
        actual += hex[byte & 15];
    }
    // All records/fields of BOTH modes, generated with the unchanged original
    // js/Utils.js and official WASM 4.4.2. Not a hash of this implementation.
    CHECK(actual == "3bb34b3649ed33c5565d7ec25de8faf19b370989b90281c2953770620f9c4142");
}
} // namespace
int main(int argc, char** argv) {
    if (argc == 3 && std::string_view(argv[1]) == "--verify-original") {
        NativeWallet service(argv[2]);
        Credential words, pass;
        bool flag = false;
        CHECK(service.available() && service.exists());
        CHECK(service.unlock(password, true, words, pass, flag));
        CHECK(words.equals(mnemonic) && pass.equals(phrase) && flag);
        CHECK(service.unlock(password, false, words, pass, flag));
        CHECK(words.equals(mnemonic) && pass.view().empty() && flag);
        std::cout << "Original Python keystores opened by native C++.\n";
        return 0;
    }
    char temporary[] = "/tmp/bjj-airlock-native-test-XXXXXX";
    CHECK(mkdtemp(temporary) != nullptr);
    const auto directory = std::filesystem::path(temporary) / "wallet";
    std::string generation;
    {
        NativeWallet service(directory);
        CHECK(service.available() && !service.exists());
        NativeWallet competing(directory);
        CHECK(!competing.available());
        auto invalid = imported(1);
        invalid.words[11].assign("abandon");
        CHECK(service.submit(std::move(invalid)));
        CHECK(wait(service).error == WalletError::InvalidMnemonic);
        CHECK(!service.exists());
        WalletJob generate;
        generate.kind = WalletWork::Generate;
        generate.token = 2;
        generate.passphrase.assign(phrase);
        generate.password.assign(password);
        CHECK(service.submit(std::move(generate)));
        auto generated = wait(service);
        CHECK(generated.error == WalletError::None && generated.token == 2);
        CHECK(generated.accounts == 3242 && generated.failed_coins == 0);
        for (const auto& word : generated.words)
            CHECK(is_mnemonic_word(word.view()));
        CHECK(service.exists()); // Original generation persists before showing backup.
        Credential generated_words, generated_phrase;
        bool generated_imported = true;
        CHECK(
            service.unlock(password, true, generated_words, generated_phrase, generated_imported));
        CHECK(!generated_imported && generated_phrase.equals(phrase));
        auto remaining = generated_words.view();
        for (const auto& word : generated.words) {
            const auto space = remaining.find(' ');
            CHECK(word.view() == remaining.substr(0, space));
            remaining =
                space == std::string_view::npos ? std::string_view{} : remaining.substr(space + 1);
        }
        CHECK(remaining.empty());
        CHECK(service.submit(imported(3)));
        auto result = wait(service);
        CHECK(result.error == WalletError::None && result.accounts == 3242 &&
              result.failed_coins == 0);
        CHECK(service.exists());
        Credential words, pass;
        bool flag = false;
        CHECK(!service.unlock("Wrong-password1!", true, words, pass, flag));
        CHECK(words.view().empty() && pass.view().empty());
        CHECK(service.unlock(password, true, words, pass, flag));
        CHECK(words.equals(mnemonic) && pass.equals(phrase) && flag);
        CHECK(service.unlock(password, false, words, pass, flag));
        CHECK(words.equals(mnemonic) && pass.view().empty() && flag);
        generation = read(directory / "CURRENT");
        CHECK(generation.size() == 32);
        verify_database(directory / generation / "wallet.db");
        const auto ciphertext = read(directory / generation / "keystore.json");
        CHECK(ciphertext.find(mnemonic) == std::string::npos &&
              ciphertext.find(phrase) == std::string::npos);
        auto tampered = ciphertext;
        const auto start = tampered.find("\"ciphertext\":\"");
        CHECK(start != std::string::npos);
        const auto payload = start + std::strlen("\"ciphertext\":\"");
        CHECK(payload < tampered.size());
        tampered[payload] = tampered[payload] == 'A' ? 'B' : 'A';
        {
            std::ofstream file(directory / generation / "keystore.json");
            file << tampered;
        }
        CHECK(!service.unlock(password, true, words, pass, flag) && words.view().empty() &&
              pass.view().empty());
        {
            std::ofstream file(directory / generation / "keystore.json");
            file << ciphertext;
        }
        // Original recovery clears the active wallet before mnemonic validation.
        auto bad = imported(4);
        bad.words[0].assign("ability");
        CHECK(service.submit(std::move(bad)));
        CHECK(wait(service).error == WalletError::InvalidMnemonic);
        CHECK(!service.exists());
        CHECK(!std::filesystem::exists(directory / generation / "keystore.json"));
        CHECK(!service.unlock(password, true, words, pass, flag));
        CHECK(service.submit(imported(5)));
        CHECK(wait(service).error == WalletError::None);
        generation = read(directory / "CURRENT");
        // Reset must never follow a replaced snapshot directory outside its
        // intended target. Both directories here contain public test data only.
        const auto parked = std::filesystem::path(temporary) / "parked-fixture";
        std::filesystem::rename(directory / generation, parked);
        std::filesystem::create_directory_symlink(parked, directory / generation);
        CHECK(service.submit(imported(6)));
        CHECK(wait(service).error == WalletError::Storage);
        CHECK(std::filesystem::exists(parked / "keystore.json") &&
              read(directory / "CURRENT") == generation);
        std::filesystem::remove(directory / generation);
        std::filesystem::rename(parked, directory / generation);
    }
    {
        NativeWallet reopened(directory);
        Credential words, pass;
        bool flag = false;
        CHECK(reopened.available() && reopened.exists());
        CHECK(reopened.unlock(password, true, words, pass, flag) && words.equals(mnemonic));
    }
    // Additional BIP39 lengths offered by the original word-count selector.
    const std::array<const char*, 4> last_words = {"address", "agent", "admit", "art"};
    for (std::size_t i = 0; i < last_words.size(); ++i) {
        NativeWallet service(std::filesystem::path(temporary) / ("count-" + std::to_string(i)));
        auto job = imported(10 + i);
        job.word_count = 15 + i * 3;
        for (std::size_t j = 0; j + 1 < job.word_count; ++j)
            job.words[j].assign("abandon");
        job.words[job.word_count - 1].assign(last_words[i]);
        CHECK(service.submit(std::move(job)));
        CHECK(wait(service).error == WalletError::None);
        Credential words, pass;
        bool flag = false;
        CHECK(service.unlock(password, true, words, pass, flag) && flag);
        CHECK(static_cast<std::size_t>(std::count(words.view().begin(), words.view().end(), ' ')) +
                  1 ==
              15 + i * 3);
    }
    // Optional test harness exports only this publicly documented fixture to
    // verify Python-original AESGCM compatibility, never a user's wallet.
    if (argc == 2) {
        std::filesystem::create_directories(argv[1]);
        for (const auto* file : {"keystore.json", "keystore_24.json"})
            std::filesystem::copy_file(directory / generation / file,
                                       std::filesystem::path(argv[1]) / file,
                                       std::filesystem::copy_options::overwrite_existing);
    }
    std::filesystem::remove_all(temporary);
    std::cout << checks
              << " native checks passed; 2692 original account records unchanged, 550 new records "
                 "generated.\n";
}
