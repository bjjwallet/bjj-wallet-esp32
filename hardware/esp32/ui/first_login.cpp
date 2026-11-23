// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/first_login.hpp"
#include "ui/address_catalog.hpp"
#include "core/rules.hpp"
#include "setup_strings.hpp"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <new>

namespace airlock {
namespace {
auto find_address(const std::vector<ReceivingAddress>& addresses, std::string_view network) {
    return std::find_if(addresses.begin(), addresses.end(), [network](const auto& entry) {
        return entry.network == network && !entry.value.empty();
    });
}

bool append_word(Credential& mnemonic, std::string_view word) {
    crypto::Sensitive<1024> joined;
    auto size = mnemonic.view().size();
    if (size + (size ? 1 : 0) + word.size() > joined.value.size())
        return false;
    std::memcpy(joined.value.data(), mnemonic.view().data(), size);
    if (size)
        joined.value[size++] = ' ';
    std::memcpy(joined.value.data() + size, word.data(), word.size());
    size += word.size();
    return mnemonic.assign({reinterpret_cast<char*>(joined.value.data()), size});
}
void pop_word(Credential& mnemonic) {
    const auto end = mnemonic.view().rfind(' ');
    if (end == std::string_view::npos)
        mnemonic.clear();
    else
        mnemonic.assign(mnemonic.view().substr(0, end));
}
} // namespace
FirstLogin::FirstLogin(TouchView& view, SetupService& service) : view_(view), service_(service) {}
void FirstLogin::start(std::uint64_t now_ms) {
    language_ready_ = false;
    initial_language_choice_ = true;
    editing_device_name_ = false;
    device_name_.clear();
    now_ = now_ms;
    splash_until_ = now_ms + 2000;
    request_.reset();
    candidate_.reset();
    session_.reset();
    first_.clear();
    old_phrase_.clear();
    startup_pin_.clear();
    pending_shared_phrase_.clear();
    phrase_proofs_.clear();
    apply_phrase_to_all_ = gathering_phrases_ = false;
    upgrading_pin_ = false;
    access_devices_.clear();
    selected_device_id_.clear();
    profiles_.clear();
    edit_ = Edit::None;
    additional_ = auto_unlock_ = false;
    unlock_target_ = FirstLoginPage::Main;
    auth_errors_ = phrase_errors_ = 0;
    addresses_.clear();
    address_after_unlock_ = resume_address_detail_ = false;
    std::string code;
    const auto locale_status = service_.load_language(code);
    if (locale_status == StoreRead::Missing) {
        render(FirstLoginPage::Language);
        return;
    }
    const auto selected = parse_language(code);
    if (locale_status != StoreRead::Found || !selected) {
        fault(tr(Text::language_read_error));
        return;
    }
    language_ = *selected;
    language_ready_ = true;
    initial_language_choice_ = false;
    view_.language(language_);
    if (!load_device_name())
        return;
    WalletProfile existing;
    if (device_name_.empty() && service_.inspect(existing) == VaultError::Missing)
        render(FirstLoginPage::DeviceName);
    else
        render(FirstLoginPage::Splash);
}
bool FirstLogin::load_device_name() {
    std::string name;
    const auto status = service_.load_device_name(name);
    if (status == StoreRead::Error || (status == StoreRead::Found && !valid_device_name(name))) {
        fault(tr(Text::device_name_read_error));
        return false;
    }
    device_name_ = status == StoreRead::Found ? name : std::string{};
    return true;
}
const char* FirstLogin::tr(Text text) const {
    return locale_text(text, language_);
}
void FirstLogin::after_splash() {
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::Open;
    job->now = now_;
    submit(std::move(job));
}
void FirstLogin::fault(const char* message) {
    request_.reset();
    candidate_.reset();
    session_.reset();
    first_.clear();
    old_phrase_.clear();
    startup_pin_.clear();
    pending_shared_phrase_.clear();
    phrase_proofs_.clear();
    apply_phrase_to_all_ = gathering_phrases_ = false;
    addresses_.clear();
    address_after_unlock_ = resume_address_detail_ = false;
    page_ = FirstLoginPage::Fault;
    view_.text(tr(Text::nao_foi_possivel_concluir_001), message, tr(Text::tentar_novamente_002),
               false, false);
}
void FirstLogin::render(FirstLoginPage page, const char* error) {
    if ((page == FirstLoginPage::WordReview || page == FirstLoginPage::Backup) &&
        page_ != FirstLoginPage::SeedNotice) {
        seed_target_ = page;
        render(FirstLoginPage::SeedNotice);
        return;
    }
    page_ = page;
    view_.directional_back(page != FirstLoginPage::Splash && page != FirstLoginPage::Choice &&
                           page != FirstLoginPage::Busy && page != FirstLoginPage::Backup &&
                           page != FirstLoginPage::Success && page != FirstLoginPage::Ready &&
                           page != FirstLoginPage::Cooldown && page != FirstLoginPage::Fault);
    switch (page) {
        case FirstLoginPage::Language: {
            std::vector<std::string> names;
            for (auto language : language_order)
                names.emplace_back(language_names[static_cast<std::size_t>(language)]);
            view_.menu(error ? error : tr(Text::choose_language), names, !initial_language_choice_);
            break;
        }
        case FirstLoginPage::DeviceName: {
            const auto hint =
                error ? std::string(error)
                      : std::string(tr(Text::device_name_hint)) +
                            (editing_device_name_ ? "\n" + device_name() : std::string{});
            view_.input(tr(editing_device_name_ ? Text::edit_device_name : Text::device_name_title),
                        hint.c_str(), false);
            break;
        }
        case FirstLoginPage::Splash:
            view_.splash();
            break;
        case FirstLoginPage::Choice:
            view_.menu(tr(Text::sua_primeira_carteira_003),
                       {tr(Text::criar_wallet_004), tr(Text::importar_wallet_005)});
            break;
        case FirstLoginPage::Name:
            view_.input(tr(Text::nome_da_wallet_006),
                        error ? error : tr(Text::como_voce_quer_chamar_sua_carteira_007), false);
            break;
        case FirstLoginPage::WordCount:
            view_.menu(request_->imported ? tr(Text::quantas_palavras_importar_008)
                                          : tr(Text::criar_seed_com_009),
                       {tr(Text::text_12_palavras_010), tr(Text::text_18_palavras_011),
                        tr(Text::text_24_palavras_012)},
                       true);
            break;
        case FirstLoginPage::ImportWord: {
            char title[48];
            std::snprintf(title, sizeof title, tr(Text::palavra_u_de_u_013),
                          static_cast<unsigned>(import_index_ + 1),
                          static_cast<unsigned>(request_->word_count));
            view_.input(title, error ? error : tr(Text::digite_a_proxima_palavra_da_sua_014), false,
                        true);
            break;
        }
        case FirstLoginPage::PassChoice: {
            const auto notice =
                std::string(request_->imported
                                ? tr(Text::se_a_carteira_original_usa_passphrase_015)
                                : tr(Text::opcional_uma_passphrase_cria_outra_carteira_016)) +
                tr(Text::a_seed_e_as_chaves_privadas_000);
            view_.text(tr(Text::passphrase_da_seed_017), notice.c_str(),
                       request_->imported ? tr(Text::informar_018) : tr(Text::configurar_019),
                       false, true, tr(Text::nao_usar_020));
            break;
        }
        case FirstLoginPage::PassEntry:
            view_.input(tr(Text::passphrase_opcional_021),
                        error ? error
                        : request_->imported
                            ? tr(Text::digite_a_passphrase_da_carteira_original_022)
                            : tr(Text::digite_a_passphrase_adicional_da_seed_023),
                        true);
            break;
        case FirstLoginPage::PassConfirm:
            view_.input(tr(Text::repita_a_passphrase_024),
                        tr(Text::digite_novamente_a_mesma_passphrase_025), true);
            break;
        case FirstLoginPage::PinChoice:
            view_.text(tr(Text::pin_de_desbloqueio_026), tr(Text::opcional_de_4_a_12_numeros_027),
                       tr(Text::configurar_pin_028), false, false);
            break;
        case FirstLoginPage::PinEntry:
            view_.input(tr(Text::crie_seu_pin_030),
                        error ? error : tr(Text::use_de_4_a_12_numeros_031), true, false, false);
            break;
        case FirstLoginPage::PinConfirm:
            view_.input(tr(Text::repita_o_pin_032), tr(Text::digite_novamente_o_mesmo_pin_033),
                        true, false, false);
            break;
        case FirstLoginPage::MobilePolicy:
            view_.checkbox(
                tr(Text::acesso_pelo_celular_034), tr(Text::marcado_o_mobile_informa_o_pin_035),
                tr(Text::pedir_pin_no_celular_036), request_->mobile_access == MobileAccess::Pin);
            break;
        case FirstLoginPage::TransactionIntro:
            view_.checkbox(edit_ == Edit::Phrase ? tr(Text::nova_transactionphrase_037)
                                                 : tr(Text::frase_de_transacao_038),
                           additional_ && apply_phrase_to_all_ &&
                                   profile_.shared_transaction_phrase && !profiles_.empty()
                               ? tr(Text::todas_as_wallets_usam_uma_frase_039)
                               : tr(Text::crie_uma_frase_com_pelo_menos_040),
                           tr(Text::usar_em_todas_as_wallets_041), apply_phrase_to_all_);
            break;
        case FirstLoginPage::TransactionEntry:
            view_.input(edit_ == Edit::Phrase ? tr(Text::nova_transactionphrase_037)
                                              : tr(Text::frase_de_transacao_038),
                        error ? error : tr(Text::crie_uma_frase_com_pelo_menos_042), true);
            break;
        case FirstLoginPage::TransactionConfirm:
            view_.input(tr(Text::repita_a_frase_043),
                        tr(Text::digite_novamente_a_frase_de_transacao_044), true);
            break;
        case FirstLoginPage::Busy:
            view_.busy(tr(Text::configurando_carteira_045), tr(Text::aguarde_um_instante_046));
            break;
        case FirstLoginPage::WordReview:
        case FirstLoginPage::Backup: {
            crypto::Sensitive<1024> display;
            const bool review = page == FirstLoginPage::WordReview;
            const auto words = review ? request_->mnemonic.view() : candidate_->backup_words.view();
            std::size_t source = 0, position = 0, index = 1;
            while (source < words.size()) {
                const auto end = words.find(' ', source);
                const auto word = words.substr(
                    source, end == std::string_view::npos ? words.size() - source : end - source);
                char number[8];
                const auto count = std::snprintf(number, sizeof number, tr(Text::u_047),
                                                 static_cast<unsigned>(index++));
                if (count < 0 || position + static_cast<std::size_t>(count) + word.size() + 1 >=
                                     display.value.size()) {
                    fault(tr(Text::nao_foi_possivel_exibir_o_backup_048));
                    return;
                }
                std::memcpy(display.value.data() + position, number, count);
                position += count;
                std::memcpy(display.value.data() + position, word.data(), word.size());
                position += word.size();
                display.value[position++] = '\n';
                if (end == std::string_view::npos)
                    break;
                source = end + 1;
            }
            if (!review) {
                const std::string_view heading = tr(Text::passphrase_adicional_049);
                if (position + heading.size() >= display.value.size()) {
                    fault(tr(Text::nao_foi_possivel_exibir_o_backup_050));
                    return;
                }
                std::memcpy(display.value.data() + position, heading.data(), heading.size());
                position += heading.size();
                const auto passphrase = candidate_->backup_passphrase.view().empty()
                                            ? std::string_view(tr(Text::nao_configurada_051))
                                            : candidate_->backup_passphrase.view();
                if (position + passphrase.size() >= display.value.size()) {
                    fault(tr(Text::nao_foi_possivel_exibir_a_passphrase_052));
                    return;
                }
                std::memcpy(display.value.data() + position, passphrase.data(), passphrase.size());
            }
            view_.text(review ? tr(Text::confira_as_palavras_053) : tr(Text::backup_da_wallet_054),
                       reinterpret_cast<char*>(display.value.data()),
                       review ? tr(Text::esta_correto_055) : tr(Text::anotei_tudo_056), true, true,
                       review ? tr(Text::corrigir_057) : tr(Text::cancelar_058));
            break;
        }
        case FirstLoginPage::Unlock:
            view_.input(tr(Text::desbloquear_wallet_059),
                        error ? error : tr(Text::digite_o_pin_numerico_de_desbloqueio_060), true,
                        false, false);
            break;
        case FirstLoginPage::Cooldown: {
            char notice[160];
            countdown_second_ = (retry_until_ > now_ ? retry_until_ - now_ + 999 : 0) / 1000;
            std::snprintf(notice, sizeof notice, tr(Text::pin_incorreto_aguarde_llu_s_061),
                          static_cast<unsigned long long>(countdown_second_));
            view_.splash(notice);
            break;
        }
        case FirstLoginPage::Main:
            view_.menu(tr(Text::main_062),
                       {tr(Text::minhas_carteiras_063), tr(Text::configuracoes_064)});
            break;
        case FirstLoginPage::Home:
        case FirstLoginPage::DeleteWallet:
        case FirstLoginPage::PhraseWallet: {
            std::vector<std::string> names;
            for (const auto& p : profiles_)
                names.push_back(p.name);
            if (profiles_.empty())
                view_.text(tr(Text::nenhuma_carteira_065),
                           tr(Text::crie_ou_importe_uma_wallet_em_066), tr(Text::voltar_067), false,
                           false);
            else
                view_.menu(page == FirstLoginPage::Home ? tr(Text::minhas_carteiras_063)
                           : page == FirstLoginPage::DeleteWallet
                               ? tr(Text::deletar_wallet_068)
                               : tr(Text::editar_transactionphrase_069),
                           names, true);
            break;
        }
        case FirstLoginPage::Balances:
            view_.menu(tr(Text::meus_saldos_070),
                       {tr(Text::saldo_por_moeda_071), tr(Text::saldo_por_rede_072)}, true);
            break;
        case FirstLoginPage::BalanceCoin:
        case FirstLoginPage::BalanceNetwork:
            view_.text(page == FirstLoginPage::BalanceCoin ? tr(Text::saldo_por_moeda_071)
                                                           : tr(Text::saldo_por_rede_072),
                       tr(Text::saldos_ainda_nao_sincronizados_pelo_mobile_073),
                       tr(Text::voltar_067), false, false);
            break;
        case FirstLoginPage::Settings:
            view_.menu(tr(Text::configuracoes_064),
                       {tr(Text::criar_wallet_004), tr(Text::importar_wallet_005),
                        tr(Text::deletar_wallet_068), tr(Text::editar_pin_074),
                        tr(Text::editar_transactionphrase_069), tr(Text::languages),
                        tr(Text::edit_device_name), tr(Text::monero_view_setup),
                        tr(Text::devices_title)},
                       true);
            break;
        case FirstLoginPage::MoneroWallet: {
            std::vector<std::string> names;
            for (const auto& wallet : profiles_)
                names.push_back(wallet.name);
            view_.menu(tr(Text::monero_view_setup), names, true, "Monero");
            break;
        }
        case FirstLoginPage::MoneroConfig: {
            const auto details =
                profiles_[monero_wallet_index_].name + "\n\n" +
                (error ? error
                       : tr(monero_enabled_ ? Text::monero_enabled : Text::monero_disabled));
            view_.text(tr(Text::monero_view_setup), details.c_str(),
                       tr(monero_enabled_ ? Text::monero_disable : Text::monero_enable), false,
                       true, tr(Text::voltar_067), "Monero");
            break;
        }
        case FirstLoginPage::AuthenticatePin:
            view_.input(tr(Text::confirme_o_pin_atual_075),
                        error ? error : tr(Text::digite_o_pin_do_aparelho_duas_076), true, false,
                        false);
            break;
        case FirstLoginPage::OldPhrase: {
            const auto index = gathering_phrases_ ? phrase_target_ : wallet_index_;
            const auto title = std::string(tr(Text::frase_atual_077)) + profiles_[index].name;
            view_.input(title.c_str(),
                        error ? error
                              : tr(Text::digite_a_transactionphrase_atual_desta_carteira_078),
                        true);
            break;
        }
        case FirstLoginPage::CommonPhrase:
            view_.input(tr(Text::transactionphrase_comum_079),
                        error ? error : tr(Text::digite_a_frase_ja_usada_em_080), true);
            break;
        case FirstLoginPage::SeedNotice:
            view_.text(tr(Text::guarde_suas_palavras_081),
                       tr(Text::as_palavras_seed_serao_exibidas_somente_082),
                       tr(Text::entendi_mostrar_083), false, true, tr(Text::cancelar_058));
            break;
        case FirstLoginPage::DeleteConfirm: {
            const auto message = std::string(tr(Text::carteira_084)) +
                                 profiles_[wallet_index_].name +
                                 tr(Text::excluir_esta_carteira_do_aparelho_para_085);
            view_.text(tr(Text::confirmar_exclusao_086), message.c_str(),
                       tr(Text::excluir_esta_wallet_087), false, true, tr(Text::cancelar_058));
            break;
        }
        case FirstLoginPage::DeleteDone:
            view_.text(tr(Text::wallet_excluida_088),
                       tr(Text::a_carteira_selecionada_foi_removida_as_089), tr(Text::concluir_090),
                       false, false);
            break;
        case FirstLoginPage::Pending:
            view_.text(tr(Text::em_breve_091),
                       tr(Text::esta_opcao_sera_implementada_posteriormente_092),
                       tr(Text::voltar_067), false, false);
            break;
        case FirstLoginPage::Success:
            view_.text(tr(Text::wallet_configurada_093),
                       tr(Text::sua_carteira_foi_salva_de_forma_094), tr(Text::concluir_090), false,
                       false);
            break;
        case FirstLoginPage::Ready: {
            auto description = profile_.name + "\n\n" + std::to_string(profile_.word_count) +
                               tr(Text::palavras_095);
            if (profile_.passphrase_enabled)
                description += tr(Text::passphrase_ativa_096);
            if (profile_.pin_enabled)
                description += tr(Text::pin_de_desbloqueio_ativo_097);
            if (profile_.mobile_access == MobileAccess::Pin)
                description += tr(Text::celular_pin_obrigatorio_098);
            description += tr(Text::frase_de_transacao_obrigatoria_099);
            view_.text(tr(Text::infos_wallet_100), description.c_str(), tr(Text::ver_enderecos_101),
                       false, profile_.pin_enabled, tr(Text::bloquear_102));
            break;
        }
        case FirstLoginPage::AddressPhrase:
            view_.input(tr(Text::ver_enderecos_101),
                        error ? error : tr(Text::digite_a_frase_de_transacao_para_103), true);
            break;
        case FirstLoginPage::AddressList: {
            std::vector<std::string> names, icons;
            for (const auto& network : address_catalog) {
                names.emplace_back(network.label);
                icons.emplace_back(network.firmware_name);
            }
            view_.menu(wallet_index_ < profiles_.size() ? profiles_[wallet_index_].name.c_str()
                                                        : tr(Text::enderecos_mainnet_104),
                       names, true, nullptr, icons);
            break;
        }
        case FirstLoginPage::AddressNetwork: {
            const auto& network = address_catalog[address_index_];
            std::vector<std::string> names{std::string(network.native)}, icons = names;
            for (const auto& token : network.tokens) {
                if (token.symbol.empty())
                    break;
                icons.emplace_back(token.symbol);
                names.push_back(token.pending
                                    ? std::string(token.symbol) + " - " + tr(Text::em_breve_091)
                                    : std::string(token.symbol));
            }
            view_.menu(network.label.data(), names, true, network.native.data(), icons);
            break;
        }
        case FirstLoginPage::AddressDetail: {
            const auto& network = address_catalog[address_index_];
            const auto token = address_asset_index_ == 0
                                   ? AddressCatalogToken{network.native, false}
                                   : network.tokens[address_asset_index_ - 1];
            const auto address = find_address(addresses_, network.firmware_name);
            if (token.pending || address == addresses_.end()) {
                const auto heading = address_asset_index_ == 0 ? network.label : token.symbol;
                const auto message = token.pending
                                         ? Text::esta_opcao_sera_implementada_posteriormente_092
                                         : Text::nao_foi_possivel_gerar_os_enderecos_109;
                view_.text(heading.data(), tr(message), tr(Text::voltar_067), false, false, nullptr,
                           token.symbol.data());
            } else {
                const auto* heading =
                    address_asset_index_ == 0 ? address->network.c_str() : token.symbol.data();
                view_.public_address(heading, address->value.c_str(), token.symbol.data());
            }
            break;
        }
        case FirstLoginPage::Devices: {
            access_devices_ =
                signing_device_ ? signing_device_->trusted_devices() : std::vector<TrustedDevice>{};
            access_revision_ = signing_device_ ? signing_device_->devices_revision() : 0;
            std::vector<std::string> items;
            for (const auto& d : access_devices_)
                items.push_back(d.id + "\n" +
                                tr(d.blocked     ? Text::device_blocked
                                   : d.connected ? Text::device_connected
                                                 : Text::device_saved));
            if (items.empty())
                view_.text(tr(Text::devices_title), tr(Text::devices_empty), tr(Text::voltar_067),
                           false, false);
            else
                view_.menu(tr(Text::devices_title), items, true);
            break;
        }
        case FirstLoginPage::DeviceActions: {
            const auto entry =
                std::find_if(access_devices_.begin(), access_devices_.end(),
                             [&](const auto& d) { return d.id == selected_device_id_; });
            std::vector<std::string> actions{tr(Text::device_delete)};
            if (entry != access_devices_.end() && !entry->blocked)
                actions.push_back(tr(Text::device_block));
            view_.menu(selected_device_id_.c_str(), actions, true);
            break;
        }
        case FirstLoginPage::DeviceDelete:
        case FirstLoginPage::DeviceBlock: {
            const auto description =
                selected_device_id_ + "\n\n" +
                tr(page == FirstLoginPage::DeviceDelete ? Text::device_delete_hint
                                                        : Text::device_block_hint);
            view_.text(
                tr(page == FirstLoginPage::DeviceDelete ? Text::device_delete : Text::device_block),
                error ? error : description.c_str(),
                tr(page == FirstLoginPage::DeviceDelete ? Text::device_delete : Text::device_block),
                false, true);
            break;
        }
        case FirstLoginPage::MobileApproval: {
            std::string description = mobile_identity_;
            for (const auto& wallet : profiles_)
                description += "\n" + wallet.name;
            if (mobile_code_) {
                char code[8];
                std::snprintf(code, sizeof code, "%06u", static_cast<unsigned>(*mobile_code_));
                description += "\n\n" + std::string(tr(Text::mobile_pair_code)) + "\n" + code;
            }
            view_.approval(tr(Text::mobile_approval_body), description.c_str(),
                           tr(Text::mobile_accept), tr(Text::mobile_reject));
            break;
        }
        case FirstLoginPage::Fault:
            break;
    }
}
void FirstLogin::show_public_sync(bool active) {
    const bool visible = active && mobile_allowed() && page_ != FirstLoginPage::MobileApproval &&
                         signing_.stage() == SignStage::Idle;
    if (visible)
        view_.show_progress(tr(Text::sync_in_progress));
    else if (sync_visible_)
        view_.hide_progress();
    sync_visible_ = visible;
}
bool FirstLogin::prompt_mobile(std::uint64_t token, std::string_view identity,
                               std::optional<std::uint32_t> code) {
    if (!mobile_allowed() || page_ == FirstLoginPage::Busy ||
        page_ == FirstLoginPage::MobileApproval || edit_ != Edit::None || request_)
        return false;
    if (signing_.stage() != SignStage::Idle)
        return false;
    mobile_return_ = page_;
    mobile_token_ = token;
    mobile_identity_ = identity;
    mobile_code_ = code;
    render(FirstLoginPage::MobileApproval);
    return true;
}
std::optional<std::pair<std::uint64_t, bool>> FirstLogin::take_mobile_decision() {
    auto decision = mobile_decision_;
    mobile_decision_.reset();
    return decision;
}
void FirstLogin::cancel_mobile() {
    if (page_ == FirstLoginPage::MobileApproval)
        render(mobile_return_);
    mobile_token_ = 0;
    mobile_decision_.reset();
}
bool FirstLogin::submit(std::unique_ptr<SetupJob> job) {
    job->token = ++token_;
    if (!service_.submit(std::move(job))) {
        fault(tr(Text::nao_foi_possivel_processar_no_aparelho_105));
        return false;
    }
    render(FirstLoginPage::Busy);
    return true;
}
void FirstLogin::generate_words() {
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::GenerateWords;
    job->request = std::move(request_);
    submit(std::move(job));
}
void FirstLogin::prepare() {
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::Prepare;
    if (additional_) {
        job->work = SetupWork::PrepareAdditional;
        copy_session(*job);
    } else
        startup_pin_.assign(request_->pin.view());
    if (additional_ && apply_phrase_to_all_)
        pending_shared_phrase_.assign(request_->transaction_phrase.view());
    job->request = std::move(request_);
    first_.clear();
    submit(std::move(job));
}
void FirstLogin::commit() {
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::Commit;
    job->shared = apply_phrase_to_all_;
    if (additional_) {
        job->work = SetupWork::Append;
        copy_session(*job);
        if (apply_phrase_to_all_) {
            job->work = profile_.shared_transaction_phrase && !profiles_.empty()
                            ? SetupWork::AppendReuse
                            : SetupWork::AppendShared;
            job->phrases = std::move(phrase_proofs_);
            job->new_phrase.assign(pending_shared_phrase_.view());
        }
    }
    pending_shared_phrase_.clear();
    job->record = candidate_->record;
    candidate_->backup_words.clear();
    candidate_->backup_passphrase.clear();
    submit(std::move(job));
}
void FirstLogin::unlock(std::string_view pin) {
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::Unlock;
    job->pin.assign(pin);
    job->now = now_;
    submit(std::move(job));
}
void FirstLogin::request_addresses(std::string_view phrase) {
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::ReceivingAddresses;
    job->wallet_index = wallet_index_;
    job->transaction_phrase.assign(phrase);
    if (session_ && session_->authorized) {
        job->address_session.pin_key.value = session_->pin_key.value;
        job->address_session.authorized = true;
    }
    submit(std::move(job));
}
void FirstLogin::copy_session(SetupJob& job) {
    if (session_ && session_->authorized) {
        job.address_session.pin_key.value = session_->pin_key.value;
        job.address_session.authorized = true;
    }
}
void FirstLogin::lock(const char* error) {
    session_.reset();
    request_.reset();
    candidate_.reset();
    first_.clear();
    old_phrase_.clear();
    startup_pin_.clear();
    pending_shared_phrase_.clear();
    phrase_proofs_.clear();
    apply_phrase_to_all_ = gathering_phrases_ = false;
    addresses_.clear();
    resume_address_detail_ = false;
    edit_ = Edit::None;
    additional_ = false;
    auto_unlock_ = false;
    unlock_target_ = FirstLoginPage::Main;
    render(FirstLoginPage::Unlock, error);
}
void FirstLogin::begin_edit() {
    request_ = std::make_unique<SetupRequest>();
    if (edit_ == Edit::Pin)
        render(FirstLoginPage::PinChoice);
    else {
        phrase_proofs_.clear();
        old_phrase_.clear();
        gathering_phrases_ = false;
        apply_phrase_to_all_ = profile_.shared_transaction_phrase;
        phrase_target_ = wallet_index_;
        render(FirstLoginPage::OldPhrase);
    }
}
void FirstLogin::after_seed_credentials() {
    render(additional_ ? FirstLoginPage::TransactionIntro : FirstLoginPage::PinChoice);
}
void FirstLogin::change_pin() {
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::ChangePin;
    copy_session(*job);
    job->pin.assign(request_->pin.view());
    job->mobile_access = request_->mobile_access;
    submit(std::move(job));
}
void FirstLogin::verify_phrase(std::string_view phrase) {
    old_phrase_.assign(phrase);
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::VerifyPhrase;
    copy_session(*job);
    job->wallet_index = gathering_phrases_ ? phrase_target_ : wallet_index_;
    job->transaction_phrase.assign(phrase);
    submit(std::move(job));
}
void FirstLogin::verify_common(std::string_view phrase) {
    request_->transaction_phrase.assign(phrase);
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::VerifyCommon;
    copy_session(*job);
    job->transaction_phrase.assign(phrase);
    submit(std::move(job));
}
void FirstLogin::change_phrase() {
    if (apply_phrase_to_all_ && profiles_.size() > 1 && !gathering_phrases_) {
        phrase_proofs_.clear();
        auto proof = std::make_unique<WalletPhrase>();
        proof->index = wallet_index_;
        proof->current.assign(old_phrase_.view());
        old_phrase_.clear();
        phrase_proofs_.push_back(std::move(proof));
        gathering_phrases_ = true;
        phrase_target_ = 0;
        if (phrase_target_ == wallet_index_)
            ++phrase_target_;
        phrase_errors_ = 0;
        render(FirstLoginPage::OldPhrase);
        return;
    }
    auto job = std::make_unique<SetupJob>();
    copy_session(*job);
    job->new_phrase.assign(request_->transaction_phrase.view());
    job->shared = apply_phrase_to_all_;
    if (apply_phrase_to_all_ && profiles_.size() > 1) {
        job->work = SetupWork::ChangePhrases;
        job->phrases = std::move(phrase_proofs_);
    } else {
        job->work = SetupWork::ChangePhrase;
        job->wallet_index = wallet_index_;
        job->transaction_phrase.assign(old_phrase_.view());
    }
    old_phrase_.clear();
    submit(std::move(job));
}
void FirstLogin::finish_phrase_creation() {
    if (edit_ == Edit::Phrase) {
        change_phrase();
        return;
    }
    if (additional_ && apply_phrase_to_all_ && !profiles_.empty()) {
        phrase_proofs_.clear();
        old_phrase_.clear();
        gathering_phrases_ = true;
        phrase_target_ = 0;
        phrase_errors_ = 0;
        render(FirstLoginPage::OldPhrase);
    } else
        prepare();
}
void FirstLogin::delete_wallet() {
    auto job = std::make_unique<SetupJob>();
    job->work = SetupWork::DeleteWallet;
    copy_session(*job);
    job->wallet_index = wallet_index_;
    job->expected_id = profiles_[wallet_index_].record_id;
    job->transaction_phrase.assign(old_phrase_.view());
    old_phrase_.clear();
    submit(std::move(job));
}
void FirstLogin::tick(std::uint64_t now_ms) {
    if (signing_.stage() != SignStage::Idle) {
        now_ = now_ms;
        return;
    }
    now_ = now_ms;
    if (page_ == FirstLoginPage::Splash && now_ms >= splash_until_)
        after_splash();
    if (page_ == FirstLoginPage::Cooldown) {
        if (now_ms >= retry_until_)
            render(cooldown_target_);
        else if ((retry_until_ - now_ms + 999) / 1000 != countdown_second_)
            render(FirstLoginPage::Cooldown);
    }
    auto result = service_.poll();
    if (!result || result->token != token_ || page_ != FirstLoginPage::Busy)
        return;
    if (result->error != VaultError::None) {
        if (result->work == SetupWork::Open && result->error == VaultError::Missing) {
            render(FirstLoginPage::Choice);
        } else if (result->work == SetupWork::Unlock &&
                   (result->error == VaultError::WrongPin || result->error == VaultError::Locked)) {
            cooldown_target_ = FirstLoginPage::Unlock;
            if (edit_ != Edit::None && result->error == VaultError::WrongPin && ++auth_errors_ < 2)
                cooldown_target_ = FirstLoginPage::AuthenticatePin;
            else if (edit_ != Edit::None) {
                lock();
            }
            session_.reset();
            retry_until_ = now_ms + result->retry_ms;
            render(FirstLoginPage::Cooldown);
        } else if (result->work == SetupWork::VerifyPhrase &&
                   result->error == VaultError::WrongPhrase) {
            old_phrase_.clear();
            if (++phrase_errors_ >= 2)
                lock(tr(Text::frase_incorreta_duas_vezes_desbloqueie_novamente_106));
            else
                render(FirstLoginPage::OldPhrase,
                       tr(Text::frase_incorreta_resta_uma_tentativa_107));
        } else if (result->work == SetupWork::VerifyCommon &&
                   result->error == VaultError::WrongPhrase) {
            request_->transaction_phrase.clear();
            pending_shared_phrase_.clear();
            if (++phrase_errors_ >= 2)
                lock(tr(Text::frase_incorreta_duas_vezes_desbloqueie_novamente_106));
            else
                render(FirstLoginPage::CommonPhrase,
                       tr(Text::frase_incorreta_resta_uma_tentativa_107));
        } else if (result->work == SetupWork::PublicAddresses) {
            render(FirstLoginPage::AddressPhrase);
        } else if (result->work == SetupWork::ReceivingAddresses) {
            render(FirstLoginPage::AddressPhrase,
                   result->error == VaultError::WrongPhrase
                       ? tr(Text::frase_de_transacao_incorreta_tente_novamente_108)
                       : tr(Text::nao_foi_possivel_gerar_os_enderecos_109));
        } else
            fault(tr(Text::a_configuracao_nao_foi_concluida_confira_110));
        return;
    }
    if (result->work == SetupWork::Open) {
        profiles_ = std::move(result->profiles);
        profile_ = profiles_.empty() ? result->device_profile : profiles_.front();
        if (result->retry_ms) {
            session_.reset();
            cooldown_target_ = FirstLoginPage::Unlock;
            retry_until_ = now_ms + result->retry_ms;
            render(FirstLoginPage::Cooldown);
        } else if (session_ && session_->authorized) {
            if (!profile_.pin_policy_current) {
                upgrading_pin_ = true;
                edit_ = Edit::Pin;
                begin_edit();
            } else
                render(unlock_target_);
        } else if (auto_unlock_) {
            auto_unlock_ = false;
            unlock(startup_pin_.view());
            startup_pin_.clear();
        } else if (profile_.pin_enabled)
            render(FirstLoginPage::Unlock);
        else
            unlock("");
    } else if (result->work == SetupWork::DeleteWallet) {
        edit_ = Edit::None;
        old_phrase_.clear();
        request_.reset();
        first_.clear();
        addresses_.clear();
        phrase_proofs_.clear();
        render(FirstLoginPage::DeleteDone);
    } else if (result->work == SetupWork::VerifyCommon) {
        prepare();
    } else if (result->work == SetupWork::VerifyPhrase) {
        if (edit_ == Edit::Delete)
            render(FirstLoginPage::DeleteConfirm);
        else if (gathering_phrases_) {
            auto proof = std::make_unique<WalletPhrase>();
            proof->index = phrase_target_;
            proof->current.assign(old_phrase_.view());
            old_phrase_.clear();
            phrase_proofs_.push_back(std::move(proof));
            do {
                ++phrase_target_;
            } while (edit_ == Edit::Phrase && phrase_target_ == wallet_index_);
            if (phrase_target_ >= profiles_.size()) {
                if (edit_ == Edit::Phrase)
                    change_phrase();
                else
                    prepare();
            } else {
                phrase_errors_ = 0;
                render(FirstLoginPage::OldPhrase);
            }
        } else
            render(FirstLoginPage::TransactionIntro);
    } else if (result->work == SetupWork::ChangePin || result->work == SetupWork::ChangePhrase ||
               result->work == SetupWork::ChangePhrases) {
        upgrading_pin_ = false;
        edit_ = Edit::None;
        request_.reset();
        old_phrase_.clear();
        first_.clear();
        phrase_proofs_.clear();
        apply_phrase_to_all_ = gathering_phrases_ = false;
        unlock_target_ = FirstLoginPage::Home;
        after_splash();
    } else if (result->work == SetupWork::GenerateWords) {
        request_ = std::move(result->request);
        if (!request_) {
            fault(tr(Text::nao_foi_possivel_gerar_as_palavras_111));
            return;
        }
        render(FirstLoginPage::WordReview);
    } else if (result->work == SetupWork::Prepare || result->work == SetupWork::PrepareAdditional) {
        candidate_ = std::move(result->candidate);
        if (!candidate_) {
            fault(tr(Text::nao_foi_possivel_preparar_a_carteira_112));
            return;
        }
        render(FirstLoginPage::Backup);
    } else if (result->work == SetupWork::Commit || result->work == SetupWork::Append ||
               result->work == SetupWork::AppendShared || result->work == SetupWork::AppendReuse) {
        profile_ = candidate_->profile;
        candidate_.reset();
        request_.reset();
        first_.clear();
        render(FirstLoginPage::Success);
    } else if (result->work == SetupWork::ReceivingAddresses ||
               result->work == SetupWork::PublicAddresses) {
        addresses_ = std::move(result->addresses);
        const bool show_detail =
            resume_address_detail_ && result->work == SetupWork::ReceivingAddresses;
        resume_address_detail_ = false;
        render(show_detail ? FirstLoginPage::AddressDetail : FirstLoginPage::AddressList);
    } else {
        session_ = std::move(result->session);
        if (!session_ || !session_->authorized) {
            fault(tr(Text::nao_foi_possivel_desbloquear_113));
            return;
        }
        profiles_ = std::move(result->profiles);
        profile_ = profiles_.empty() ? result->device_profile : profiles_.front();
        if (!profile_.pin_policy_current) {
            upgrading_pin_ = true;
            edit_ = Edit::Pin;
            begin_edit();
        } else if (edit_ != Edit::None)
            begin_edit();
        else
            render(address_after_unlock_ ? FirstLoginPage::AddressPhrase : unlock_target_);
        address_after_unlock_ = false;
    }
}
void FirstLogin::back() {
    if (upgrading_pin_ &&
        (page_ == FirstLoginPage::PinChoice || page_ == FirstLoginPage::PinEntry ||
         page_ == FirstLoginPage::PinConfirm || page_ == FirstLoginPage::MobilePolicy)) {
        first_.clear();
        render(FirstLoginPage::PinChoice);
        return;
    }
    switch (page_) {
        case FirstLoginPage::MoneroWallet:
            render(FirstLoginPage::Settings);
            break;
        case FirstLoginPage::MoneroConfig:
            render(FirstLoginPage::MoneroWallet);
            break;
        case FirstLoginPage::DeviceName:
            if (editing_device_name_) {
                editing_device_name_ = false;
                render(FirstLoginPage::Settings);
            } else {
                initial_language_choice_ = true;
                render(FirstLoginPage::Language);
            }
            break;
        case FirstLoginPage::Devices:
            render(FirstLoginPage::Settings);
            break;
        case FirstLoginPage::DeviceActions:
            render(FirstLoginPage::Devices);
            break;
        case FirstLoginPage::DeviceDelete:
        case FirstLoginPage::DeviceBlock:
            render(FirstLoginPage::DeviceActions);
            break;
        case FirstLoginPage::Language:
            if (!initial_language_choice_)
                render(FirstLoginPage::Settings);
            break;
        case FirstLoginPage::SeedNotice:
            if (seed_target_ == FirstLoginPage::Backup) {
                candidate_.reset();
                request_.reset();
                first_.clear();
                startup_pin_.clear();
                pending_shared_phrase_.clear();
                phrase_proofs_.clear();
                apply_phrase_to_all_ = gathering_phrases_ = false;
                render(additional_ ? FirstLoginPage::Settings : FirstLoginPage::Choice);
                additional_ = false;
            } else if (request_->imported) {
                --import_index_;
                pop_word(request_->mnemonic);
                render(FirstLoginPage::ImportWord);
            } else
                render(FirstLoginPage::WordCount);
            break;
        case FirstLoginPage::Backup:
            candidate_.reset();
            request_.reset();
            first_.clear();
            startup_pin_.clear();
            pending_shared_phrase_.clear();
            phrase_proofs_.clear();
            apply_phrase_to_all_ = gathering_phrases_ = false;
            render(additional_ ? FirstLoginPage::Settings : FirstLoginPage::Choice);
            additional_ = false;
            break;
        case FirstLoginPage::Name:
            request_.reset();
            render(additional_ ? FirstLoginPage::Settings : FirstLoginPage::Choice);
            additional_ = false;
            break;
        case FirstLoginPage::WordCount:
            render(FirstLoginPage::Name);
            break;
        case FirstLoginPage::PassChoice:
            render(FirstLoginPage::WordReview);
            break;
        case FirstLoginPage::WordReview:
            if (request_->imported) {
                --import_index_;
                pop_word(request_->mnemonic);
                render(FirstLoginPage::ImportWord);
            } else
                render(FirstLoginPage::WordCount);
            break;
        case FirstLoginPage::PinChoice:
            if (edit_ == Edit::Pin) {
                edit_ = Edit::None;
                request_.reset();
                render(FirstLoginPage::Settings);
            } else
                render(FirstLoginPage::PassChoice);
            break;
        case FirstLoginPage::ImportWord:
            if (import_index_) {
                --import_index_;
                pop_word(request_->mnemonic);
                render(FirstLoginPage::ImportWord);
            } else
                render(FirstLoginPage::WordCount);
            break;
        case FirstLoginPage::PassEntry:
            first_.clear();
            render(FirstLoginPage::PassChoice);
            break;
        case FirstLoginPage::PassConfirm:
            first_.clear();
            render(FirstLoginPage::PassEntry);
            break;
        case FirstLoginPage::PinEntry:
            first_.clear();
            render(FirstLoginPage::PinChoice);
            break;
        case FirstLoginPage::PinConfirm:
            first_.clear();
            render(FirstLoginPage::PinEntry);
            break;
        case FirstLoginPage::MobilePolicy:
            render(FirstLoginPage::PinChoice);
            break;
        case FirstLoginPage::TransactionIntro:
            if (edit_ == Edit::Phrase) {
                old_phrase_.clear();
                render(FirstLoginPage::OldPhrase);
            } else if (additional_)
                render(FirstLoginPage::PassChoice);
            else
                render(request_->pin.view().empty() ? FirstLoginPage::PinChoice
                                                    : FirstLoginPage::MobilePolicy);
            break;
        case FirstLoginPage::TransactionEntry:
            first_.clear();
            render(FirstLoginPage::TransactionIntro);
            break;
        case FirstLoginPage::TransactionConfirm:
            first_.clear();
            render(FirstLoginPage::TransactionEntry);
            break;
        case FirstLoginPage::Home:
        case FirstLoginPage::Balances:
        case FirstLoginPage::Settings:
            render(FirstLoginPage::Main);
            break;
        case FirstLoginPage::BalanceCoin:
        case FirstLoginPage::BalanceNetwork:
            render(FirstLoginPage::Balances);
            break;
        case FirstLoginPage::DeleteWallet:
        case FirstLoginPage::PhraseWallet:
            render(FirstLoginPage::Settings);
            break;
        case FirstLoginPage::CommonPhrase:
        case FirstLoginPage::DeleteConfirm:
        case FirstLoginPage::OldPhrase:
        case FirstLoginPage::AuthenticatePin:
            old_phrase_.clear();
            pending_shared_phrase_.clear();
            phrase_proofs_.clear();
            apply_phrase_to_all_ = gathering_phrases_ = false;
            edit_ = Edit::None;
            request_.reset();
            if (!session_)
                lock();
            else
                render(FirstLoginPage::Settings);
            break;
        case FirstLoginPage::Pending:
            render(pending_return_);
            break;
        case FirstLoginPage::Unlock:
            first_.clear();
            render(address_after_unlock_ ? FirstLoginPage::Ready : FirstLoginPage::Unlock);
            address_after_unlock_ = false;
            break;
        case FirstLoginPage::AddressPhrase:
            if (resume_address_detail_) {
                resume_address_detail_ = false;
                render(address_detail_parent_);
            } else
                render(FirstLoginPage::Home);
            break;
        case FirstLoginPage::AddressList:
            addresses_.clear();
            render(FirstLoginPage::Home);
            break;
        case FirstLoginPage::AddressNetwork:
            render(FirstLoginPage::AddressList);
            break;
        case FirstLoginPage::AddressDetail:
            render(address_detail_parent_);
            break;
        default:
            break;
    }
}
void FirstLogin::end_signing(SignOutcome outcome, std::string_view error) {
    if (signing_device_)
        signing_device_->respond_transaction(std::move(outcome), error);
    else
        wipe(outcome.encoded.data(), outcome.encoded.size());
    signing_monero_.reset();
    signing_phrase_ = false;
    signing_payment_symbol_.clear();
    render(signing_return_);
}
void FirstLogin::submit_signing() {
    if (auto job = signing_.confirm(signing_.token(), now_)) {
        const auto token = job->token;
        if (signing_monero_) {
            view_.status(tr(Text::monero_view_setup),
                         tr(*signing_monero_ == MoneroOperation::WatchWallet
                                ? Text::monero_preparing_view_key
                            : *signing_monero_ == MoneroOperation::ReceiveAddress
                                ? Text::monero_preparing_receive_address
                                : Text::signing_in_progress),
                         false, "Monero");
        } else
            view_.show_progress(tr(Text::signing_in_progress));
        if (!signing_device_ || !signing_device_->sign(std::move(*job))) {
            if (auto result = signing_.completed(token, signing_connection_, {}, false))
                end_signing(std::move(*result), "SIGNING_BUSY");
        }
    }
}
void FirstLogin::poll_signing(DeviceService& device, std::uint64_t now) {
    signing_device_ = &device;
    if (page_ == FirstLoginPage::Devices && access_revision_ != device.devices_revision())
        render(FirstLoginPage::Devices);
    if (signing_.stage() != SignStage::Idle) {
        if (!mobile_allowed() || !device.connected(signing_connection_)) {
            if (auto result = signing_.disconnect(signing_connection_))
                end_signing(std::move(*result));
        } else if (auto expired = signing_.poll(now))
            end_signing(std::move(*expired));
        if (auto result = device.poll_signature()) {
            if (auto completed = signing_.completed(result->token, result->connection,
                                                    std::move(result->encoded), result->success))
                end_signing(std::move(*completed), result->error);
        }
        return;
    }
    auto prompt = device.poll_transaction();
    if (!prompt)
        return;
    if (!mobile_allowed() ||
        (page_ != FirstLoginPage::Main && page_ != FirstLoginPage::Home &&
         page_ != FirstLoginPage::Balances && page_ != FirstLoginPage::Ready)) {
        device.respond_transaction({{}, prompt->request.connection, SignEnd::Rejected, {}}, {});
        return;
    }
    signing_return_ = page_;
    signing_connection_ = prompt->request.connection;
    const auto format_amount = [&](std::string amount, std::string_view symbol = {}) {
        unsigned decimals = prompt->decimals;
        if (!symbol.empty()) {
            if (symbol == "ETH" || symbol == "BNB" || symbol == "POL" || symbol == "AVAX" ||
                symbol == "ETC" || symbol == "HYPE")
                decimals = 18;
            else if (symbol == "SOL" || symbol == "TON" || symbol == "SUI")
                decimals = 9;
            else if (symbol == "ADA" || symbol == "TRX" || symbol == "XRP")
                decimals = 6;
            else if (symbol == "XLM")
                decimals = 7;
        }
        if (decimals) {
            if (amount.size() <= decimals)
                amount.insert(0, decimals + 1 - amount.size(), '0');
            amount.insert(amount.size() - decimals, 1, '.');
            while (amount.back() == '0')
                amount.pop_back();
            if (amount.back() == '.')
                amount.pop_back();
        }
        amount += " ";
        amount += symbol.empty() ? std::string_view(prompt->symbol) : symbol;
        return amount;
    };
    if (const auto operation = prompt->request.monero) {
        const char* mode =
            prompt->request.monero_mode == PublicWalletMode::Standard ? "STANDARD" : "HIDDEN";
        const auto message =
            tr(*operation == MoneroOperation::WatchWallet      ? Text::monero_export_view
               : *operation == MoneroOperation::ReceiveAddress ? Text::monero_export_receive_address
                                                               : Text::monero_sign_outputs);
        const bool transfer = *operation == MoneroOperation::SignTransfer;
        std::string body = prompt->coin + "\n" + mode + "\n\n";
        prompt->request.review = {{prompt->coin, mode}};
        if (transfer) {
            body += "Monero (XMR) · Stagenet\n\n" + format_amount(prompt->amount) + "\n\n";
            prompt->request.review.push_back({tr(Text::transfer_recipient), prompt->recipient});
            prompt->request.review.push_back({tr(Text::transfer_fee), format_amount(prompt->fee)});
            for (std::size_t i = 1; i < prompt->request.review.size(); ++i) {
                const auto& field = prompt->request.review[i];
                body += field.label + "\n" + field.value + "\n\n";
            }
        } else
            body += message;
        if (!signing_.begin(std::move(prompt->request), now)) {
            device.respond_transaction({{}, signing_connection_, SignEnd::Failed, {}},
                                       "MONERO_FAILED");
            return;
        }
        signing_monero_ = operation;
        signing_.mark_reviewed(signing_.token());
        view_.approval(tr(transfer ? Text::monero_transfer_review : Text::monero_view_setup),
                       body.c_str(), tr(Text::mobile_accept), tr(Text::mobile_reject), "Monero",
                       transfer);
        return;
    }
    const auto amount = format_amount(prompt->amount);
    auto& review = prompt->request.review;
    review = {{std::string(tr(Text::transfer_amount)) + " (" + prompt->coin + ")", amount},
              {tr(Text::transfer_recipient), prompt->recipient},
              {tr(Text::transfer_fee), format_amount(prompt->fee, prompt->fee_symbol)}};
    if (!prompt->native_amount.empty())
        review.push_back({std::string(tr(Text::transfer_amount)) + " (" + prompt->fee_symbol + ")",
                          format_amount(prompt->native_amount, prompt->fee_symbol)});
    if (!prompt->change.empty())
        review.push_back(
            {tr(Text::transfer_change), format_amount(prompt->change, prompt->fee_symbol)});
    if (prompt->request.local_phrase)
        review.insert(review.begin(), {prompt->wallet_name, prompt->mode});
    if (!prompt->change_address.empty())
        review.push_back({tr(Text::transfer_change_address), prompt->change_address});
    const auto local_phrase = prompt->request.local_phrase;
    std::string body;
    for (const auto& field : review)
        body += field.label + "\n" + field.value + "\n\n";
    if (!signing_.begin(std::move(prompt->request), now)) {
        device.respond_transaction({{}, signing_connection_, SignEnd::Failed, {}}, {});
        return;
    }
    signing_.mark_reviewed(signing_.token());
    if (local_phrase)
        signing_payment_symbol_ = prompt->symbol;
    view_.approval(tr(Text::transfer_signature_request), body.c_str(), tr(Text::mobile_accept),
                   tr(Text::mobile_reject),
                   prompt->symbol == "POL" || prompt->symbol == "SOL" || prompt->symbol == "ADA" ||
                           prompt->symbol == "ETH"
                       ? prompt->coin.c_str()
                       : "",
                   local_phrase);
}
void FirstLogin::consume() {
    auto event = view_.take_event();
    if (!event || event->screen != view_.screen_id())
        return;
    if (signing_.stage() != SignStage::Idle) {
        if (signing_.stage() == SignStage::Review) {
            const bool local_payment = !signing_payment_symbol_.empty();
            const auto* phrase_title =
                local_payment ? signing_payment_symbol_.c_str() : tr(Text::monero_view_setup);
            const auto* phrase_icon = local_payment ? "" : "Monero";
            if (event->action == TouchAction::Confirm && !signing_phrase_) {
                if (signing_monero_ || local_payment) {
                    signing_phrase_ = true;
                    view_.input(phrase_title,
                                tr(Text::digite_a_transactionphrase_atual_desta_carteira_078), true,
                                false, false, phrase_icon);
                } else
                    submit_signing();
            } else if (event->action == TouchAction::Submit && signing_phrase_) {
                if (valid_transaction_phrase(event->value.view()) &&
                    signing_.set_local_phrase(event->value.view()))
                    submit_signing();
                else
                    view_.input(phrase_title, tr(Text::digite_a_frase_de_transacao_da_125), true,
                                false, false, phrase_icon);
            } else if (event->action == TouchAction::Back || event->action == TouchAction::Cancel) {
                if (auto result = signing_.reject())
                    end_signing(std::move(*result));
            }
        }
        return;
    }

    if (page_ == FirstLoginPage::MobileApproval) {
        if (event->action == TouchAction::Confirm || event->action == TouchAction::Cancel ||
            event->action == TouchAction::Back) {
            mobile_decision_ = std::make_pair(mobile_token_, event->action == TouchAction::Confirm);
            mobile_token_ = 0;
            render(mobile_return_);
        }
        return;
    }
    if (event->action == TouchAction::Back) {
        back();
        return;
    }
    if (page_ == FirstLoginPage::PassChoice && event->action == TouchAction::Cancel) {
        request_->passphrase.clear();
        after_seed_credentials();
        return;
    }
    if (page_ == FirstLoginPage::PinChoice && event->action == TouchAction::Cancel) {
        back();
        return;
    }
    if (page_ == FirstLoginPage::Ready && profile_.pin_enabled &&
        event->action == TouchAction::Cancel) {
        session_.reset();
        addresses_.clear();
        render(FirstLoginPage::Unlock);
        return;
    }
    if (event->action == TouchAction::Cancel) {
        back();
        return;
    }
    if (event->action == TouchAction::InputTooLong) {
        auto entry = page_;
        if (page_ == FirstLoginPage::PassConfirm)
            entry = FirstLoginPage::PassEntry;
        if (page_ == FirstLoginPage::PinConfirm)
            entry = FirstLoginPage::PinEntry;
        if (page_ == FirstLoginPage::TransactionConfirm)
            entry = FirstLoginPage::TransactionEntry;
        first_.clear();
        render(entry, tr(Text::texto_longo_demais_reduza_a_entrada_114));
        return;
    }
    if (page_ == FirstLoginPage::Devices && event->action == TouchAction::Select &&
        event->selection < access_devices_.size()) {
        selected_device_id_ = access_devices_[event->selection].id;
        render(FirstLoginPage::DeviceActions);
        return;
    }
    if (page_ == FirstLoginPage::DeviceActions && event->action == TouchAction::Select) {
        if (event->selection < 2)
            render(event->selection ? FirstLoginPage::DeviceBlock : FirstLoginPage::DeviceDelete);
        return;
    }
    if ((page_ == FirstLoginPage::DeviceDelete || page_ == FirstLoginPage::DeviceBlock) &&
        event->action == TouchAction::Confirm) {
        const bool ok =
            signing_device_ && (page_ == FirstLoginPage::DeviceDelete
                                    ? signing_device_->remove_device(selected_device_id_)
                                    : signing_device_->block_device(selected_device_id_));
        if (ok)
            render(FirstLoginPage::Devices);
        else
            render(page_, tr(Text::devices_save_error));
        return;
    }
    if (page_ == FirstLoginPage::Devices && event->action == TouchAction::Confirm) {
        render(FirstLoginPage::Settings);
        return;
    }
    if (event->action == TouchAction::Select) {
        if (page_ == FirstLoginPage::Language && event->selection < language_order.size()) {
            const auto selected = language_order[event->selection];
            if (!service_.save_language(language_codes[static_cast<std::size_t>(selected)])) {
                render(FirstLoginPage::Language, tr(Text::language_save_error));
                return;
            }
            language_ = selected;
            language_ready_ = true;
            view_.language(language_);
            if (initial_language_choice_) {
                initial_language_choice_ = false;
                if (!load_device_name())
                    return;
                if (device_name_.empty())
                    render(FirstLoginPage::DeviceName);
                else {
                    splash_until_ = now_ + 2000;
                    render(FirstLoginPage::Splash);
                }
            } else
                render(FirstLoginPage::Settings);
            return;
        }
        if (page_ == FirstLoginPage::Main && event->selection < 2) {
            constexpr FirstLoginPage pages[] = {FirstLoginPage::Home, FirstLoginPage::Settings};
            render(pages[event->selection]);
            return;
        }
        if (page_ == FirstLoginPage::TransactionIntro) {
            apply_phrase_to_all_ = !apply_phrase_to_all_;
            render(FirstLoginPage::TransactionIntro);
            return;
        }
        if (page_ == FirstLoginPage::Home && event->selection < profiles_.size()) {
            wallet_index_ = event->selection;
            addresses_.clear();
            resume_address_detail_ = false;
            auto job = std::make_unique<SetupJob>();
            job->work = SetupWork::PublicAddresses;
            job->wallet_index = wallet_index_;
            submit(std::move(job));
            return;
        }
        if (page_ == FirstLoginPage::Balances && event->selection < 2) {
            render(event->selection ? FirstLoginPage::BalanceNetwork : FirstLoginPage::BalanceCoin);
            return;
        }
        if (page_ == FirstLoginPage::MoneroWallet && event->selection < profiles_.size()) {
            monero_wallet_index_ = event->selection;
            if (service_.load_monero_enabled(monero_wallet_index_, monero_enabled_))
                render(FirstLoginPage::MoneroConfig);
            else
                fault(tr(Text::nao_foi_possivel_processar_no_aparelho_105));
            return;
        }
        if (page_ == FirstLoginPage::Settings && event->selection < 9) {
            const auto selected = event->selection;
            if (selected < 2) {
                phrase_proofs_.clear();
                pending_shared_phrase_.clear();
                old_phrase_.clear();
                gathering_phrases_ = false;
                apply_phrase_to_all_ = profile_.shared_transaction_phrase;
                phrase_errors_ = 0;
                additional_ = true;
                request_ = std::make_unique<SetupRequest>();
                request_->imported = selected == 1;
                render(FirstLoginPage::Name);
            } else if (selected == 8) {
                render(FirstLoginPage::Devices);
            } else if (selected == 7) {
                render(FirstLoginPage::MoneroWallet);
            } else if (selected == 6) {
                editing_device_name_ = true;
                render(FirstLoginPage::DeviceName);
            } else if (selected == 5) {
                initial_language_choice_ = false;
                render(FirstLoginPage::Language);
            } else if (selected == 2)
                render(FirstLoginPage::DeleteWallet);
            else if (selected == 4)
                render(FirstLoginPage::PhraseWallet);
            else {
                edit_ = Edit::Pin;
                auth_errors_ = phrase_errors_ = 0;
                if (profile_.pin_enabled)
                    render(FirstLoginPage::AuthenticatePin);
                else
                    begin_edit();
            }
            return;
        }
        if ((page_ == FirstLoginPage::PhraseWallet || page_ == FirstLoginPage::DeleteWallet) &&
            event->selection < profiles_.size()) {
            const bool deleting = page_ == FirstLoginPage::DeleteWallet;
            wallet_index_ = event->selection;
            edit_ = deleting ? Edit::Delete : Edit::Phrase;
            auth_errors_ = phrase_errors_ = 0;
            if (profile_.pin_enabled)
                render(FirstLoginPage::AuthenticatePin);
            else
                begin_edit();
            return;
        }
    }
    if (page_ == FirstLoginPage::Choice && event->action == TouchAction::Select &&
        event->selection < 2) {
        request_ = std::make_unique<SetupRequest>();
        request_->imported = event->selection == 1;
        render(FirstLoginPage::Name);
    } else if (page_ == FirstLoginPage::WordCount && event->action == TouchAction::Select &&
               event->selection < 3) {
        constexpr std::size_t counts[] = {12, 18, 24};
        request_->word_count = counts[event->selection];
        request_->mnemonic.clear();
        import_index_ = 0;
        if (request_->imported)
            render(FirstLoginPage::ImportWord);
        else
            generate_words();
    } else if (page_ == FirstLoginPage::MobilePolicy && event->action == TouchAction::Select) {
        request_->mobile_access = MobileAccess::Pin;
        render(FirstLoginPage::MobilePolicy);
    } else if (page_ == FirstLoginPage::AddressList && event->action == TouchAction::Select &&
               event->selection < std::size(address_catalog)) {
        address_index_ = event->selection;
        address_asset_index_ = 0;
        address_detail_parent_ = FirstLoginPage::AddressList;
        render(address_catalog[address_index_].standalone ? FirstLoginPage::AddressDetail
                                                          : FirstLoginPage::AddressNetwork);
    } else if (page_ == FirstLoginPage::AddressNetwork && event->action == TouchAction::Select) {
        const auto& network = address_catalog[address_index_];
        const auto& tokens = network.tokens;
        if (event->selection == 0 ||
            (event->selection <= tokens.size() && !tokens[event->selection - 1].symbol.empty())) {
            address_asset_index_ = event->selection;
            address_detail_parent_ = FirstLoginPage::AddressNetwork;
            const bool pending =
                address_asset_index_ > 0 && tokens[address_asset_index_ - 1].pending;
            resume_address_detail_ =
                !pending && network.native == "ADA" &&
                find_address(addresses_, network.firmware_name) == addresses_.end();
            render(resume_address_detail_ ? FirstLoginPage::AddressPhrase
                                          : FirstLoginPage::AddressDetail);
        }
    } else if (event->action == TouchAction::Confirm) {
        switch (page_) {
            case FirstLoginPage::MoneroConfig:
                if (session_ && service_.save_monero_enabled(*session_, monero_wallet_index_,
                                                             !monero_enabled_)) {
                    monero_enabled_ = !monero_enabled_;
                    render(FirstLoginPage::MoneroConfig);
                } else
                    render(FirstLoginPage::MoneroConfig,
                           tr(Text::nao_foi_possivel_processar_no_aparelho_105));
                break;
            case FirstLoginPage::WordReview:
                render(FirstLoginPage::PassChoice);
                break;
            case FirstLoginPage::PassChoice:
                render(FirstLoginPage::PassEntry);
                break;
            case FirstLoginPage::PinChoice:
                render(FirstLoginPage::PinEntry);
                break;
            case FirstLoginPage::MobilePolicy:
                if (edit_ == Edit::Pin)
                    change_pin();
                else
                    render(FirstLoginPage::TransactionIntro);
                break;
            case FirstLoginPage::TransactionIntro:
                render(additional_ && apply_phrase_to_all_ && profile_.shared_transaction_phrase &&
                               !profiles_.empty()
                           ? FirstLoginPage::CommonPhrase
                           : FirstLoginPage::TransactionEntry);
                break;
            case FirstLoginPage::SeedNotice:
                render(seed_target_);
                break;
            case FirstLoginPage::Backup:
                commit();
                break;
            case FirstLoginPage::Success:
                unlock_target_ = FirstLoginPage::Home;
                auto_unlock_ = !additional_;
                additional_ = false;
                after_splash();
                break;
            case FirstLoginPage::Ready:
                address_after_unlock_ = profile_.pin_enabled && !session_;
                render(address_after_unlock_ ? FirstLoginPage::Unlock
                                             : FirstLoginPage::AddressPhrase);
                break;
            case FirstLoginPage::BalanceCoin:
            case FirstLoginPage::BalanceNetwork:
                render(FirstLoginPage::Balances);
                break;
            case FirstLoginPage::Pending:
                render(pending_return_);
                break;
            case FirstLoginPage::AddressDetail:
                render(address_detail_parent_);
                break;
            case FirstLoginPage::DeleteConfirm:
                delete_wallet();
                break;
            case FirstLoginPage::DeleteDone:
                unlock_target_ = FirstLoginPage::Home;
                after_splash();
                break;
            case FirstLoginPage::Home:
            case FirstLoginPage::DeleteWallet:
            case FirstLoginPage::PhraseWallet:
                if (profiles_.empty())
                    back();
                break;
            case FirstLoginPage::Fault:
                if (language_ready_)
                    after_splash();
                else
                    start(now_);
                break;
            default:
                break;
        }
    } else if (event->action == TouchAction::Submit) {
        const auto value = event->value.view();
        switch (page_) {
            case FirstLoginPage::DeviceName: {
                if (!valid_device_name(value)) {
                    render(FirstLoginPage::DeviceName, tr(Text::device_name_invalid));
                    break;
                }
                if (!service_.save_device_name(value)) {
                    render(FirstLoginPage::DeviceName, tr(Text::device_name_save_error));
                    break;
                }
                device_name_ = value;
                if (editing_device_name_) {
                    editing_device_name_ = false;
                    render(FirstLoginPage::Settings);
                } else {
                    splash_until_ = now_ + 2000;
                    render(FirstLoginPage::Splash);
                }
                break;
            }
            case FirstLoginPage::Name:
                if (!valid_wallet_name(value))
                    render(FirstLoginPage::Name, tr(Text::digite_um_nome_valido_e_mais_115));
                else {
                    request_->name = value;
                    render(FirstLoginPage::WordCount);
                }
                break;
            case FirstLoginPage::ImportWord:
                if (!is_mnemonic_word(value)) {
                    render(FirstLoginPage::ImportWord,
                           tr(Text::palavra_fora_do_dicionario_bip39_em_116));
                    break;
                }
                if (!append_word(request_->mnemonic, value)) {
                    fault(tr(Text::nao_foi_possivel_registrar_a_palavra_117));
                    break;
                }
                if (++import_index_ == request_->word_count) {
                    crypto::Entropy entropy;
                    if (crypto::mnemonic_entropy(request_->mnemonic.view(), entropy) !=
                        crypto::HdError::None) {
                        import_index_ = 0;
                        request_->mnemonic.clear();
                        render(FirstLoginPage::ImportWord,
                               tr(Text::checksum_invalido_confira_e_redigite_as_118));
                    } else
                        render(FirstLoginPage::WordReview);
                } else
                    render(FirstLoginPage::ImportWord);
                break;
            case FirstLoginPage::PassEntry:
                if (value.empty() || value.size() > 256 || !valid_text(value))
                    render(FirstLoginPage::PassEntry,
                           tr(Text::passphrase_vazia_ou_longa_demais_reduza_119));
                else {
                    first_.assign(value);
                    render(FirstLoginPage::PassConfirm);
                }
                break;
            case FirstLoginPage::PassConfirm:
                if (!first_.equals(value)) {
                    first_.clear();
                    render(FirstLoginPage::PassEntry,
                           tr(Text::as_passphrases_nao_coincidem_digite_novamente_120));
                } else {
                    request_->passphrase.assign(first_.view());
                    first_.clear();
                    after_seed_credentials();
                }
                break;
            case FirstLoginPage::PinEntry:
                if (!valid_setup_pin(value))
                    render(FirstLoginPage::PinEntry, tr(Text::o_pin_deve_ter_de_4_121));
                else {
                    first_.assign(value);
                    render(FirstLoginPage::PinConfirm);
                }
                break;
            case FirstLoginPage::PinConfirm:
                if (!first_.equals(value)) {
                    first_.clear();
                    render(FirstLoginPage::PinEntry,
                           tr(Text::os_pins_nao_coincidem_digite_novamente_122));
                } else {
                    request_->pin.assign(first_.view());
                    first_.clear();
                    request_->mobile_access = MobileAccess::Pin;
                    if (edit_ == Edit::Pin)
                        change_pin();
                    else
                        render(FirstLoginPage::TransactionIntro);
                }
                break;
            case FirstLoginPage::TransactionEntry:
                if (!valid_transaction_phrase(value))
                    render(FirstLoginPage::TransactionEntry,
                           tr(Text::use_ao_menos_8_caracteres_reduza_123));
                else {
                    first_.assign(value);
                    render(FirstLoginPage::TransactionConfirm);
                }
                break;
            case FirstLoginPage::TransactionConfirm:
                if (!first_.equals(value)) {
                    first_.clear();
                    render(FirstLoginPage::TransactionEntry,
                           tr(Text::as_frases_nao_coincidem_digite_novamente_124));
                } else {
                    request_->transaction_phrase.assign(first_.view());
                    first_.clear();
                    finish_phrase_creation();
                }
                break;
            case FirstLoginPage::Unlock:
            case FirstLoginPage::AuthenticatePin:
                unlock(value);
                break;
            case FirstLoginPage::OldPhrase:
                verify_phrase(value);
                break;
            case FirstLoginPage::CommonPhrase:
                verify_common(value);
                break;
            case FirstLoginPage::AddressPhrase:
                if (!valid_transaction_phrase(value))
                    render(FirstLoginPage::AddressPhrase,
                           tr(Text::digite_a_frase_de_transacao_da_125));
                else
                    request_addresses(value);
                break;
            default:
                break;
        }
    }
}
} // namespace airlock
