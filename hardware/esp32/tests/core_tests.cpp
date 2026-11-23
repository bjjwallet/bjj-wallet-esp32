// SPDX-License-Identifier: GPL-3.0-or-later
// Only public, synthetic data. No real mnemonic or key is used here.
#include "core/rules.hpp"
#include "core/secret.hpp"
#include "hardware/ble_framing.hpp"
#include "hardware/bond_admission.hpp"
#include "hardware/peripheral_policy.hpp"
#include "hardware/ft6336.hpp"
#include "ui/states_wallet.hpp"
#include "ui/state_sign.hpp"
#include <cstdlib>
#include <iostream>
#include <type_traits>
#include <utility>

using namespace airlock;
namespace {
int checks = 0;
#define CHECK(condition)                                                                           \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(condition)) {                                                                        \
            std::cerr << "Check failed at line " << __LINE__ << ": " #condition "\n";              \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
constexpr std::string_view phrase = "Example-Phrase-123!";
constexpr std::string_view password = "Example-Pay-123!";

void rules_and_secrets() {
    static_assert(!std::is_copy_constructible_v<Credential>);
    static_assert(!std::is_copy_constructible_v<SigningRequest>);
    CHECK(validate_credential(phrase, passphrase_rules) == InputError::None);
    CHECK(validate_credential("ExampleShort1!", passphrase_rules) == InputError::TooShort);
    CHECK(validate_credential("abcdefghijkl1!", password_rules) == InputError::Uppercase);
    CHECK(validate_credential("ABCDEFGHIJKL1!", password_rules) == InputError::Lowercase);
    CHECK(validate_credential("Abcdefghijklm!", password_rules) == InputError::Digit);
    CHECK(validate_credential("Abcdefghijklm1", password_rules) == InputError::Special);
    CHECK(validate_credential("Ab1!éééé", password_rules) == InputError::TooShort);
    CHECK(validate_credential(std::string(1025, 'a'), password_rules) == InputError::TooLong);
    CHECK(!valid_text(std::string_view("abc\0def", 7)));
    CHECK(!valid_text("\xc0\xaf"));
    CHECK(!valid_text("\xed\xa0\x80"));
    CHECK(!valid_text("\xf4\x90\x80\x80"));
    CHECK(!valid_text("\xe2\x82"));
    CHECK(valid_text("senha 中文 🔐"));
    CHECK(valid_text(""));
    CHECK(is_mnemonic_word("abandon"));
    CHECK(is_mnemonic_word("zoo"));
    CHECK(!is_mnemonic_word("nonexistent"));
    CHECK(!is_mnemonic_word("Abandon"));
    CHECK(format_amount("1000000000000000000", 18) == "1");
    CHECK(format_amount("123456789123456789123456789", 18) == "123456789.123456789123456789");
    CHECK(format_amount("1", 18) == "0.000000000000000001");
    CHECK(format_amount("000000", 6) == "0");
    CHECK(format_amount("00020", 0) == "20");
    CHECK(!format_amount("1e18", 18));
    CHECK(!format_amount("1", 256));
    CHECK(!is_unsigned_decimal("1e18"));
    CHECK(!is_unsigned_decimal("-1"));
    CHECK(!is_unsigned_decimal("1.5"));
    CHECK(!is_unsigned_decimal(""));
    Credential secret;
    CHECK(secret.assign(password));
    CHECK(!secret.assign(std::string(1025, 'x')));
    CHECK(secret.equals(password));
    CHECK(secret.assign(secret.view().substr(1)));
    CHECK(secret.equals(password.substr(1)));
    Credential moved(std::move(secret));
    CHECK(secret.view().empty());
    CHECK(moved.equals(password.substr(1)));
    CHECK(!moved.equals("other"));
    moved.clear();
    CHECK(moved.view().empty());
    SecretBuffer buffer;
    CHECK(buffer.assign("payload", 8));
    CHECK(!buffer.assign("too long!", 8));
    SecretBuffer destination(std::move(buffer));
    CHECK(buffer.view().empty());
    CHECK(destination.view() == "payload");
    CHECK(destination.assign(destination.view().substr(1), 8));
    CHECK(destination.view() == "ayload");
}

void bond_admission() {
    BondAdmission admission;
    // More phones than the four cached bonds, each approved independently.
    for (std::uint64_t phone = 1; phone <= 32; ++phone) {
        CHECK(!admission.may_rotate(phone, true, true));
        admission.approve_pairing(phone);
        CHECK(!admission.may_rotate(phone, true, false));
        CHECK(!admission.may_rotate(phone, false, true));
        CHECK(!admission.may_rotate(phone + 1, true, true));
        CHECK(admission.may_rotate(phone, true, true));
        admission.reset(); // disconnect, rejection, lock or new connection
        CHECK(!admission.may_rotate(phone, true, true));
    }
    admission.approve_pairing(0);
    CHECK(!admission.may_rotate(0, true, true));
}

void framing() {
    const std::string packet = "{\"id\":7,\"method\":\"ping\"}";
    const std::string frame = packet + std::string(config::eof_marker);
    // Exercise every two-fragment boundary, including all delimiter boundaries.
    for (std::size_t split = 0; split <= frame.size(); ++split) {
        BleFraming rx;
        std::vector<std::string> output;
        auto receiver = [&](auto text) { output.emplace_back(text); };
        CHECK(rx.receive(std::string_view(frame).substr(0, split), 10, receiver) ==
              ReceiveStatus::Ok);
        CHECK(rx.receive(std::string_view(frame).substr(split), 20, receiver) == ReceiveStatus::Ok);
        CHECK(output.size() == 1 && output[0] == packet);
        CHECK(rx.buffered_bytes() == 0);
    }
    BleFraming rx(packet.size());
    std::vector<std::string> output;
    auto receive = [&](auto text) { output.emplace_back(text); };
    CHECK(rx.receive(frame + frame + "{", 100, receive) == ReceiveStatus::Ok);
    CHECK(output.size() == 2);
    CHECK(rx.buffered_bytes() == 1);
    CHECK(rx.poll(10100) == ReceiveStatus::Expired);
    CHECK(rx.receive(frame, 10101, receive) == ReceiveStatus::Expired);
    CHECK(output.size() == 2);
    rx.reset();
    CHECK(rx.receive(std::string(packet.size() + 1, 'x') + frame, 1, receive) ==
          ReceiveStatus::Overflow);
    CHECK(rx.buffered_bytes() == 0 && output.size() == 2);
    rx.reset();
    CHECK(rx.receive("{", 100, receive) == ReceiveStatus::Ok);
    CHECK(rx.poll(99) == ReceiveStatus::ClockError);
    rx.reset();
    CHECK(rx.receive(frame, 200, receive) == ReceiveStatus::Ok);
    CHECK(output.size() == 3);
    // ATT MTU 23 only allows 20 data bytes; do not assume an MTU of 185.
    for (auto mtu : {23, 185, 517}) {
        std::string combined;
        CHECK(send_frame(packet, mtu, [&](auto part) {
            CHECK(part.size() <= std::min<std::size_t>(mtu - 3, 180));
            combined += part;
            return true;
        }));
        CHECK(combined == frame);
    }
    int calls = 0;
    CHECK(!send_frame(frame, 23, [&](auto) {
        ++calls;
        return true;
    }));
    CHECK(calls == 0);
    CHECK(!send_frame(packet, 22, [&](auto) {
        ++calls;
        return true;
    }));
    CHECK(calls == 0);
    CHECK(!send_frame(packet, 23, [&](auto) {
        ++calls;
        return false;
    }));
    CHECK(calls == 1);
}

void enter_creation(WalletStates& flow) {
    flow.begin(WalletFlow::Create, false);
    CHECK(flow.input(phrase) == InputError::None);
    CHECK(flow.input("mismatch") == InputError::Mismatch);
    CHECK(flow.input(phrase) == InputError::None);
    CHECK(flow.input(phrase) == InputError::None);
    CHECK(flow.input(phrase) == InputError::None);
    CHECK(flow.input(phrase) == InputError::SameCredentials);
    CHECK(flow.input(password) == InputError::None);
    CHECK(flow.input(password) == InputError::None);
    CHECK(flow.stage() == WalletStage::Generating);
}
std::array<std::string_view, 24> example_words() {
    // Dictionary-only fixture, deliberately NOT a cryptographic checksum test.
    std::array<std::string_view, 24> words;
    words.fill("abandon");
    words[23] = "art";
    return words;
}
void wallet_flow() {
    WalletStates flow;
    flow.begin(WalletFlow::Create, true);
    CHECK(flow.stage() == WalletStage::Overwrite);
    CHECK(!flow.take_job());
    CHECK(flow.input(phrase) == InputError::WrongStage);
    flow.cancel();
    CHECK(flow.stage() == WalletStage::Idle);
    flow.begin(WalletFlow::Create, true);
    CHECK(flow.confirm_overwrite() == InputError::None);
    CHECK(flow.stage() == WalletStage::Passphrase);
    enter_creation(flow);
    auto generate = flow.take_job();
    CHECK(generate && generate->kind == WalletWork::Generate);
    CHECK(generate->password.equals(password));
    CHECK(!flow.take_job());
    CHECK(!flow.saved(generate->token));
    CHECK(!flow.generated(generate->token + 1, example_words()));
    CHECK(flow.generated(generate->token, example_words()));
    CHECK(flow.backup_word(23) == "art");
    CHECK(flow.backup_word(24).empty());
    CHECK(!flow.take_job());
    CHECK(flow.begin_backup_verification() == InputError::None);
    CHECK(flow.backup_word(0).empty());
    CHECK(flow.input("zoo") == InputError::Mismatch);
    for (auto word : example_words())
        CHECK(flow.input(word) == InputError::None);
    CHECK(flow.stage() == WalletStage::VerifyPassphrase);
    CHECK(flow.input(phrase) == InputError::None);
    CHECK(flow.input("wrong") == InputError::Mismatch);
    CHECK(flow.input(password) == InputError::None);
    CHECK(flow.stage() == WalletStage::Done);
    CHECK(!flow.saved(generate->token));
    CHECK(!flow.take_job());
    enter_creation(flow);
    auto cancelled = flow.take_job();
    flow.cancel();
    CHECK(!flow.generated(cancelled->token, example_words()));
    // Recovery accepts every original word count and an empty passphrase.
    for (auto count : config::import_word_counts) {
        flow.begin(WalletFlow::Import, true);
        CHECK(flow.confirm_overwrite() == InputError::None);
        CHECK(flow.select_word_count(13) == InputError::InvalidWordCount);
        CHECK(flow.select_word_count(count) == InputError::None);
        CHECK(flow.input(password) == InputError::None);
        CHECK(flow.input(password) == InputError::None);
        CHECK(flow.input("   ") == InputError::InvalidWord);
        for (std::size_t i = 0; i < count; ++i)
            CHECK(flow.input(" ABANDON ") == InputError::None);
        CHECK(flow.input("") == InputError::None);
        auto import = flow.take_job();
        CHECK(import && import->word_count == count && import->kind == WalletWork::Import);
        CHECK(import->passphrase.view().empty());
        CHECK(flow.failed(import->token)); // Simulate backend checksum/storage error.
        CHECK(flow.stage() == WalletStage::ImportWord && flow.word_index() == 0);
        CHECK(!flow.saved(import->token));
        for (std::size_t i = 0; i < count; ++i)
            CHECK(flow.input("abandon") == InputError::None);
        CHECK(flow.input("legacy phrase") == InputError::None);
        auto retry = flow.take_job();
        CHECK(retry && retry->password.equals(password) &&
              retry->passphrase.equals("legacy phrase"));
        CHECK(flow.saved(retry->token));
    }
}
SigningRequest request(std::uint64_t connection = 7) {
    SigningRequest request;
    request.id_json = "42";
    request.connection = connection;
    request.review = {{"Asset", "TEST"}, {"Amount", "1"}, {"Recipient", "synthetic-test-address"}};
    CHECK(request.payload.assign("{\"test\":true}", config::max_packet_bytes));
    CHECK(request.password.assign(password));
    return request;
}
void signing_flow() {
    TransactionSignState flow;
    CHECK(flow.begin(request(), 100));
    auto token = flow.token();
    CHECK(!flow.begin(request(), 110));
    CHECK(!flow.confirm(token, 111));
    CHECK(!flow.mark_reviewed(token + 1));
    CHECK(flow.mark_reviewed(token));
    auto job = flow.confirm(token, 112);
    CHECK(job && job->request.password.equals(password));
    CHECK(!flow.confirm(token, 113));
    CHECK(!flow.reject());
    CHECK(!flow.poll(999999)); // Review timeout is not a crypto execution timeout.
    CHECK(!flow.completed(token + 1, 7, "test-result", true));
    CHECK(!flow.completed(token, 8, "test-result", true));
    auto done = flow.completed(token, 7, "test-result", true);
    CHECK(done && done->reason == SignEnd::Completed && done->id_json == "42");
    CHECK(!flow.completed(token, 7, "test-result", true));
    CHECK(flow.begin(request(), 0));
    token = flow.token();
    CHECK(flow.mark_reviewed(token));
    CHECK(!flow.confirm(token, 60001));
    auto timeout = flow.poll(60001);
    CHECK(timeout && timeout->reason == SignEnd::TimedOut);
    CHECK(!flow.poll(60001));
    CHECK(flow.begin(request(), 100));
    CHECK(flow.poll(99)->reason == SignEnd::TimedOut);
    CHECK(flow.begin(request(), 100));
    CHECK(flow.reject()->reason == SignEnd::Rejected);
    CHECK(flow.begin(request(8), 100));
    token = flow.token();
    CHECK(flow.mark_reviewed(token));
    CHECK(flow.confirm(token, 101));
    CHECK(!flow.disconnect(7));
    CHECK(flow.disconnect(8)->reason == SignEnd::Disconnected);
    CHECK(!flow.completed(token, 8, "test-result", true));
    CHECK(flow.begin(request(9), 102));
    CHECK(!flow.completed(token, 8, "test-result", true));
    CHECK(!flow.mark_reviewed(token));
}
} // namespace
int main() {
    // Raw FT6336G reports, including the second finger and failed I2C reads.
    Ft6336Decoder touch;
    CHECK(touch.read({1, 0, 20, 0, 30}).cancel); // held at boot
    CHECK(!touch.read({0, 0, 0, 0, 0}).pressed);
    auto point = touch.read({1, 0, 239, 1, 63}); // last pixel, y=319
    CHECK(point.pressed && point.x == 319 && point.y == 0);
    touch.read({0, 0, 0, 0, 0});
    point = touch.read({1, 0, 0, 0, 0});
    CHECK(point.pressed && point.x == 0 && point.y == 239);
    touch.read({0, 0, 0, 0, 0});
    point = touch.read({1, 0, 239, 0, 0});
    CHECK(point.pressed && point.x == 0 && point.y == 0);
    touch.read({0, 0, 0, 0, 0});
    point = touch.read({1, 0, 0, 1, 63});
    CHECK(point.pressed && point.x == 319 && point.y == 239);
    touch.read({0, 0, 0, 0, 0});
    CHECK(touch.read({1, 0x80, 239, 1, 63}).pressed); // continuing contact
    CHECK(!touch.read({0, 0, 0, 0, 0}).pressed);
    CHECK(touch.read({1, 0, 240, 0, 20}).cancel); // out of bounds, not clamped
    CHECK(touch.read({1, 0, 10, 0, 20}).cancel);  // must lift first
    touch.read({0, 0, 0, 0, 0});
    CHECK(touch.read({1, 0, 10, 1, 64}).cancel); // y=320 invalid
    touch.read({0, 0, 0, 0, 0});
    CHECK(touch.read({1, 0, 10, 0, 20}).pressed);
    CHECK(touch.read({2, 0x80, 10, 0, 20}).cancel);
    CHECK(touch.read({1, 0x80, 10, 0, 20}).cancel);
    touch.read({0, 0, 0, 0, 0});
    CHECK(touch.read({1, 0, 10, 0, 20}).pressed);
    CHECK(touch.read({1, 0x80, 10, 0x10, 20}).cancel); // finger ID changed
    touch.read({0, 0, 0, 0, 0});
    CHECK(touch.read({1, 0, 10, 0, 20}).pressed);
    CHECK(touch.read({}, false).cancel); // bus error must cancel, not click
    CHECK(touch.read({1, 0x80, 10, 0, 20}).cancel);
    touch.read({0, 0, 0, 0, 0});
    CHECK(touch.read({1, 0xc0, 10, 0, 20}).cancel); // reserved event
    touch.read({0, 0, 0, 0, 0});
    CHECK(touch.read({1, 0, 10, 0, 20}).pressed);
    CHECK(!touch.read({1, 0x40, 10, 0, 20}).pressed); // explicit lift event
    CHECK(touch.read({0xf0, 0, 0, 0, 0}).cancel);
    rules_and_secrets();
    bond_admission();
    framing();
    wallet_flow();
    signing_flow();
    std::cout << checks
              << " portable checks passed. No hardware or cryptographic signing tested.\n";
}
