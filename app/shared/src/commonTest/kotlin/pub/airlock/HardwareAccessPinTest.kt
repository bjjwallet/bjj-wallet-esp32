package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.ble.*
import pub.airlock.data.*
import pub.airlock.domain.*

class HardwareAccessPinTest {
    private class Connection : HardwareConnection {
        var received: JsonObject? = null
        var failure: String? = null
        var accepted = true

        override suspend fun discover() = emptyList<HardwareDevice>()

        override suspend fun connect(id: String) {}

        override suspend fun disconnect() {}

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            assertEquals("verify_pin", method)
            received = params
            failure?.let { throw HardwareRejectedException("Test rejection", it) }
            return buildJsonObject { put("accepted", accepted) }
        }
    }

    @Test
    fun missingOrOptionalHardwarePinCannotBypassMandatoryAccess() = runTest {
        for (required in listOf<Boolean?>(null, false, true)) {
            val connection =
                object : HardwareConnection by Connection() {
                    override suspend fun request(method: String, params: JsonObject): JsonElement =
                        buildJsonObject {
                            required?.let { put("pin_required", it) }
                        }
                }
            val hardware = ProductionHardwareWallet(connection, DemoPublicKeyDeriver())
            if (required == true) assertTrue(hardware.requiresAccessPin())
            else
                assertEquals(
                    InputError.HARDWARE_PIN_SETUP_REQUIRED,
                    assertFailsWith<InputException> { hardware.requiresAccessPin() }.reason,
                )
        }
    }

    @Test
    fun accessPinIsSentUnchangedIncludingLeadingZeroes() = runTest {
        val connection = Connection()
        val hardware = ProductionHardwareWallet(connection, DemoPublicKeyDeriver())
        assertTrue(hardware.unlockAccess("0Ab123"))
        assertEquals(buildJsonObject { put("pin", "0Ab123") }, connection.received)
        connection.accepted = false
        assertFalse(hardware.unlockAccess("0Ab123"))
    }

    @Test
    fun deviceErrorsAreNotClassifiedAsAnIncorrectPin() = runTest {
        val connection = Connection()
        val hardware = ProductionHardwareWallet(connection, DemoPublicKeyDeriver())
        for ((code, expected) in
            mapOf(
                "PIN_LOCKED" to InputError.HARDWARE_PIN_LOCKED,
                "WALLET_BUSY" to InputError.HARDWARE_BUSY,
                "DEVICE_APPROVAL_REQUIRED" to InputError.HARDWARE_APPROVAL_REQUIRED,
                "DEVICE_DISCONNECTED" to InputError.DEVICE_DISCONNECTED,
                "METHOD_NOT_FOUND" to InputError.INTEGRATION_UNAVAILABLE,
                "INTERNAL_ERROR" to InputError.HARDWARE_FAILURE,
            )) {
            connection.failure = code
            assertEquals(
                expected,
                assertFailsWith<InputException> { hardware.unlockAccess("0Ab123") }.reason,
            )
        }
    }

    @Test
    fun publicExportFailureAfterAnAcceptedPinIsNotReportedAsAnIncorrectPin() = runTest {
        val connection =
            object : HardwareConnection by Connection() {
                override suspend fun request(method: String, params: JsonObject): JsonElement =
                    when (method) {
                        "get_device_info" -> buildJsonObject { put("wallet_sync_version", 1) }
                        "verify_pin" -> buildJsonObject { put("accepted", true) }
                        "get_wallets" ->
                            throw HardwareRejectedException(
                                "Test export failure",
                                "PUBLIC_DATA_INVALID",
                            )
                        else -> error("Unexpected test request")
                    }
            }
        val hardware = ProductionHardwareWallet(connection, DemoPublicKeyDeriver())
        assertTrue(hardware.unlockAccess("0Ab123"))
        assertEquals(
            InputError.PUBLIC_DATA_INVALID,
            assertFailsWith<InputException> { hardware.synchronizePublicWallets() }.reason,
        )
    }
}
