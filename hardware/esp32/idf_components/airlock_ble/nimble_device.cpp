// SPDX-License-Identifier: GPL-3.0-or-later
#include "airlock_ble.hpp"
#include "core/public_wallet_rpc.hpp"
#include "core/secret.hpp"
#include "core/rules.hpp"
#include "core/json_memory.hpp"
#include "hardware/bond_admission.hpp"
#include "hardware/bond_removal.hpp"
#include "core/device_access.hpp"
#include "cJSON.h"
#include <cstring>
#include "esp_timer.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
extern "C" {
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_sm.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_store.h"
#include "host/ble_uuid.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
void ble_store_config_init(void);
}
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <utility>

#if !CONFIG_BT_NIMBLE_ENABLED || !CONFIG_BT_NIMBLE_NVS_PERSIST ||                                  \
    !CONFIG_BT_NIMBLE_SECURITY_ENABLE || !CONFIG_BT_NIMBLE_SM_SC ||                                \
    CONFIG_BT_NIMBLE_SM_SC_ONLY != 1 || CONFIG_BT_NIMBLE_SM_LVL != 4 ||                            \
    CONFIG_BT_NIMBLE_MAX_CONNECTIONS != 1
#error "AirLock BLE requires one connection, persistent bonds and authenticated Secure Connections."
#endif
#if defined(CONFIG_BT_NIMBLE_SM_LEGACY) || defined(CONFIG_BT_NIMBLE_SM_SC_DEBUG_KEYS)
#error "Legacy pairing and debug keys are forbidden for the AirLock public service."
#endif

