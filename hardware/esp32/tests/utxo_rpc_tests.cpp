// SPDX-License-Identifier: GPL-3.0-or-later
// Transport-only fixture. Cryptographic signatures are tested by utxo_signer_tests.cpp.
#include "core/public_wallet_rpc.hpp"
#include "core/config.hpp"
#include <cstdlib>
#include <iostream>
using namespace airlock;
void check(bool value) {
    if (!value) {
        std::cerr << "Signing RPC check failed\n";
        std::exit(1);
    }
}
struct Wallet : PublicWalletService {
    std::uint64_t epoch = 1;
    bool utxo_signing_supported() const override {
        return true;
    }
    std::uint64_t public_authorization_epoch() const override {
        return epoch;
    }
    PublicWalletError public_generation(std::string& g) const override {
        g = "fixture";
        return PublicWalletError::None;
    }
    PublicWalletError public_accounts(std::string_view, const PublicWalletQuery&,
                                      std::vector<PublicAccount>&) const override {
        return PublicWalletError::None;
    }
};
struct Transport : PublicWalletTransport {
    Wallet& wallet;
    PublicPeer peer{7, true, true, true, 247};
    std::string reply;
    unsigned requests = 0;
    bool change = false, reject = false;
    explicit Transport(Wallet& w) : wallet(w) {}
    PublicPeer public_peer() const override {
        return peer;
    }
    bool notify_public(std::string_view chunk) override {
        reply += chunk;
        return true;
    }
    std::string request_signature(std::string_view payload, std::uint64_t connection,
                                  std::string& error) override {
        check(connection == 7);
        check(payload.find("fixture") != std::string::npos);
        ++requests;
        if (change)
            ++wallet.epoch;
        if (reject) {
            error = "TRANSACTION_REJECTED";
            return {};
        }
        return "010203";
    }
};
int main() {
    Wallet wallet;
    Transport transport(wallet);
    PublicWalletRpc rpc(wallet, {}, transport);
    check(rpc.receive("{\"id\":1,\"method\":\"get_device_info\",\"params\":{}}###EOF###", 100) ==
          ReceiveStatus::Ok);
    check(transport.reply.find("\"utxo_signing_version\":1") != std::string::npos);
    transport.reply.clear();
    const std::string request =
        "{\"id\":2,\"method\":\"sign_tx\",\"params\":{\"password\":\"fixture\"}}###EOF###";
    check(rpc.receive(request, 200) == ReceiveStatus::Ok);
    check(transport.requests == 1);
    check(transport.reply.find("\"data\":\"010203\"") != std::string::npos);
    check(transport.reply.find("fixture") == std::string::npos);
    transport.reply.clear();
    transport.reject = true;
    check(rpc.receive(request, 300) == ReceiveStatus::Ok);
    check(transport.reply.find("TRANSACTION_REJECTED") != std::string::npos);
    transport.reply.clear();
    transport.reject = false;
    transport.change = true;
    check(rpc.receive(request, 400) == ReceiveStatus::Disconnected);
    check(transport.reply.empty());
    Transport insecure(wallet);
    insecure.peer.encrypted = false;
    PublicWalletRpc denied(wallet, {}, insecure);
    check(denied.receive(request, 500) == ReceiveStatus::Disconnected);
    check(insecure.requests == 0);
    std::cout
        << "Signing RPC capability, rejection, epoch change and encrypted-link checks passed\n";
}
