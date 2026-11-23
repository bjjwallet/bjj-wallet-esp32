@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pub.airlock.ble.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class UtxoPaymentFlowTest {
    @Test
    fun hardwareReceivesExactPlanAndAlwaysDisconnects() = runTest {
        val vector = utxoVectors().first()
        val quote = utxoQuote(vector)
        val raw = vector.jsonObject.getValue("signed").jsonPrimitive.content
        var disconnects = 0
        var version = 1
        var rejected = false
        val signRequests = mutableListOf<JsonObject>()
        val connection =
            object : HardwareConnection {
                override suspend fun discover() = listOf(HardwareDevice("test", "Public fixture"))

                override suspend fun connect(id: String) = Unit

                override suspend fun disconnect() {
                    disconnects++
                }

                override suspend fun request(method: String, params: JsonObject): JsonElement =
                    when (method) {
                        "get_accounts" -> PublicWalletFixtures.response(WalletMode.STANDARD)
                        "get_device_info" ->
                            buildJsonObject { put("utxo_signing_version", version) }
                        "sign_tx" -> {
                            signRequests += params
                            if (rejected) throw HardwareRejectedException("Rejected")
                            JsonPrimitive(raw)
                        }
                        else -> error("Unexpected RPC")
                    }
            }
        val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
        hardware.connect("test")
        hardware.synchronize(WalletMode.STANDARD)
        assertEquals(raw, hardware.sign(quote, "TestPin123!", WalletMode.STANDARD))
        val request = signRequests.single()
        assertEquals("TestPin123!", request.getValue("password").jsonPrimitive.content)
        assertEquals(
            quote.utxoPlan!!.fee.toString(),
            request.getValue("txData").jsonObject.getValue("fee").jsonPrimitive.content,
        )
        assertEquals(
            quote.utxoPlan!!.inputs.single().previousTransaction,
            request
                .getValue("txData")
                .jsonObject
                .getValue("utxos")
                .jsonArray
                .single()
                .jsonObject
                .getValue("previousTransaction")
                .jsonPrimitive
                .content,
        )
        assertEquals(2, disconnects)
        version = 0
        assertEquals(
            InputError.INTEGRATION_UNAVAILABLE,
            assertFailsWith<InputException> {
                    hardware.sign(quote, "TestPin123!", WalletMode.STANDARD)
                }
                .reason,
        )
        assertEquals(1, signRequests.size)
        version = 1
        rejected = true
        assertEquals(
            InputError.TRANSACTION_REJECTED,
            assertFailsWith<InputException> {
                    hardware.sign(quote, "TestPin123!", WalletMode.STANDARD)
                }
                .reason,
        )
        assertEquals(4, disconnects)
    }

    @Test
    fun confirmationJournalsBeforeHttpAndRestartResumesWithoutNewSignature() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vector = utxoVectors().first()
        val quote = utxoQuote(vector)
        val raw = vector.jsonObject.getValue("signed").jsonPrimitive.content
        var signs = 0
        var sends = 0
        var known = false
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        vault.update {
            it.copy(
                appSecurityConfigured = true,
                accounts =
                    listOf(
                        Account(
                            quote.asset.accountId,
                            "bitcoin",
                            "Bitcoin",
                            "BTC",
                            "",
                            WalletMode.STANDARD,
                        )
                    ),
                assets = listOf(quote.asset),
            )
        }
        val client =
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        this.dispatcher = dispatcher
                        addHandler { request ->
                            if (request.method == HttpMethod.Post) {
                                assertEquals(
                                    raw,
                                    vault.snapshot().pendingSubmissions.single().signedTransaction,
                                )
                                sends++
                                respond("Timeout", HttpStatusCode.ServiceUnavailable)
                            } else if (known)
                                respond(
                                    """{"txid":"${vault.snapshot().pendingSubmissions.single().txId}","status":{"confirmed":true}}""",
                                    HttpStatusCode.OK,
                                )
                            else respond("Not found", HttpStatusCode.NotFound)
                        }
                    }
                )
            )
        val production = ProductionWalletNetwork(client) { vault.snapshot() }
        val network =
            object : WalletNetwork by production {
                override suspend fun prices(symbols: List<String>) = emptyMap<String, String>()

                override suspend fun estimate(asset: Asset, destination: String, amount: String) =
                    quote
            }
        val hardware =
            object : HardwareWallet by DemoHardwareWallet() {
                override val simulated = false

                override suspend fun sign(
                    quote: TransferQuote,
                    paymentPin: String,
                    mode: WalletMode,
                ): String {
                    signs++
                    return raw
                }
            }
        try {
            val vm = WalletViewModel(vault, hardware, network, DemoPublicKeyDeriver())
            advanceUntilIdle()
            vm.open(vm.state.value.assets.single())
            vm.send()
            val typedDigits = AtomicAmountInput.restore(quote.amount, quote.asset.decimals)
            val maskedAmount = AtomicAmountInput.decimal(typedDigits, quote.asset.decimals, "pt")
            vm.prepareTransfer(quote.destination, maskedAmount)
            advanceUntilIdle()
            assertEquals(quote.amount, vm.state.value.quote?.amount)
            assertEquals(Screen.CONFIRM, vm.state.value.screen)
            vm.confirmTransfer("TestPin123!")
            advanceUntilIdle()
            assertEquals(1, signs, "signature: ${vm.state.value.error}")
            assertEquals(
                1,
                sends,
                "broadcast: ${vm.state.value.error}; pending=${vault.snapshot().pendingSubmissions.size}",
            )
            val pending = vault.snapshot().pendingSubmissions.single()
            assertEquals(InputError.SUBMISSION_PENDING, vm.state.value.networkFailure)
            vm.lock()
            advanceUntilIdle()
            val restoredVault = VaultRepository(storage, TestCrypto(), dispatcher)
            val restored = WalletViewModel(restoredVault, hardware, network, DemoPublicKeyDeriver())
            advanceUntilIdle()
            assertEquals(1, signs)
            assertEquals(1, sends)
            known = true
            restored.retryPending(pending.txId)
            advanceUntilIdle()
            assertEquals(1, signs)
            assertEquals(1, sends)
            assertTrue(restoredVault.snapshot().pendingSubmissions.isEmpty())
            assertEquals(
                TransferStatus.CONFIRMED,
                restoredVault.snapshot().transfers.single().status,
            )
            assertEquals("1", restoredVault.snapshot().assets.single().balance)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }
}