namespace airlock {
namespace {
std::uint64_t now_ms() {
    return static_cast<std::uint64_t>(esp_timer_get_time() / 1000);
}
std::string peer_id(const ble_addr_t& address) {
    char value[24];
    std::snprintf(value, sizeof value, "%u:%02x:%02x:%02x:%02x:%02x:%02x", address.type & 1,
                  address.val[5], address.val[4], address.val[3], address.val[2], address.val[1],
                  address.val[0]);
    return value;
}
} // namespace
struct NimbleDeviceService::State {
    struct Fragment {
        std::uint64_t connection = 0, received = 0;
        std::uint16_t length = 0;
        std::array<char, 512> bytes{};
    };
    static State* instance;
    NimbleDeviceService& owner;
    PublicWalletService& wallet;
    PublicDeviceInfo info;
    const std::function<std::string()> temperature;
    mutable std::mutex mutex;
    mutable std::mutex access_mutex;
    DeviceAccessList access_list;
    std::atomic<bool> access_healthy{true};
    bool load_access() {
        nvs_handle_t storage = 0;
        const auto opened = nvs_open("airlock_access", NVS_READONLY, &storage);
        if (opened == ESP_ERR_NVS_NOT_FOUND)
            return true;
        if (opened != ESP_OK)
            return false;
        std::size_t size = 0;
        auto error = nvs_get_blob(storage, "devices", nullptr, &size);
        if (error == ESP_ERR_NVS_NOT_FOUND) {
            nvs_close(storage);
            return true;
        }
        if (error != ESP_OK || size > 65536) {
            nvs_close(storage);
            return false;
        }
        std::string data(size, '\0');
        error = nvs_get_blob(storage, "devices", data.data(), &size);
        nvs_close(storage);
        return error == ESP_OK && access_list.decode(data);
    }
    bool denied(std::string_view id) const {
        std::lock_guard<std::mutex> lock(access_mutex);
        return !access_healthy || access_list.blocked(id);
    }
    bool record_access(std::string_view id, bool deny, bool remove = false) {
        std::lock_guard<std::mutex> lock(access_mutex);
        if (!deny && !remove && access_list.blocked(id))
            return false;
        const bool ok = access_list.update(id, deny, remove, [](std::string_view data) {
            nvs_handle_t storage = 0;
            if (nvs_open("airlock_access", NVS_READWRITE, &storage) != ESP_OK)
                return false;
            const auto written = nvs_set_blob(storage, "devices", data.data(), data.size());
            const bool saved = written == ESP_OK && nvs_commit(storage) == ESP_OK;
            nvs_close(storage);
            return saved;
        });
        if (ok)
            ++revision;
        else
            access_healthy =
                false; // Never continue admitting phones after a failed decision write.
        return ok;
    }
    PublicPeer peer;
    std::string identity;
    std::uint16_t handle = BLE_HS_CONN_HANDLE_NONE, notify_handle = 0;
    bool subscribed = false, blocked = false, pairing_delivered = false;
    std::optional<PairingRequest> pairing;
    BondAdmission bond_admission;
    std::uint64_t pairing_deadline = 0, pairing_serial = 0;
    std::atomic<std::uint64_t> revision{0};
    std::atomic<bool> stopping{false}, started{false}, unlocked{false}, synced_ready{false};
    std::atomic<bool> public_syncing{false};
    ble_npl_event access_event{}, name_event{};
    std::string pending_name;
    int tx_status = BLE_HS_ENOTCONN;
    ble_npl_event tx_event{};
    std::array<char, config::notification_bytes> tx_bytes{};
    std::size_t tx_length = 0;
    std::uint64_t tx_connection = 0;
    bool tx_pending = false;
    ble_npl_event pairing_event{};
    ble_npl_event removal_event{};
    std::mutex removal_mutex;
    std::string removal_id;
    bool removal_result = false, editing_bonds = false;
    std::uint64_t decision_token = 0, decision_connection = 0;
    bool decision_accept = false, decision_pending = false;
    std::optional<TransactionPrompt> transaction_prompt;
    std::optional<SigningJob> signing_job;
    std::optional<SigningResult> signing_result;
    std::optional<SignOutcome> signing_outcome;
    std::string signing_error;
    std::uint64_t signing_connection = 0;
    QueueHandle_t queue = nullptr;
    SemaphoreHandle_t tx_done = nullptr, worker_done = nullptr, removal_done = nullptr;
    TaskHandle_t worker_task = nullptr;
    ble_uuid_any_t service_uuid{}, notify_uuid{}, command_uuid{};
    ble_gatt_chr_def characteristics[3]{};
    ble_gatt_svc_def services[2]{};
    std::uint8_t address_type = 0;
    State(NimbleDeviceService& device, PublicWalletService& source, PublicDeviceInfo details,
          std::function<std::string()> sensor)
        : owner(device), wallet(source), info(std::move(details)), temperature(std::move(sensor)) {}
    PublicDeviceInfo device_info() const {
        std::lock_guard<std::mutex> lock(mutex);
        return info;
    }
    PublicPeer current() const {
        std::lock_guard<std::mutex> lock(mutex);
        auto value = peer;
        value.connected =
            value.connected && !blocked && !stopping.load() && unlocked.load() && access_healthy;
        return value;
    }
    void terminate(std::uint64_t expected = 0) {
        std::uint16_t connection;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (expected && peer.connection != expected)
                return;
            blocked = true;
            pairing.reset();
            bond_admission.reset();
            connection = handle;
        }
        if (connection != BLE_HS_CONN_HANDLE_NONE)
            ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
        if (tx_done)
            xSemaphoreGive(tx_done);
    }
    void clear_link() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            peer.connected = peer.authenticated = peer.encrypted = false;
            subscribed = false;
            pairing.reset();
            bond_admission.reset();
            handle = BLE_HS_CONN_HANDLE_NONE;
            tx_status = BLE_HS_ENOTCONN;
            ++revision;
        }
        if (tx_done)
            xSemaphoreGive(tx_done);
    }
    void advertise() {
        if (stopping || !unlocked || !synced_ready || !access_healthy || editing_bonds ||
            ble_gap_adv_active())
            return;
        ble_hs_adv_fields fields{};
        fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
        // ble_uuid_from_str compresses Bluetooth-base UUIDs (including BEEF)
        // into 16/32-bit UUIDs. Never reinterpret that union as a UUID128.
        switch (service_uuid.u.type) {
            case BLE_UUID_TYPE_16:
                fields.uuids16 = &service_uuid.u16;
                fields.num_uuids16 = 1;
                fields.uuids16_is_complete = 1;
                break;
            case BLE_UUID_TYPE_32:
                fields.uuids32 = &service_uuid.u32;
                fields.num_uuids32 = 1;
                fields.uuids32_is_complete = 1;
                break;
            case BLE_UUID_TYPE_128:
                fields.uuids128 = &service_uuid.u128;
                fields.num_uuids128 = 1;
                fields.uuids128_is_complete = 1;
                break;
            default:
                ESP_LOGE("airlock_ble", "Invalid service UUID type");
                return;
        }
        int status = ble_gap_adv_set_fields(&fields);
        if (status) {
            ESP_LOGE("airlock_ble", "Advertising fields failed: %d", status);
            return;
        }
        ble_hs_adv_fields scan{};
        const auto identity = device_info();
        scan.name = reinterpret_cast<const std::uint8_t*>(identity.name.data());
        scan.name_len = static_cast<std::uint8_t>(identity.name.size());
        scan.name_is_complete = 1;
        status = ble_gap_adv_rsp_set_fields(&scan);
        if (status) {
            ESP_LOGE("airlock_ble", "Scan response failed: %d", status);
            return;
        }
        ble_gap_adv_params parameters{};
        parameters.conn_mode = BLE_GAP_CONN_MODE_UND;
        parameters.disc_mode = BLE_GAP_DISC_MODE_GEN;
        status =
            ble_gap_adv_start(address_type, nullptr, BLE_HS_FOREVER, &parameters, gap_event, this);
        if (status)
            ESP_LOGE("airlock_ble", "Advertising start failed: %d", status);
        else
            ESP_LOGI("airlock_ble",
                     "Advertising enabled after local unlock; service=%s; uuid_bits=%u",
                     config::service_uuid.data(), service_uuid.u.type);
    }
    static void synced() {
        auto* state = instance;
        if (!state || ble_hs_util_ensure_addr(0) || ble_hs_id_infer_auto(0, &state->address_type))
            return;
        state->synced_ready = true;
        state->advertise();
    }
    static void reset(int) {
        if (instance) {
            instance->synced_ready = false;
            instance->clear_link();
        }
    }
    static void update_access(ble_npl_event* event) {
        auto& state = *static_cast<State*>(ble_npl_event_get_arg(event));
        if (!state.unlocked) {
            ble_gap_adv_stop();
            state.terminate();
        } else if (!state.current().connected)
            state.advertise();
    }
    static void update_name(ble_npl_event* event) {
        auto& state = *static_cast<State*>(ble_npl_event_get_arg(event));
        std::string name;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            if (state.pending_name.empty() || state.pending_name == state.info.name)
                return;
            name = std::move(state.pending_name);
            state.info.name = name;
        }
        ble_gap_adv_stop();
        const auto status = ble_svc_gap_device_name_set(name.c_str());
        if (status) {
            ESP_LOGE("airlock_ble", "Device name update failed: %d", status);
            state.terminate();
            return;
        }
        // A live connection must authorize the device again with its new identity.
        if (state.current().connected)
            state.terminate();
        else
            state.advertise();
        ESP_LOGI("airlock_ble", "Device name updated: %s", name.c_str());
    }
    static int store_status(ble_store_status_event* event, void*) {
        // FULL is a preflight warning, before numeric comparison: let pairing
        // reach the local confirmation without changing any saved keys yet.
        if (event->event_code == BLE_STORE_EVENT_FULL)
            return 0;
        if (event->event_code != BLE_STORE_EVENT_OVERFLOW || !instance)
            return BLE_HS_ENOMEM;
        auto& state = *instance;
        std::uint16_t handle;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            handle = state.handle;
        }
        ble_gap_conn_desc current{};
        if (ble_gap_conn_find(handle, &current) || !current.sec_state.encrypted ||
            !current.sec_state.authenticated || current.sec_state.key_size != 16)
            return BLE_HS_ENOMEM;
        if (state.denied(peer_id(current.peer_id_addr)))
            return BLE_HS_ENOMEM;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            // NimBLE persists keys before delivering ENC_CHANGE. Use the host's
            // security descriptor above rather than our not-yet-updated peer flags.
            if (state.handle != handle ||
                !state.bond_admission.may_rotate(state.peer.connection,
                                                 state.peer.connected && !state.blocked &&
                                                     !state.stopping && state.unlocked,
                                                 true))
                return BLE_HS_ENOMEM;
        }
        if (event->overflow.obj_type != BLE_STORE_OBJ_TYPE_OUR_SEC &&
            event->overflow.obj_type != BLE_STORE_OBJ_TYPE_PEER_SEC &&
            event->overflow.obj_type != BLE_STORE_OBJ_TYPE_CCCD)
            return BLE_HS_ENOMEM;
        if (!event->overflow.value)
            return BLE_HS_ENOMEM;
        const auto& incoming = event->overflow.obj_type == BLE_STORE_OBJ_TYPE_CCCD
                                   ? event->overflow.value->cccd.peer_addr
                                   : event->overflow.value->sec.peer_addr;
        if (ble_addr_cmp(&incoming, &current.peer_id_addr) != 0)
            return BLE_HS_ENOMEM;
        // NimBLE enumerates bonds oldest first. Never evict the current phone,
        // including when its CCCD is written after the security records.
        std::array<ble_addr_t, CONFIG_BT_NIMBLE_MAX_BONDS> peers{};
        int count = 0;
        const int status = ble_store_util_bonded_peers(peers.data(), &count, peers.size());
        if (status)
            return status;
        for (int i = 0; i < count; ++i) {
            if (ble_addr_cmp(&peers[i], &current.peer_id_addr) == 0)
                continue;
            const int removed = state.unpair_peer(peers[i]);
            if (!removed)
                ++state.revision;
            return removed;
        }
        return BLE_HS_ENOMEM;
    }
    int unpair_peer(const ble_addr_t& address) {
        editing_bonds = true;
        const int status = without_advertising(
            ble_gap_adv_active(), [] { return ble_gap_adv_stop(); },
            [&] { return ble_gap_unpair(&address); },
            [&] {
                editing_bonds = false;
                if (!current().connected)
                    advertise();
            });
        editing_bonds = false;
        return status;
    }
    static void remove_saved_device(ble_npl_event* event) {
        auto& state = *static_cast<State*>(ble_npl_event_get_arg(event));
        std::string id;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            id = state.removal_id;
        }
        bool ok = !state.stopping && state.unlocked;
        std::array<ble_addr_t, CONFIG_BT_NIMBLE_MAX_BONDS> peers{};
        int count = 0;
        if (ok)
            ok = ble_store_util_bonded_peers(peers.data(), &count, peers.size()) == 0;
        if (ok)
            for (int i = 0; i < count; ++i)
                if (peer_id(peers[i]) == id) {
                    ok = state.unpair_peer(peers[i]) == 0;
                    break;
                }
        if (ok) {
            if (state.owner.connection_identity() == id)
                state.terminate();
            ok = state.record_access(id, false, true);
        }
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            state.removal_result = ok;
        }
        xSemaphoreGive(state.removal_done);
    }
    static int gap_event(ble_gap_event* event, void* argument) {
        auto& state = *static_cast<State*>(argument);
        switch (event->type) {
            case BLE_GAP_EVENT_CONNECT:
                if (event->connect.status)
                    state.advertise();
                else if (!state.unlocked)
                    ble_gap_terminate(event->connect.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
                else {
                    ble_gap_conn_desc description{};
                    const bool known =
                        ble_gap_conn_find(event->connect.conn_handle, &description) == 0;
                    if (!known || state.denied(peer_id(description.peer_id_addr))) {
                        ble_gap_terminate(event->connect.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
                        return 0;
                    }
                    std::lock_guard<std::mutex> lock(state.mutex);
                    state.identity = known ? peer_id(description.peer_id_addr) : std::string{};
                    state.handle = event->connect.conn_handle;
                    ++state.peer.connection;
                    state.peer.connected = true;
                    state.peer.authenticated = state.peer.encrypted = false;
                    state.peer.mtu = 23;
                    state.blocked = state.subscribed = false;
                    state.pairing.reset();
                    state.bond_admission.reset();
                    ++state.revision;
                }
                return 0;
            case BLE_GAP_EVENT_DISCONNECT:
                state.clear_link();
                state.advertise();
                return 0;
            case BLE_GAP_EVENT_ADV_COMPLETE:
                state.advertise();
                return 0;
            case BLE_GAP_EVENT_ENC_CHANGE: {
                ble_gap_conn_desc description{};
                const bool found =
                    ble_gap_conn_find(event->enc_change.conn_handle, &description) == 0;
                // Encryption resolves a bonded phone's identity after an RPA connect.
                const bool denied = found && state.denied(peer_id(description.peer_id_addr));
                bool secure, approved_pairing = false;
                {
                    std::lock_guard<std::mutex> lock(state.mutex);
                    secure = found && !denied && !event->enc_change.status &&
                             description.sec_state.encrypted &&
                             description.sec_state.authenticated &&
                             description.sec_state.key_size == 16;
                    state.peer.encrypted = secure;
                    state.peer.authenticated = secure;
                    if (found)
                        state.identity = peer_id(description.peer_id_addr);
                    approved_pairing = state.bond_admission.may_rotate(
                        state.peer.connection, state.peer.connected, secure);
                    state.pairing.reset();
                    ++state.revision;
                }
                if (secure && approved_pairing &&
                    !state.owner.accept_current_device(state.current().connection))
                    secure = false;
                if (!secure)
                    state.terminate();
                return 0;
            }
            case BLE_GAP_EVENT_MTU: {
                std::lock_guard<std::mutex> lock(state.mutex);
                if (state.handle == event->mtu.conn_handle)
                    state.peer.mtu = event->mtu.value;
                return 0;
            }
            case BLE_GAP_EVENT_SUBSCRIBE: {
                bool cancel = false;
                {
                    std::lock_guard<std::mutex> lock(state.mutex);
                    if (event->subscribe.attr_handle == state.notify_handle &&
                        event->subscribe.conn_handle == state.handle) {
                        cancel = state.subscribed && !event->subscribe.cur_notify;
                        state.subscribed = event->subscribe.cur_notify;
                    }
                }
                if (cancel)
                    state.terminate();
                return 0;
            }
            case BLE_GAP_EVENT_PASSKEY_ACTION:
                if (event->passkey.params.action != BLE_SM_IOACT_NUMCMP) {
                    state.terminate();
                    return 0; // No Just Works or automatic approval.
                }
                {
                    std::lock_guard<std::mutex> lock(state.mutex);
                    if (event->passkey.conn_handle != state.handle)
                        return 0;
                    state.pairing =
                        PairingRequest{++state.pairing_serial, event->passkey.params.numcmp};
                    state.pairing_delivered = false;
                    state.pairing_deadline = now_ms() + config::confirmation_timeout_ms;
                }
                return 0;
            case BLE_GAP_EVENT_REPEAT_PAIRING:
                // Require explicit removal via the device UI before replacing a bond.
                return BLE_GAP_REPEAT_PAIRING_IGNORE;
            default:
                return 0;
        }
    }
    static int access(std::uint16_t connection, std::uint16_t attribute,
                      ble_gatt_access_ctxt* context, void* argument) {
        auto& state = *static_cast<State*>(argument);
        const auto peer = state.current();
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            if (connection != state.handle)
                return BLE_ATT_ERR_UNLIKELY;
        }
        if (!peer.connected || !peer.authenticated || !peer.encrypted)
            return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
        if (attribute == state.notify_handle && context->op == BLE_GATT_ACCESS_OP_READ_CHR)
            return os_mbuf_append(context->om, "READY", 5) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        if (context->op != BLE_GATT_ACCESS_OP_WRITE_CHR)
            return BLE_ATT_ERR_UNLIKELY;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            if (!state.subscribed)
                return BLE_ATT_ERR_UNLIKELY;
        }
        Fragment fragment;
        const auto size = OS_MBUF_PKTLEN(context->om);
        if (!size || size > fragment.bytes.size())
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        fragment.connection = peer.connection;
        fragment.received = now_ms();
        fragment.length = size;
        const bool copied = os_mbuf_copydata(context->om, 0, size, fragment.bytes.data()) == 0;
        const bool queued = copied && xQueueSend(state.queue, &fragment, 0) == pdTRUE;
        wipe(fragment.bytes.data(), fragment.bytes.size());
        if (!queued) {
            state.terminate(); // Includes Write Without Response; never drop a fragment silently.
            return BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        return 0;
    }
    static void host(void*) {
        nimble_port_run();
        nimble_port_freertos_deinit();
    }
    static void confirm_pairing(ble_npl_event* event) {
        auto& state = *static_cast<State*>(ble_npl_event_get_arg(event));
        std::uint16_t handle;
        bool accept;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            state.decision_pending = false;
            if (state.stopping || !state.unlocked || state.blocked || !state.peer.connected ||
                !state.pairing || state.pairing->token != state.decision_token ||
                state.peer.connection != state.decision_connection)
                return;
            handle = state.handle;
            accept = state.decision_accept && now_ms() < state.pairing_deadline;
            if (accept)
                state.bond_admission.approve_pairing(state.peer.connection);
            state.pairing.reset();
        }
        // UI decisions and connection callbacks run on the same host task so a
        // delayed touch cannot authorize a different link with a recycled handle.
        ble_sm_io decision{};
        decision.action = BLE_SM_IOACT_NUMCMP;
        decision.numcmp_accept = accept;
        if (ble_sm_inject_io(handle, &decision) || !accept)
            state.terminate();
    }
    static void transmit(ble_npl_event* event) {
        auto& state = *static_cast<State*>(ble_npl_event_get_arg(event));
        std::array<char, config::notification_bytes> bytes{};
        std::size_t length;
        std::uint16_t handle;
        bool allowed;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            allowed = !state.stopping && state.unlocked && !state.blocked && state.subscribed &&
                      state.peer.connected && state.peer.encrypted && state.peer.authenticated &&
                      state.peer.connection == state.tx_connection;
            handle = state.handle;
            length = state.tx_length;
            bytes = state.tx_bytes;
            wipe(state.tx_bytes.data(), state.tx_bytes.size());
        }
        // Run on the NimBLE host task, serialized with connection/disconnection
        // callbacks. A recycled controller handle cannot receive an old session's
        // public data between the security check and the notify call.
        int status = BLE_HS_ENOTCONN;
        ble_gap_conn_desc connection{};
        if (allowed && ble_gap_conn_find(handle, &connection) == 0 &&
            connection.sec_state.authenticated && connection.sec_state.encrypted &&
            connection.sec_state.key_size == 16) {
            auto* buffer = ble_hs_mbuf_from_flat(bytes.data(), length);
            status = buffer ? ble_gatts_notify_custom(handle, state.notify_handle, buffer)
                            : BLE_HS_ENOMEM;
        }
        wipe(bytes.data(), bytes.size());
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            state.tx_status = status;
            state.tx_pending = false;
        }
        xSemaphoreGive(state.tx_done);
    }
    static void worker(void* argument) {
        auto& state = *static_cast<State*>(argument);
        std::unique_ptr<PublicWalletRpc> rpc;
        std::uint64_t session = 0;
        while (!state.stopping) {
            Fragment fragment;
            const bool received =
                xQueueReceive(state.queue, &fragment, pdMS_TO_TICKS(20)) == pdTRUE;
            const auto peer = state.current();
            if (!peer.connected || !peer.authenticated || !peer.encrypted ||
                (rpc && peer.connection != session))
                rpc.reset();
            if (received && peer.connected && peer.authenticated && peer.encrypted &&
                fragment.connection == peer.connection) {
                if (!rpc) {
                    session = peer.connection;
                    rpc = std::make_unique<PublicWalletRpc>(state.wallet, state.device_info(),
                                                            state.owner);
                }
                if (rpc->receive({fragment.bytes.data(), fragment.length}, fragment.received) !=
                    ReceiveStatus::Ok) {
                    rpc.reset();
                    if (state.current().connection == fragment.connection)
                        state.terminate();
                }
            }
            wipe(fragment.bytes.data(), fragment.bytes.size());
            if (rpc && rpc->poll(now_ms()) != ReceiveStatus::Ok) {
                rpc.reset();
                if (state.current().connection == session)
                    state.terminate();
            }
            std::uint64_t expired = 0;
            {
                std::lock_guard<std::mutex> lock(state.mutex);
                if (state.pairing && now_ms() >= state.pairing_deadline)
                    expired = state.pairing->token;
            }
            if (expired)
                state.owner.respond_pairing(expired, false);
        }
        rpc.reset();
        xSemaphoreGive(state.worker_done);
        vTaskDelete(nullptr);
    }
};
NimbleDeviceService::State* NimbleDeviceService::State::instance = nullptr;
NimbleDeviceService::NimbleDeviceService(PublicWalletService& wallet, PublicDeviceInfo info,
                                         std::function<std::string()> temperature)
    : state_(std::make_unique<State>(*this, wallet, std::move(info), std::move(temperature))) {}
