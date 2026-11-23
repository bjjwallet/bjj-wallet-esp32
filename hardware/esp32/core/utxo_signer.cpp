// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/utxo_signer.hpp"
#include "core/json_memory.hpp"
#include "core/trezor_random_scope.hpp"
#include "cJSON.h"
extern "C" {
#include <TrezorCrypto/base58.h>
#include <TrezorCrypto/ecdsa.h>
#include <TrezorCrypto/secp256k1.h>
}
#include <algorithm>
#include <cmath>
#include <memory>
#include <set>

namespace airlock {
namespace {
using Bytes = std::vector<std::uint8_t>;
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
constexpr std::uint64_t limit = 9000000000000000ULL;
std::string text(const cJSON* o, const char* key) {
    const auto* v = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsString(v) && v->valuestring ? v->valuestring : "";
}
bool unique(const cJSON* o) {
    if (cJSON_IsObject(o)) {
        std::set<std::string> keys;
        for (auto* p = o->child; p; p = p->next)
            if (!p->string || !keys.insert(p->string).second || !unique(p))
                return false;
    } else if (cJSON_IsArray(o))
        for (auto* p = o->child; p; p = p->next)
            if (!unique(p))
                return false;
    return true;
}
bool number(std::string_view s, std::uint64_t& n) {
    n = 0;
    if (s.empty() || s.size() > 16 || (s.size() > 1 && s[0] == '0'))
        return false;
    for (auto c : s) {
        if (c < '0' || c > '9' || n > (limit - (c - '0')) / 10)
            return false;
        n = n * 10 + c - '0';
    }
    return true;
}
bool hex(std::string_view s, Bytes& out) {
    out.clear();
    if (s.empty() || s.size() % 2 || s.size() > 200000)
        return false;
    auto digit = [](char c) {
        return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
    };
    for (std::size_t i = 0; i < s.size(); i += 2) {
        int a = digit(s[i]), b = digit(s[i + 1]);
        if (a < 0 || b < 0)
            return false;
        out.push_back((a << 4) | b);
    }
    return true;
}
std::string hex(const Bytes& b) {
    constexpr char alphabet[] = "0123456789abcdef";
    std::string s;
    for (auto v : b) {
        s += alphabet[v >> 4];
        s += alphabet[v & 15];
    }
    return s;
}
void le(Bytes& b, std::uint64_t n, unsigned size) {
    for (unsigned i = 0; i < size; ++i) {
        b.push_back(n & 255);
        n >>= 8;
    }
}
void var(Bytes& b, std::uint64_t n) {
    if (n < 253)
        b.push_back(n);
    else if (n <= 65535) {
        b.push_back(253);
        le(b, n, 2);
    } else {
        b.push_back(254);
        le(b, n, 4);
    }
}
void append(Bytes& out, const Bytes& in) {
    out.insert(out.end(), in.begin(), in.end());
}
void script(Bytes& out, const Bytes& s) {
    var(out, s.size());
    append(out, s);
}
Bytes hash(const Bytes& b) {
    std::array<std::uint8_t, 32> a{}, d{};
    if (!crypto::sha256({b.data(), b.size()}, a) || !crypto::sha256({a.data(), a.size()}, d))
        return {};
    return {d.begin(), d.end()};
}
struct Reader {
    const Bytes& b;
    std::size_t pos = 0;
    bool ok = true;
    std::uint64_t read(unsigned n) {
        if (n > 8 || pos + n > b.size()) {
            ok = false;
            return 0;
        }
        std::uint64_t v = 0;
        for (unsigned i = 0; i < n; ++i)
            v |= std::uint64_t(b[pos++]) << (8 * i);
        return v;
    }
    std::uint64_t compact() {
        auto v = read(1);
        if (v < 253)
            return v;
        auto n = read(v == 253 ? 2 : v == 254 ? 4 : 8);
        if ((v == 253 && n < 253) || (v == 254 && n <= 65535) || (v == 255 && n <= 0xffffffff))
            ok = false;
        return n;
    }
    Bytes take(std::uint64_t n) {
        if (n > b.size() - pos) {
            ok = false;
            return {};
        }
        Bytes v(b.begin() + pos, b.begin() + pos + n);
        pos += n;
        return v;
    }
    Bytes script() {
        auto n = compact();
        if (n > 100000) {
            ok = false;
            return {};
        }
        return take(n);
    }
};
bool funding(const Bytes& raw, std::uint64_t wanted, const Bytes& expected, std::uint64_t amount,
             Bytes& txhash, bool reject_tokens = false) {
    Reader r{raw};
    Bytes stripped;
    auto version = r.read(4);
    le(stripped, version, 4);
    bool witness = false;
    if (r.pos < raw.size() && raw[r.pos] == 0) {
        r.read(1);
        if (r.read(1) != 1)
            return false;
        witness = true;
    }
    auto count = r.compact();
    if (!r.ok || count == 0 || count > 10000)
        return false;
    var(stripped, count);
    for (std::uint64_t i = 0; i < count && r.ok; ++i) {
        append(stripped, r.take(36));
        script(stripped, r.script());
        le(stripped, r.read(4), 4);
    }
    auto outputs = r.compact();
    if (!r.ok || outputs == 0 || outputs > 10000 || wanted >= outputs)
        return false;
    var(stripped, outputs);
    bool found = false;
    for (std::uint64_t i = 0; i < outputs && r.ok; ++i) {
        auto value = r.read(8);
        auto s = r.script();
        constexpr std::uint8_t slp[] = {0x53, 0x4c, 0x50, 0x00}, alp[] = {0x53, 0x4c, 0x50, 0x32};
        if (reject_tokens && !s.empty() && s[0] == 0x6a &&
            (std::search(s.begin(), s.end(), std::begin(slp), std::end(slp)) != s.end() ||
             std::search(s.begin(), s.end(), std::begin(alp), std::end(alp)) != s.end()))
            return false;
        le(stripped, value, 8);
        script(stripped, s);
        if (i == wanted)
            found = value == amount && s == expected;
    }
    if (witness)
        for (std::uint64_t i = 0; i < count && r.ok; ++i) {
            auto n = r.compact();
            if (n > 10000)
                return false;
            for (std::uint64_t j = 0; j < n && r.ok; ++j)
                r.script();
        }
    le(stripped, r.read(4), 4);
    if (!r.ok || r.pos != raw.size() || !found)
        return false;
    txhash = hash(stripped);
    return txhash.size() == 32;
}
std::string lower(std::string s) {
    for (auto& c : s)
        if (c >= 'A' && c <= 'Z')
            c += 32;
    return s;
}
bool pkh(const Bytes& s) {
    return s.size() == 25 && s[0] == 0x76 && s[1] == 0xa9 && s[2] == 20 && s[23] == 0x88 &&
           s[24] == 0xac;
}
bool wpkh(const Bytes& s) {
    return s.size() == 22 && s[0] == 0 && s[1] == 20;
}
Bytes pkh_script(const Bytes& keyhash) {
    Bytes s{0x76, 0xa9, 20};
    append(s, keyhash);
    s.push_back(0x88);
    s.push_back(0xac);
    return s;
}
std::vector<unsigned> groups(const Bytes& b) {
    std::vector<unsigned> d;
    unsigned acc = 0, bits = 0;
    for (auto v : b) {
        acc = (acc << 8) | v;
        bits += 8;
        while (bits >= 5) {
            bits -= 5;
            d.push_back((acc >> bits) & 31);
        }
    }
    if (bits)
        d.push_back((acc << (5 - bits)) & 31);
    return d;
}
std::string witness_address(std::string hrp, const Bytes& s) {
    constexpr char alpha[] = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    std::vector<unsigned> d{unsigned(s[0] == 0 ? 0 : s[0] - 0x50)};
    auto g = groups(Bytes(s.begin() + 2, s.end()));
    d.insert(d.end(), g.begin(), g.end());
    std::uint32_t c = 1;
    const std::uint32_t gen[] = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
    auto feed = [&](unsigned v) {
        auto top = c >> 25;
        c = ((c & 0x1ffffff) << 5) ^ v;
        for (unsigned i = 0; i < 5; ++i)
            if ((top >> i) & 1)
                c ^= gen[i];
    };
    for (auto v : hrp) {
        feed(v >> 5);
    }
    feed(0);
    for (auto v : hrp)
        feed(v & 31);
    for (auto v : d)
        feed(v);
    for (unsigned i = 0; i < 6; ++i)
        feed(0);
    c ^= s[0] == 0 ? 1 : 0x2bc830a3;
    std::string out = hrp + "1";
    for (auto v : d)
        out += alpha[v];
    for (int i = 5; i >= 0; --i)
        out += alpha[(c >> (i * 5)) & 31];
    return out;
}
std::string cash_address(const Bytes& s, std::string_view prefix) {
    Bytes b{std::uint8_t(pkh(s) ? 0 : 8)};
    b.insert(b.end(), s.begin() + (pkh(s) ? 3 : 2), s.begin() + (pkh(s) ? 23 : 22));
    auto d = groups(b);
    std::uint64_t c = 1;
    const std::uint64_t gen[] = {0x98f2bc8e61, 0x79b76d99e2, 0xf33e5fb3c4, 0xae2eabe2a8,
                                 0x1e4f43e470};
    auto feed = [&](unsigned v) {
        auto top = c >> 35;
        c = ((c & 0x07ffffffffULL) << 5) ^ v;
        for (unsigned i = 0; i < 5; ++i)
            if ((top >> i) & 1)
                c ^= gen[i];
    };
    for (char v : prefix) {
        feed(v & 31);
    }
    feed(0);
    for (auto v : d)
        feed(v);
    for (unsigned i = 0; i < 8; ++i)
        feed(0);
    c ^= 1;
    constexpr char a[] = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    std::string out(prefix);
    out += ':';
    for (auto v : d)
        out += a[v];
    for (int i = 7; i >= 0; --i)
        out += a[(c >> (i * 5)) & 31];
    return out;
}
bool address(std::string coin, std::string a, const Bytes& s) {
    const bool ispkh = pkh(s), issh = s.size() == 23 && s[0] == 0xa9 && s[1] == 20 && s[22] == 0x87;
    if (coin == "bitcoincash" || coin == "ecash") {
        if (!ispkh && !issh)
            return false;
        if (a != lower(a) &&
            std::any_of(a.begin(), a.end(), [](char c) { return c >= 'a' && c <= 'z'; }))
            return false;
        a = lower(a);
        if (a.find(':') == std::string::npos)
            a = std::string(coin) + ":" + a;
        return a == cash_address(s, coin);
    }
    if (ispkh || issh) {
        unsigned v = coin == "bitcoin"    ? (ispkh ? 0 : 5)
                     : coin == "litecoin" ? (ispkh ? 48 : 50)
                     : coin == "doge"     ? (ispkh ? 30 : 22)
                                          : (ispkh ? 76 : 16);
        Bytes data{std::uint8_t(v)};
        data.insert(data.end(), s.begin() + (ispkh ? 3 : 2), s.begin() + (ispkh ? 23 : 22));
        char out[128]{};
        if (!base58_encode_check(data.data(), data.size(), HASHER_SHA2D, out, sizeof(out)))
            return false;
        return a == out;
    }
    if ((coin != "bitcoin" && coin != "litecoin") || s.size() < 4 || s[1] != s.size() - 2)
        return false;
    if (!((s[0] == 0 && (s[1] == 20 || s[1] == 32)) || (s[0] == 0x51 && s[1] == 32)))
        return false;
    if (a != lower(a) &&
        std::any_of(a.begin(), a.end(), [](char c) { return c >= 'a' && c <= 'z'; }))
        return false;
    return lower(a) == witness_address(coin == "bitcoin" ? "bc" : "ltc", s);
}
struct Input {
    Bytes txhash, script;
    std::uint64_t index = 0, amount = 0;
};
struct Plan {
    UtxoReview review;
    std::vector<Input> inputs;
    Bytes recipient, change;
    std::uint64_t value = 0, change_value = 0, fee = 0;
};
bool parse(std::string_view payload, Plan& p) {
    if (payload.size() > 180000)
        return false;
    Json root(parse_json(payload), cJSON_Delete);
    if (!root || !unique(root.get()))
        return false;
    auto* a = cJSON_GetObjectItemCaseSensitive(root.get(), "asset");
    auto* t = cJSON_GetObjectItemCaseSensitive(root.get(), "txData");
    if (!cJSON_IsObject(a) || !cJSON_IsObject(t))
        return false;
    auto& v = p.review;
    v.coin = text(a, "coin");
    v.address = text(a, "address");
    v.recipient = text(t, "toAddress");
    v.path = text(a, "derivation_path");
    v.mode = text(root.get(), "mode");
    const char* path = nullptr;
    if (v.coin == "bitcoin") {
        v.network = Network::Bitcoin;
        v.symbol = "BTC";
        path = "m/84'/0'/0'/0/";
    } else if (v.coin == "litecoin") {
        v.network = Network::Litecoin;
        v.symbol = "LTC";
        path = "m/84'/2'/0'/0/";
    } else if (v.coin == "doge") {
        v.network = Network::Dogecoin;
        v.symbol = "DOGE";
        path = "m/44'/3'/0'/0/";
    } else if (v.coin == "dash") {
        v.network = Network::Dash;
        v.symbol = "DASH";
        path = "m/44'/5'/0'/0/";
    } else if (v.coin == "bitcoincash") {
        v.network = Network::BitcoinCash;
        v.symbol = "BCH";
        path = "m/44'/145'/0'/0/";
    } else if (v.coin == "ecash") {
        v.network = Network::Ecash;
        v.symbol = "XEC";
        path = "m/44'/899'/0'/0/";
        v.decimals = 2;
    } else
        return false;
    std::uint64_t index = 0;
    const std::string prefix(path);
    if (v.path.substr(0, prefix.size()) != prefix || !number(v.path.substr(prefix.size()), index) ||
        index >= 0x80000000)
        return false;
    if (v.mode != "STANDARD" && v.mode != "HIDDEN")
        return false;
    auto* version = cJSON_GetObjectItemCaseSensitive(t, "utxoVersion");
    auto* decimals = cJSON_GetObjectItemCaseSensitive(a, "decimals");
    if (!cJSON_IsNumber(version) || version->valuedouble != 1 || !cJSON_IsNumber(decimals) ||
        decimals->valuedouble != v.decimals || text(a, "symbol") != v.symbol ||
        !text(a, "contract").empty())
        return false;
    if (!hex(text(t, "recipientScript"), p.recipient) || !hex(text(t, "changeScript"), p.change) ||
        !address(v.coin, v.recipient, p.recipient) || !address(v.coin, v.address, p.change) ||
        text(t, "changeAddress") != v.address)
        return false;
    if (!pkh(p.change) && !wpkh(p.change))
        return false;
    if ((v.coin == "bitcoin" || v.coin == "litecoin") != wpkh(p.change))
        return false;
    v.amount = text(t, "amount");
    v.fee = text(t, "fee");
    v.change = text(t, "changeAmount");
    std::uint64_t rate = 0;
    if (!number(v.amount, p.value) || !number(v.fee, p.fee) || !number(v.change, p.change_value) ||
        !number(text(t, "byteFee"), rate) || rate == 0 || rate > 10000000 || p.fee == 0)
        return false;
    const auto dust = v.coin == "doge" ? 1000000U : v.coin == "litecoin" ? 1000U : 546U;
    if (p.value < dust || (p.change_value && p.change_value < dust) || p.recipient == p.change)
        return false;
    auto* rows = cJSON_GetObjectItemCaseSensitive(t, "utxos");
    if (!cJSON_IsArray(rows) || cJSON_GetArraySize(rows) < 1 || cJSON_GetArraySize(rows) > 16)
        return false;
    std::uint64_t total = 0;
    std::set<std::string> seen;
    for (auto* row = rows->child; row; row = row->next) {
        Input in;
        Bytes raw, txid;
        auto* ix = cJSON_GetObjectItemCaseSensitive(row, "index");
        auto* seq = cJSON_GetObjectItemCaseSensitive(row, "sequence");
        if (!cJSON_IsNumber(ix) || ix->valuedouble < 0 || ix->valuedouble > 0xffffffffULL ||
            std::floor(ix->valuedouble) != ix->valuedouble || !cJSON_IsNumber(seq) ||
            seq->valuedouble != 0xffffffffULL)
            return false;
        in.index = static_cast<std::uint64_t>(ix->valuedouble);
        if (!number(text(row, "amount"), in.amount) || in.amount == 0 ||
            !hex(text(row, "script"), in.script) || in.script != p.change ||
            !hex(text(row, "txHash"), txid) || txid.size() != 32 ||
            !hex(text(row, "previousTransaction"), raw))
            return false;
        if (!seen.insert(text(row, "txHash") + ":" + std::to_string(in.index)).second ||
            !funding(raw, in.index, in.script, in.amount, in.txhash, v.coin == "ecash"))
            return false;
        std::reverse(txid.begin(), txid.end());
        if (in.txhash != txid || total > limit - in.amount)
            return false;
        total += in.amount;
        p.inputs.push_back(std::move(in));
    }
    if (p.value > total || p.change_value > total - p.value ||
        p.fee != total - p.value - p.change_value)
        return false;
    return true;
}
void outpoint(Bytes& b, const Input& i) {
    append(b, i.txhash);
    le(b, i.index, 4);
}
Bytes outputs(const Plan& p) {
    Bytes b;
    le(b, p.value, 8);
    script(b, p.recipient);
    if (p.change_value) {
        le(b, p.change_value, 8);
        script(b, p.change);
    }
    return b;
}
Bytes transaction(const Plan& p, const std::vector<Bytes>& scripts,
                  const std::vector<Bytes>& signatures, const Bytes& pub) {
    const bool witness = !signatures.empty() && wpkh(p.change);
    Bytes b;
    le(b, 2, 4);
    if (witness) {
        b.push_back(0);
        b.push_back(1);
    }
    var(b, p.inputs.size());
    for (std::size_t i = 0; i < p.inputs.size(); ++i) {
        outpoint(b, p.inputs[i]);
        script(b, scripts[i]);
        le(b, 0xffffffff, 4);
    }
    var(b, p.change_value ? 2 : 1);
    append(b, outputs(p));
    if (witness) {
        for (auto& sig : signatures) {
            var(b, 2);
            script(b, sig);
            script(b, pub);
        }
    }
    le(b, 0, 4);
    return b;
}
} // namespace
bool review_utxo(std::string_view payload, UtxoReview& review) {
    Plan p;
    if (!parse(payload, p))
        return false;
    review = std::move(p.review);
    return true;
}
bool sign_utxo(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
               std::string& encoded) {
    crypto::TrezorRandomScope binding(random);
    encoded.clear();
    Plan p;
    if (!parse(payload, p))
        return false;
    crypto::DerivedKey key;
    if (crypto::derive_key(seed, crypto::Curve::Secp256k1, p.review.path, random, key) !=
        crypto::HdError::None)
        return false;
    AccountPublicKey public_key{key.public_key, key.public_key_size};
    std::string source;
    if (encode_receiving_address(p.review.network, public_key, source) != crypto::HdError::None ||
        source != p.review.address)
        return false;
    Bytes pub(key.public_key.begin(), key.public_key.begin() + key.public_key_size);
    Bytes pkhash(32);
    hasher_Raw(HASHER_SHA2_RIPEMD, pub.data(), pub.size(), pkhash.data());
    // hasher_Raw SHA2_RIPEMD writes 20 bytes.
    pkhash.resize(20);
    auto code = pkh_script(pkhash);
    std::vector<Bytes> scripts(p.inputs.size()), signatures;
    const auto type = (p.review.coin == "bitcoincash" || p.review.coin == "ecash") ? 0x41U : 1U;
    for (std::size_t i = 0; i < p.inputs.size(); ++i) {
        Bytes digest;
        if (wpkh(p.change) || type == 0x41) {
            Bytes points, sequences;
            for (auto& in : p.inputs) {
                outpoint(points, in);
                le(sequences, 0xffffffff, 4);
            }
            Bytes pre;
            le(pre, 2, 4);
            append(pre, hash(points));
            append(pre, hash(sequences));
            outpoint(pre, p.inputs[i]);
            script(pre, code);
            le(pre, p.inputs[i].amount, 8);
            le(pre, 0xffffffff, 4);
            append(pre, hash(outputs(p)));
            le(pre, 0, 4);
            le(pre, type, 4);
            digest = hash(pre);
        } else {
            std::vector<Bytes> unsigned_scripts(p.inputs.size());
            unsigned_scripts[i] = code;
            auto pre = transaction(p, unsigned_scripts, {}, {});
            le(pre, type, 4);
            digest = hash(pre);
        }
        if (digest.size() != 32)
            return false;
        crypto::Sensitive<64> signature;
        if (ecdsa_sign_digest(&secp256k1, key.private_key.value.data(), digest.data(),
                              signature.value.data(), nullptr, nullptr) != 0)
            return false;
        std::array<std::uint8_t, 72> der{};
        auto size = ecdsa_sig_to_der(signature.value.data(), der.data());
        if (size < 8 || size > 72)
            return false;
        Bytes sig(der.begin(), der.begin() + size);
        sig.push_back(type);
        signatures.push_back(sig);
        if (!wpkh(p.change)) {
            scripts[i].push_back(sig.size());
            append(scripts[i], sig);
            scripts[i].push_back(pub.size());
            append(scripts[i], pub);
        }
    }
    encoded = hex(transaction(p, scripts, signatures, pub));
    return !encoded.empty();
}
} // namespace airlock
