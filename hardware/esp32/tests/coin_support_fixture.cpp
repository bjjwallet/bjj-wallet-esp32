// SPDX-License-Identifier: GPL-3.0-or-later
// DEVELOPMENT ONLY: create six public fixtures in an empty temporary directory.
#include "simulator/native_wallet.hpp"
#include <chrono>
#include <fstream>
#include <iostream>
#include <thread>

using namespace airlock;
int main(int argc, char** argv) {
    if (argc != 2)
        return 2;
    const auto root = std::filesystem::path(argv[1]);
    // Refuse an existing/nonempty target: audit never opens a user's wallet.
    if (!std::filesystem::is_directory(root) || !std::filesystem::is_empty(root))
        return 3;
    for (const auto count : {12U, 18U, 24U})
        for (const bool with_phrase : {false, true}) {
            const auto id = std::to_string(count) + (with_phrase ? "-with" : "-without");
            NativeWallet wallet(root / id);
            if (!wallet.available())
                return 4;
            WalletJob job;
            job.kind = WalletWork::Import;
            job.word_count = count;
            job.token = count;
            const auto* phrase = with_phrase ? "Public-Passphrase1!" : "";
            job.passphrase.assign(phrase);
            job.password.assign("Public-Password1!");
            std::string mnemonic;
            for (unsigned i = 0; i < count; ++i) {
                const auto* word = i + 1 < count ? "abandon"
                                   : count == 12 ? "about"
                                   : count == 18 ? "agent"
                                                 : "art";
                job.words[i].assign(word);
                if (i)
                    mnemonic += ' ';
                mnemonic += word;
            }
            if (!wallet.submit(std::move(job)))
                return 5;
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(120);
            std::optional<WalletResult> result;
            while (!(result = wallet.poll())) {
                if (std::chrono::steady_clock::now() >= deadline)
                    return 6;
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            if (result->error != WalletError::None || result->failed_coins || !wallet.exists())
                return 7;
            for (const bool main : {true, false}) {
                Credential words, pass;
                bool imported = false;
                if (!wallet.unlock("Public-Password1!", main, words, pass, imported) || !imported ||
                    !words.equals(mnemonic) || !pass.equals(main ? phrase : ""))
                    return 8;
            }
            std::cout << id << ": " << result->accounts
                      << " account rows; both keystores verified\n";
        }
}