NimbleDeviceService::~NimbleDeviceService() {
    stop();
}
esp_err_t NimbleDeviceService::start() {
    auto& state = *state_;
    if (State::instance || state.info.name.empty() || state.info.name.size() > 29)
        return ESP_ERR_INVALID_STATE;
    // NVS contains bonds. Never erase it automatically on init/version errors.
    auto error = nvs_flash_init();
    if (error != ESP_OK)
        return error;
    error = nimble_port_init();
    if (error != ESP_OK)
        return error;
    if (!state.load_access()) {
        state.access_healthy = false;
        nimble_port_deinit();
        return ESP_FAIL;
    }
    state.queue = xQueueCreate(16, sizeof(State::Fragment));
    state.tx_done = xSemaphoreCreateBinary();
    state.worker_done = xSemaphoreCreateBinary();
    state.removal_done = xSemaphoreCreateBinary();
    if (!state.queue || !state.tx_done || !state.worker_done || !state.removal_done) {
        if (state.queue)
            vQueueDelete(state.queue);
        if (state.tx_done)
            vSemaphoreDelete(state.tx_done);
        if (state.worker_done)
            vSemaphoreDelete(state.worker_done);
        if (state.removal_done)
            vSemaphoreDelete(state.removal_done);
        nimble_port_deinit();
        return ESP_ERR_NO_MEM;
    }
    State::instance = &state;
    state.stopping = false;
    ble_npl_event_init(&state.access_event, State::update_access, &state);
    ble_npl_event_init(&state.name_event, State::update_name, &state);
    ble_npl_event_init(&state.tx_event, State::transmit, &state);
    ble_npl_event_init(&state.pairing_event, State::confirm_pairing, &state);
    ble_npl_event_init(&state.removal_event, State::remove_saved_device, &state);
    ble_hs_cfg.reset_cb = State::reset;
    ble_hs_cfg.sync_cb = State::synced;
    ble_hs_cfg.store_status_cb = State::store_status;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_DISPLAY_YESNO;
    ble_hs_cfg.sm_bonding = ble_hs_cfg.sm_mitm = ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_store_config_init();
    int status = ble_uuid_from_str(&state.service_uuid, config::service_uuid.data());
    status |= ble_uuid_from_str(&state.notify_uuid, config::status_uuid.data());
    status |= ble_uuid_from_str(&state.command_uuid, config::command_uuid.data());
    auto& notify = state.characteristics[0];
    notify.uuid = &state.notify_uuid.u;
    notify.access_cb = State::access;
    notify.arg = &state;
    notify.flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC |
                   BLE_GATT_CHR_F_READ_AUTHEN;
    notify.min_key_size = 16;
    notify.val_handle = &state.notify_handle;
    auto& write = state.characteristics[1];
    write.uuid = &state.command_uuid.u;
    write.access_cb = State::access;
    write.arg = &state;
    write.flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_WRITE_ENC |
                  BLE_GATT_CHR_F_WRITE_AUTHEN;
    write.min_key_size = 16;
    state.services[0].type = BLE_GATT_SVC_TYPE_PRIMARY;
    state.services[0].uuid = &state.service_uuid.u;
    state.services[0].characteristics = state.characteristics;
    status |= ble_gatts_count_cfg(state.services);
    status |= ble_gatts_add_svcs(state.services);
    status |= ble_svc_gap_device_name_set(state.info.name.c_str());
    status |= ble_att_set_preferred_mtu(247);
    // RPC dispatch remains on the stack while the approved Monero operation
    // opens the vault and performs the BIP39 and archive cryptography.
    if (status ||
        xTaskCreate(State::worker, "airlock_rpc", 20480, &state, 4, &state.worker_task) != pdPASS) {
        State::instance = nullptr;
        vQueueDelete(state.queue);
        vSemaphoreDelete(state.tx_done);
        vSemaphoreDelete(state.worker_done);
        vSemaphoreDelete(state.removal_done);
        nimble_port_deinit();
        return ESP_FAIL;
    }
    state.started = true;
    nimble_port_freertos_init(State::host);
    return ESP_OK;
}
void NimbleDeviceService::set_unlocked(bool unlocked) {
    auto& state = *state_;
    if (state.unlocked.exchange(unlocked) != unlocked && state.started)
        ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &state.access_event);
}
void NimbleDeviceService::set_device_name(std::string_view name) {
    if (name.empty() || name.size() > 29 || !valid_text(name))
        return;
    auto& state = *state_;
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        if (name == state.info.name)
            return;
        state.pending_name = name;
    }
    if (state.started)
        ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &state.name_event);
}
void NimbleDeviceService::stop() {
    auto& state = *state_;
    if (!state.started.exchange(false))
        return;
    state.stopping = true;
    state.terminate();
    xSemaphoreTake(state.worker_done, portMAX_DELAY);
    nimble_port_stop();
    nimble_port_deinit();
    State::instance = nullptr;
    // Queued commands are public requests; still erase them before releasing storage.
    State::Fragment fragment;
    while (xQueueReceive(state.queue, &fragment, 0) == pdTRUE)
        wipe(fragment.bytes.data(), fragment.bytes.size());
    vQueueDelete(state.queue);
    vSemaphoreDelete(state.tx_done);
    vSemaphoreDelete(state.worker_done);
    vSemaphoreDelete(state.removal_done);
}
PublicPeer NimbleDeviceService::public_peer() const {
    return state_->current();
}
PublicDeviceInfo NimbleDeviceService::read_device_info(const PublicDeviceInfo&) const {
    auto info = state_->device_info();
    // Runs on the serialized RPC worker, never the NimBLE host/ISR task.
    // A failed reading replaces the old value with empty, so stale data is omitted.
    if (state_->temperature)
        info.cpu = state_->temperature();
    return info;
}
void NimbleDeviceService::public_sync_progress(bool active) {
    state_->public_syncing.store(active);
}
bool NimbleDeviceService::public_sync_active() const {
    return state_->public_syncing.load() && state_->current().connected;
}
std::string NimbleDeviceService::request_signature(std::string_view payload,
                                                   std::uint64_t connection, std::string& error) {
    auto& state = *state_;
    TransactionPrompt prompt;
    error = "INVALID_PARAMS";
    using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
    Json object(parse_json(payload), cJSON_Delete);
    if (!object)
        return {};
    auto* password = cJSON_GetObjectItemCaseSensitive(object.get(), "password");
    const auto* network = cJSON_GetObjectItemCaseSensitive(object.get(), "network");
    const std::string_view network_name = cJSON_IsString(network) ? network->valuestring : "";
    error = password                            ? "INVALID_UTXO_PLAN"
            : network_name == "cardano-mainnet" ? "INVALID_CARDANO_PLAN"
            : network_name == "solana-mainnet"  ? "INVALID_SOLANA_PLAN"
                                                : "INVALID_BRZ_PLAN";
    if (!state.wallet.review_signing(payload, prompt))
        return {};
    prompt.request.connection = connection;
    if (prompt.request.local_phrase) {
        if (password)
            return {};
    } else {
        if (!cJSON_IsString(password) || !password->valuestring ||
            std::strlen(password->valuestring) > 1024 ||
            !prompt.request.password.assign(password->valuestring))
            return {};
        wipe(password->valuestring, std::strlen(password->valuestring));
        cJSON_DeleteItemFromObjectCaseSensitive(object.get(), "password");
    }
    char* raw = cJSON_PrintUnformatted(object.get());
    if (!raw)
        return {};
    const bool assigned = prompt.request.payload.assign(raw, 180000);
    json_free(raw);
    if (!assigned)
        return {};
    return wait_for_signature(std::move(prompt), connection, error);
}
std::string NimbleDeviceService::request_monero(const MoneroRequest& request,
                                                std::uint64_t connection, std::string& error) {
    TransactionPrompt prompt;
    prompt.coin = state_->wallet.monero_wallet_name(request.wallet_id);
    if (prompt.coin.empty()) {
        error = "WALLET_NOT_FOUND";
        return {};
    }
    prompt.request.connection = connection;
    prompt.request.monero = request.operation;
    prompt.request.monero_mode = request.mode;
    prompt.request.wallet_id = request.wallet_id;
    if (request.operation == MoneroOperation::SignTransfer) {
        prompt.request.monero_recipient = request.recipient;
        prompt.request.monero_amount = request.amount;
        prompt.request.monero_fee = request.fee;
        prompt.recipient = prompt.request.monero_recipient;
        prompt.amount = std::to_string(request.amount);
        prompt.fee = std::to_string(request.fee);
        prompt.symbol = "XMR";
        prompt.decimals = 12;
    }
    if (!prompt.request.payload.assign(request.archive_hex, monero_max_archive_hex)) {
        error = "PAYLOAD_TOO_LARGE";
        return {};
    }
    return wait_for_signature(std::move(prompt), connection, error);
}
std::string NimbleDeviceService::wait_for_signature(TransactionPrompt&& prompt,
                                                    std::uint64_t connection, std::string& error) {
    auto& state = *state_;
    const bool monero = prompt.request.monero.has_value();
    error = "REQUEST_TIMEOUT";
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        if (state.signing_connection) {
            error = "SIGNING_BUSY";
            return {};
        }
        state.signing_connection = connection;
        state.signing_error.clear();
        state.transaction_prompt.emplace(std::move(prompt));
    }
    const auto started = now_ms(), epoch = state.wallet.public_authorization_epoch();
    std::string encoded;
    while (now_ms() - started < 180000 && !state.stopping) {
        const auto peer = state.current();
        if (!peer.connected || peer.connection != connection || !peer.authenticated ||
            !peer.encrypted || epoch != state.wallet.public_authorization_epoch()) {
            error = "DEVICE_DISCONNECTED";
            break;
        }
        std::optional<SigningJob> job;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            if (state.signing_outcome) {
                auto outcome = std::move(*state.signing_outcome);
                state.signing_outcome.reset();
                if (outcome.connection == connection && outcome.reason == SignEnd::Completed)
                    encoded = std::move(outcome.encoded);
                else {
                    wipe(outcome.encoded.data(), outcome.encoded.size());
                    error = state.signing_error.empty()
                                ? (monero ? "USER_REJECTED" : "TRANSACTION_REJECTED")
                                : state.signing_error;
                }
                break;
            }
            if (state.signing_job) {
                job.emplace(std::move(*state.signing_job));
                state.signing_job.reset();
            }
        }
        if (job) {
            SigningResult result{job->token, connection, {}, {}, false};
            auto& request = job->request;
            if (request.monero) {
                result.success = state.wallet.run_monero(
                    {*request.monero, request.monero_mode, request.wallet_id,
                     request.payload.view(), request.monero_recipient, request.monero_amount,
                     request.monero_fee},
                    request.password.view(), result.encoded, result.error);
            } else {
                result.success = state.wallet.sign_transaction(
                    request.payload.view(), request.password.view(), result.encoded);
            }
            job.reset();
            if (!result.success && result.error.empty())
                result.error = "SIGNING_FAILED";
            std::lock_guard<std::mutex> lock(state.mutex);
            state.signing_result.emplace(std::move(result));
        } else
            vTaskDelay(pdMS_TO_TICKS(20));
    }
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        if (state.signing_result)
            wipe(state.signing_result->encoded.data(), state.signing_result->encoded.size());
        if (state.signing_outcome)
            wipe(state.signing_outcome->encoded.data(), state.signing_outcome->encoded.size());
        state.transaction_prompt.reset();
        state.signing_job.reset();
        state.signing_result.reset();
        state.signing_outcome.reset();
        state.signing_connection = 0;
    }
    return encoded;
}
std::optional<TransactionPrompt> NimbleDeviceService::poll_transaction() {
    std::lock_guard<std::mutex> lock(state_->mutex);
    auto value = std::move(state_->transaction_prompt);
    state_->transaction_prompt.reset();
    return value;
}
bool NimbleDeviceService::sign(SigningJob job) {
    std::lock_guard<std::mutex> lock(state_->mutex);
    if (state_->signing_connection != job.request.connection || state_->signing_job)
        return false;
    state_->signing_job.emplace(std::move(job));
    return true;
}
std::optional<SigningResult> NimbleDeviceService::poll_signature() {
    std::lock_guard<std::mutex> lock(state_->mutex);
    auto value = std::move(state_->signing_result);
    state_->signing_result.reset();
    return value;
}
void NimbleDeviceService::respond_transaction(SignOutcome outcome, std::string_view error) {
    std::lock_guard<std::mutex> lock(state_->mutex);
    if (state_->signing_connection == outcome.connection) {
        state_->signing_error = error;
        state_->signing_outcome.emplace(std::move(outcome));
    } else
        wipe(outcome.encoded.data(), outcome.encoded.size());
}
bool NimbleDeviceService::notify_public(std::string_view fragment) {
    auto& state = *state_;
    const auto expected = state.current();
    if (!expected.connected || !expected.authenticated || !expected.encrypted || fragment.empty() ||
        expected.mtu < 23 ||
        fragment.size() > std::min<std::size_t>(config::notification_bytes, expected.mtu - 3))
        return false;
    const auto deadline = now_ms() + 2000;
    while (now_ms() < deadline) {
        const auto peer = state.current();
        if (!peer.connected || peer.connection != expected.connection || !peer.authenticated ||
            !peer.encrypted)
            return false;
        while (xSemaphoreTake(state.tx_done, 0) == pdTRUE) {
        }
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            if (!state.subscribed || state.tx_pending)
                return false;
            state.tx_status = BLE_HS_ENOTCONN;
            state.tx_connection = expected.connection;
            state.tx_length = fragment.size();
            std::copy(fragment.begin(), fragment.end(), state.tx_bytes.begin());
            state.tx_pending = true;
        }
        ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &state.tx_event);
        if (xSemaphoreTake(state.tx_done, pdMS_TO_TICKS(2000)) != pdTRUE)
            return false;
        int result;
        {
            std::lock_guard<std::mutex> lock(state.mutex);
            result = state.tx_status;
        }
        if (result == BLE_HS_ENOMEM || result == BLE_HS_EAGAIN) {
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }
        if (result)
            return false;
        // Keep the original 180 bytes / 50 ms pacing, scaled for smaller MTUs.
        // A fixed 50 ms per 20-byte fragment would exceed the app's RPC timeout
        // for the HIDDEN pool. Only explicitly refused fragments are retried.
        const auto pacing_ms =
            (50 * fragment.size() + config::notification_bytes - 1) / config::notification_bytes;
        vTaskDelay(std::max<TickType_t>(1, pdMS_TO_TICKS(pacing_ms)));
        const auto current = state.current();
        std::lock_guard<std::mutex> lock(state.mutex);
        return current.connected && current.connection == expected.connection &&
               current.authenticated && current.encrypted && state.subscribed &&
               state.tx_status == 0;
    }
    return false;
}
std::vector<TrustedDevice> NimbleDeviceService::trusted_devices() {
    std::vector<TrustedDevice> result;
    if (!state_->started)
        return result;
    const auto current = connection_identity();
    {
        std::lock_guard<std::mutex> lock(state_->access_mutex);
        for (const auto& entry : state_->access_list.entries())
            result.push_back({entry.id, entry.id,
                              state_->current().connected && entry.id == current, entry.blocked});
    }
    std::array<ble_addr_t, CONFIG_BT_NIMBLE_MAX_BONDS> peers{};
    int count = 0;
    if (!ble_store_util_bonded_peers(peers.data(), &count, peers.size())) {
        for (int i = 0; i < count; ++i) {
            const auto id = peer_id(peers[i]);
            if (std::none_of(result.begin(), result.end(),
                             [&](const auto& d) { return d.id == id; }))
                result.push_back({id, id, state_->current().connected && id == current, false});
        }
    }
    return result;
}
std::uint64_t NimbleDeviceService::devices_revision() const {
    return state_->revision.load();
}
bool NimbleDeviceService::remove_device(std::string_view id) {
    std::lock_guard<std::mutex> operation(state_->removal_mutex);
    if (!state_->started || state_->stopping || !state_->unlocked)
        return false;
    while (xSemaphoreTake(state_->removal_done, 0) == pdTRUE) {
    }
    {
        std::lock_guard<std::mutex> lock(state_->mutex);
        state_->removal_id = id;
        state_->removal_result = false;
    }
    ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &state_->removal_event);
    xSemaphoreTake(state_->removal_done, portMAX_DELAY);
    std::lock_guard<std::mutex> lock(state_->mutex);
    return state_->removal_result;
}
bool NimbleDeviceService::block_device(std::string_view id) {
    if (!state_->started)
        return false;
    // Retain bond keys so privacy addresses can still resolve to the blocked ID.
    const bool saved = state_->record_access(id, true);
    if (!saved || connection_identity() == id)
        state_->terminate();
    return saved;
}
bool NimbleDeviceService::accept_current_device(std::uint64_t connection) {
    std::string id;
    PublicPeer peer;
    {
        std::lock_guard<std::mutex> lock(state_->mutex);
        if (state_->peer.connection != connection || !state_->peer.connected || state_->blocked)
            return false;
        id = state_->identity;
        peer = state_->peer;
    }
    if (state_->denied(id)) {
        state_->terminate(connection);
        return false;
    }
    // A first pairing can reveal its permanent identity only after key exchange.
    if (peer.authenticated && peer.encrypted && !state_->record_access(id, false)) {
        state_->terminate(connection);
        return false;
    }
    const auto current = state_->current();
    return current.connected && current.connection == connection;
}
bool NimbleDeviceService::reject_current_device(std::uint64_t connection) {
    std::string id;
    {
        std::lock_guard<std::mutex> lock(state_->mutex);
        if (state_->peer.connection != connection || !state_->peer.connected)
            return false;
        id = state_->identity;
    }
    const bool saved = state_->record_access(id, true);
    state_->terminate(connection);
    return saved;
}
std::string NimbleDeviceService::connection_identity() const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    return state_->identity;
}
std::optional<PairingRequest> NimbleDeviceService::poll_pairing() {
    std::lock_guard<std::mutex> lock(state_->mutex);
    if (!state_->pairing || state_->pairing_delivered)
        return std::nullopt;
    state_->pairing_delivered = true;
    return state_->pairing;
}
bool NimbleDeviceService::pairing_active(std::uint64_t token) const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    return state_->pairing && state_->pairing->token == token && state_->peer.connected &&
           !state_->blocked && now_ms() < state_->pairing_deadline;
}
void NimbleDeviceService::respond_pairing(std::uint64_t token, bool accept) {
    {
        std::lock_guard<std::mutex> lock(state_->mutex);
        if (!state_->started || !state_->pairing || state_->pairing->token != token ||
            state_->blocked || state_->decision_pending)
            return;
        state_->decision_accept = accept;
        state_->decision_token = token;
        state_->decision_connection = state_->peer.connection;
        state_->decision_pending = true;
    }
    ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &state_->pairing_event);
}
bool NimbleDeviceService::connected(std::uint64_t connection) const {
    const auto peer = state_->current();
    return peer.connected && peer.connection == connection;
}
} // namespace airlock
