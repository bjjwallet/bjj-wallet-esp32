@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class TransferFlowTest {
    @Test
    fun approvalSheetWaitsForHardwareThenSuccessOpensRefreshableTransactions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vector = utxoVectors().first()
        val quote = utxoQuote(vector)
        val raw = vector.jsonObject.getValue("signed").jsonPrimitive.content
        val hash = ProductionWalletNetwork().transactionId(raw, quote)
        val approved = CompletableDeferred<Unit>()
        val progress = MutableStateFlow(TransferStage.CONNECTING)
        var sends = 0
        var historyReads = 0
        var confirmed = false
        var reject = false
        var disconnected = true
        val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
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
        lateinit var vm: WalletViewModel
        val hardware =
            object : HardwareWallet by DemoHardwareWallet() {
                override val simulated = false
                override val signingProgress = progress
                override val requiresSigningAccessPin = true

                override suspend fun withSigningAccessPin(
                    pin: String,
                    sign: suspend () -> String,
                ): String {
                    assertEquals("Aa1234", pin)
                    return sign()
                }

                override suspend fun sign(
                    quote: TransferQuote,
                    paymentPin: String,
                    mode: WalletMode,
                ): String {
                    if (disconnected) throw InputException(InputError.DEVICE_DISCONNECTED)
                    progress.value = TransferStage.APPROVAL
                    approved.await()
                    if (reject) throw InputException(InputError.TRANSACTION_REJECTED)
                    return raw
                }
            }
        val network =
            object : WalletNetwork {
                override suspend fun prices(symbols: List<String>) = emptyMap<String, String>()

                override suspend fun balance(asset: Asset) = asset.balance

                override suspend fun estimate(asset: Asset, destination: String, amount: String) =
                    quote

                override fun transactionId(signedTransaction: String, quote: TransferQuote) =
                    ProductionWalletNetwork().transactionId(signedTransaction, quote)

                override suspend fun broadcast(signedTransaction: String): String =
                    error("Quote required")

                override suspend fun broadcast(
                    signedTransaction: String,
                    quote: TransferQuote,
                ): String {
                    assertEquals(TransferStage.SUBMITTING, vm.state.value.transferStage)
                    assertEquals(raw, signedTransaction)
                    sends++
                    return hash
                }

                override suspend fun transactionHistory(
                    asset: Asset,
                    cursor: String?,
                ): TransactionPage {
                    historyReads++
                    return TransactionPage(
                        listOf(
                            Transfer(
                                hash,
                                asset.id,
                                quote.amount,
                                quote.destination,
                                status =
                                    if (confirmed) TransferStatus.CONFIRMED
                                    else TransferStatus.SUBMITTED,
                            )
                        ),
                        null,
                    )
                }
            }
        try {
            vm = WalletViewModel(vault, hardware, network, DemoPublicKeyDeriver())
            advanceUntilIdle()
            vm.open(vm.state.value.assets.single())
            vm.send()
            vm.prepareTransfer(quote.destination, quote.amount)
            advanceUntilIdle()
            vm.requestTransferConnection()
            assertEquals(TransferStage.CONNECTION_REQUIRED, vm.state.value.transferStage)
            assertEquals(0, sends)
            vm.cancelTransferConnection()
            assertNull(vm.state.value.transferStage)
            vm.requestTransferConnection()
            vm.confirmTransfer("TestPin123!", "Aa1234")
            advanceUntilIdle()
            assertEquals(TransferStage.CONNECTION_REQUIRED, vm.state.value.transferStage)
            assertEquals(Screen.CONFIRM, vm.state.value.screen)
            assertEquals(InputError.DEVICE_DISCONNECTED, vm.state.value.transferConnectionError)
            assertNull(
                vm.state.value.error,
                "Connection retry belongs in the sheet, without requiring synchronization",
            )
            assertFalse(vm.state.value.busy)
            assertEquals(0, sends)
            assertTrue(vault.snapshot().pendingSubmissions.isEmpty())
            disconnected = false
            vm.confirmTransfer("TestPin123!", "Aa1234")
            runCurrent()
            assertEquals(TransferStage.APPROVAL, vm.state.value.transferStage)
            assertEquals(quote, vm.state.value.signingQuote)
            assertEquals(0, sends, "No broadcast before physical approval")
            approved.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, sends)
            assertEquals(Screen.TRANSACTIONS, vm.state.value.screen)
            assertNull(vm.state.value.transferStage)
            assertTrue(vault.snapshot().pendingSubmissions.isEmpty())
            assertEquals(TransferStatus.SUBMITTED, vm.state.value.transfers.single().status)
            confirmed = true
            vm.refreshTransactions()
            advanceUntilIdle()
            assertEquals(1, historyReads)
            val remote = vm.state.value.chainHistory.getValue(quote.asset.id).transfers
            val rows = TransferHistory.merge(vm.state.value.transfers, remote)
            assertEquals(1, rows.size)
            assertEquals(TransferStatus.CONFIRMED, rows.single().status)
            assertFalse(vm.state.value.refreshingTransactions)

            reject = true
            vm.back()
            vm.send()
            vm.prepareTransfer(quote.destination, quote.amount)
            advanceUntilIdle()
            vm.confirmTransfer("TestPin123!", "Aa1234")
            advanceUntilIdle()
            assertEquals(1, sends, "Hardware rejection must not broadcast")
            assertEquals(InputError.TRANSACTION_REJECTED, vm.state.value.error)
            assertNull(vm.state.value.transferStage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun esploraHistoryReportsReceiptAndConfirmationWithoutCountingChangeOrFeeAsAmount() = runTest {
        val q = utxoQuote(utxoVectors().first())
        val sender = q.utxoPlan!!.changeScript
        val recipient = q.utxoPlan!!.recipientScript
        val sentHash = "ab".repeat(32)
        val receivedHash = "cd".repeat(32)
        var confirmed = false
        for (provider in listOf(NetworkProvider.BLOCKSTREAM, NetworkProvider.MEMPOOL)) {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals(
                            provider.serviceUrl("") + "/address/${q.asset.address}/txs",
                            request.url.toString(),
                        )
                        respond(
                            """[{"txid":"$sentHash","vin":[{"prevout":{"scriptpubkey":"$sender","value":100000000}}],
                    "vout":[{"scriptpubkey":"$recipient","scriptpubkey_address":"${q.destination}","value":50000000},
                        {"scriptpubkey":"$sender","value":49990000}],"status":{"confirmed":$confirmed}},
                    {"txid":"$receivedHash","vin":[{"prevout":null}],"vout":[{"scriptpubkey":"$sender","value":1000}],
                    "status":{"confirmed":true}}]""",
                            HttpStatusCode.OK,
                        )
                    }
                )
            try {
                val network =
                    ProductionWalletNetwork(client) {
                        VaultSnapshot(networkSettings = NetworkSettings(provider))
                    }
                confirmed = false
                val first = network.transactionHistory(q.asset).transfers
                assertEquals("0.5", first.first().amount)
                assertFalse(first.first().incoming)
                assertEquals(TransferStatus.SUBMITTED, first.first().status)
                assertEquals("0.00001", first.last().amount)
                assertTrue(first.last().incoming)
                confirmed = true
                assertEquals(
                    TransferStatus.CONFIRMED,
                    network.transactionHistory(q.asset).transfers.first().status,
                )
                assertEquals(
                    1,
                    TransferHistory.merge(
                            listOf(first.first()),
                            listOf(first.first().copy(status = TransferStatus.CONFIRMED)),
                        )
                        .size,
                )
            } finally {
                client.close()
            }
        }
    }
}
