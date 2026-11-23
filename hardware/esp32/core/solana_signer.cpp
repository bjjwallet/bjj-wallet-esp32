// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/solana_signer.hpp"
#include "core/solana_signing_catalog.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
#include <TrezorCrypto/base58.h>
extern "C" {
#include <TrezorCrypto/ed25519.h>
}
#include <algorithm>
#include <charconv>
#include <memory>
#include <vector>

namespace airlock {
namespace {
using Key = std::array<std::uint8_t, 32>;
struct Payment {
    SolanaReview review;
    Key sender{}, recipient{}, blockhash{};
    std::uint64_t amount = 0;
    const SolanaSigningAsset* token = nullptr;
    Key mint{}, program{}, source_account{}, destination_account{}, associated_program{},
        memo_program{};
    std::uint64_t rent = 0;
};

std::string field(const cJSON* obj, const char* key) {
    const auto* value = cJSON_GetObjectItemCaseSensitive(obj, key);
    return cJSON_IsString(value) ? value->valuestring : "";
}

bool number(std::string_view text, std::uint64_t& value) {
    if (text.empty() || (text.size() > 1 && text.front() == '0'))
        return false;
    const auto* end = text.data() + text.size();
    const auto parsed = std::from_chars(text.data(), end, value, 10);
    return parsed.ec == std::errc{} && parsed.ptr == end;
}

bool public_key(const std::string& input, Key& key) {
    if (input.size() < 32 || input.size() > 44)
        return false;
    auto size = key.size();
    if (!b58tobin(key.data(), &size, input.c_str()) || size != key.size())
        return false;
    if (std::all_of(key.begin(), key.end(), [](auto byte) { return byte == 0; }))
        return false;
    std::array<char, 45> canonical{};
    size = canonical.size();
    return b58enc(canonical.data(), &size, key.data(), key.size()) && input == canonical.data();
}

bool associated_account(const Key& owner, const Key& mint, const Key& token_program,
                        const Key& ata_program, Key& output) {
    // The same SHA-256 PDA derivation as the Associated Token Program. Never trust ATA addresses
    // supplied by the app.
    for (int bump = 255; bump >= 0; --bump) {
        std::vector<std::uint8_t> bytes;
        for (const auto& key : {owner, token_program, mint})
            bytes.insert(bytes.end(), key.begin(), key.end());
        bytes.push_back(static_cast<std::uint8_t>(bump));
        bytes.insert(bytes.end(), ata_program.begin(), ata_program.end());
        constexpr std::string_view marker = "ProgramDerivedAddress";
        bytes.insert(bytes.end(), marker.begin(), marker.end());
        if (!crypto::sha256({bytes.data(), bytes.size()}, output))
            return false;
        // CoSi's two-point path decompresses both points. Its one-point shortcut does not validate
        // a point.
        ed25519_public_key points[2]{}, sum{};
        std::copy(output.begin(), output.end(), points[0]);
        std::fill(std::begin(points[1]), std::end(points[1]), 0x66);
        points[1][0] = 0x58; // Canonical Ed25519 base point.
        if (ed25519_cosi_combine_publickeys(sum, points, 2) != 0)
            return true;
    }
    return false;
}

bool parse(std::string_view payload, Payment& payment) {
    if (payload.size() > 1024)
        return false;
    const std::unique_ptr<cJSON, decltype(&cJSON_Delete)> object(parse_json(payload), cJSON_Delete);
    if (!cJSON_IsObject(object.get()) || field(object.get(), "network") != "solana-mainnet")
        return false;
    for (const auto* item = object->child; item; item = item->next)
        for (const auto* other = item->next; other; other = other->next)
            if (!item->string || !other->string || std::string_view(item->string) == other->string)
                return false;
    auto& r = payment.review;
    const auto* asset = cJSON_GetObjectItemCaseSensitive(object.get(), "asset");
    if (asset) {
        payment.token = solana_signing_asset(field(object.get(), "asset"));
        if (!payment.token || cJSON_GetArraySize(object.get()) != 11 ||
            !number(field(object.get(), "rent_atomic"), payment.rent) ||
            (payment.rent != 0 &&
             payment.rent != (payment.token->extensions ? 2192400U : 2039280U)))
            return false;
        r.symbol = payment.token->symbol;
        r.decimals = payment.token->decimals;
        r.fee_amount = std::to_string(5000 + payment.rent);
        if (!public_key(std::string(payment.token->mint), payment.mint) ||
            !public_key(std::string(payment.token->program), payment.program) ||
            !public_key("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", payment.associated_program))
            return false;
        if (payment.token->extensions &&
            !public_key("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", payment.memo_program))
            return false;
    } else if (cJSON_GetArraySize(object.get()) != 9)
        return false;
    if (field(object.get(), "fee_atomic") != r.fee_amount)
        return false;
    r.wallet_id = field(object.get(), "wallet_id");
    r.mode = field(object.get(), "mode");
    r.sender = field(object.get(), "sender");
    r.recipient = field(object.get(), "recipient");
    r.path = field(object.get(), "path");
    r.amount = field(object.get(), "amount_atomic");
    const auto path = std::string_view(r.path);
    constexpr std::string_view prefix = "m/44'/501'/";
    std::uint64_t index = 0;
    const bool valid =
        r.wallet_id.size() == 64 &&
        r.wallet_id.find_first_not_of("0123456789abcdef") == std::string::npos &&
        (r.mode == "STANDARD" || r.mode == "HIDDEN") && path.size() > prefix.size() + 1 &&
        path.substr(0, prefix.size()) == prefix && path.back() == '\'' &&
        number(path.substr(prefix.size(), path.size() - prefix.size() - 1), index) &&
        index < 0x80000000ULL && number(r.amount, payment.amount) && payment.amount != 0 &&
        public_key(r.sender, payment.sender) && public_key(r.recipient, payment.recipient) &&
        payment.sender != payment.recipient &&
        public_key(field(object.get(), "blockhash"), payment.blockhash);
    return valid && (!payment.token ||
                     (associated_account(payment.sender, payment.mint, payment.program,
                                         payment.associated_program, payment.source_account) &&
                      associated_account(payment.recipient, payment.mint, payment.program,
                                         payment.associated_program, payment.destination_account)));
}

std::vector<std::uint8_t> message(const Payment& p) {
    if (p.token) {
        // One signer; sender, source ATA and recipient ATA writable. Every mint/program identity is
        // pinned in firmware.
        std::vector<std::uint8_t> out{1, 0, static_cast<std::uint8_t>(p.token->extensions ? 6 : 5),
                                      static_cast<std::uint8_t>(p.token->extensions ? 9 : 8)};
        const Key system{};
        for (const auto& key : {p.sender, p.source_account, p.destination_account, p.recipient,
                                p.mint, system, p.program, p.associated_program})
            out.insert(out.end(), key.begin(), key.end());
        if (p.token->extensions)
            out.insert(out.end(), p.memo_program.begin(), p.memo_program.end());
        out.insert(out.end(), p.blockhash.begin(), p.blockhash.end());
        out.push_back((p.rent ? 2 : 1) + (p.token->extensions ? 1 : 0));
        if (p.rent) {
            // CreateIdempotent: payer, recipient ATA, recipient wallet, mint, System, Token
            // Program.
            const std::uint8_t create[] = {7, 6, 0, 2, 3, 4, 5, 6, 1, 1};
            out.insert(out.end(), std::begin(create), std::end(create));
        }
        if (p.token->extensions) {
            // Empty UTF-8 Memo v3 also supports recipient accounts requiring an incoming memo.
            const std::uint8_t memo[] = {8, 0, 0};
            out.insert(out.end(), std::begin(memo), std::end(memo));
        }
        const std::uint8_t transfer[] = {6, 4, 1, 4, 2, 0};
        out.insert(out.end(), std::begin(transfer), std::end(transfer));
        out.push_back(p.token->extensions ? 19 : 10);
        if (p.token->extensions) {
            out.push_back(26);
            out.push_back(1);
        } // TransferCheckedWithFee, expected fee zero.
        else
            out.push_back(12); // TransferChecked.
        for (unsigned shift = 0; shift < 64; shift += 8)
            out.push_back(p.amount >> shift);
        out.push_back(static_cast<std::uint8_t>(p.token->decimals));
        if (p.token->extensions)
            out.insert(out.end(), 8, 0);
        return out;
    }
    // A single signer, two writable accounts, and the readonly System Program.
    std::vector<std::uint8_t> out{1, 0, 1, 3};
    out.reserve(150);
    out.insert(out.end(), p.sender.begin(), p.sender.end());
    out.insert(out.end(), p.recipient.begin(), p.recipient.end());
    out.insert(out.end(), 32, 0);
    out.insert(out.end(), p.blockhash.begin(), p.blockhash.end());
    // Exactly one SystemInstruction::Transfer. No additional accounts or instructions.
    const std::uint8_t instruction[] = {1, 2, 2, 0, 1, 12, 2, 0, 0, 0};
    out.insert(out.end(), std::begin(instruction), std::end(instruction));
    for (unsigned shift = 0; shift < 64; shift += 8)
        out.push_back(p.amount >> shift);
    return out;
}
} // namespace

bool review_solana(std::string_view payload, SolanaReview& review) {
    Payment p;
    if (!parse(payload, p))
        return false;
    review = std::move(p.review);
    return true;
}

bool sign_solana(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
                 std::string& encoded) {
    encoded.clear();
    Payment p;
    if (!parse(payload, p))
        return false;
    crypto::DerivedKey key;
    if (crypto::derive_key(seed, crypto::Curve::Ed25519, p.review.path, random, key) !=
            crypto::HdError::None ||
        key.public_key_size != p.sender.size() ||
        !std::equal(p.sender.begin(), p.sender.end(), key.public_key.begin()))
        return false;
    const auto bytes = message(p);
    std::array<std::uint8_t, 64> signature{};
    ed25519_sign(bytes.data(), bytes.size(), key.private_key.value.data(), signature.data());
    constexpr std::string_view digits = "0123456789abcdef";
    encoded.reserve(2 * signature.size());
    for (auto byte : signature) {
        encoded += digits[byte >> 4];
        encoded += digits[byte & 15];
    }
    return true;
}
} // namespace airlock
