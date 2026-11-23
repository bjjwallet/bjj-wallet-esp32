@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import fr.acinq.bitcoin.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.data.network.*
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.domain.*
import pub.airlock.presentation.*

class BitcoinSignatureTestTest {
    // Public test key only: this source set is never packaged in the app.
    private val key = PrivateKey(ByteArray(32).also { it[31] = 1 })
    private val fixture = utxoQuote(utxoVectors().first())
    private val asset
        get() =
            fixture.asset.copy(
                balance = "0",
                address = key.publicKey().p2wpkhAddress(Block.LivenetGenesisBlock.hash),
            )

    private fun quote() =
        BitcoinSignatureTest.quote(asset, fixture.destination, "1", enabled = true)

    private fun signed(q: TransferQuote): String {
        val plan = q.utxoPlan!!
        val input = plan.inputs.single()
        val tx =
            Transaction(
                2,
                listOf(TxIn(OutPoint(TxId(input.txId), input.index), 0xffffffffL)),
                listOf(
                    TxOut(
                        Satoshi(Amount.atomic(q.amount, 8).toLong()),
                        ByteVector(plan.recipientScript),
                    ),
                    TxOut(Satoshi(plan.change), ByteVector(plan.changeScript)),
                ),
                0,
            )
        val signature =
            tx.signInput(
                0,
                Script.pay2pkh(key.publicKey().hash160()),
                1,
                Satoshi(input.amount),
                SigVersion.SIGVERSION_WITNESS_V0,
                key,
            )
        return Transaction.write(
                tx.copy(
                    txIn =
                        listOf(
                            tx.txIn
                                .single()
                                .copy(
                                    witness =
                                        ScriptWitness(
                                            listOf(ByteVector(signature), key.publicKey().value)
                                        )
                                )
                        )
                )
            )
            .hex()
    }

    @Test
    fun syntheticInputNeedsNoBalanceAndKeepsCryptographicChecks() {
        val q = quote()
        BitcoinSignatureTest.validate(q, enabled = true)
        val parent = Transaction.read(q.utxoPlan!!.inputs.single().previousTransaction)
        assertEquals("00".repeat(32), parent.txIn.single().outPoint.txid.toString())
        assertEquals(0L, parent.txIn.single().outPoint.index)
        assertFailsWith<InputException> { BitcoinSignatureTest.validate(q, enabled = false) }
        assertFailsWith<InputException> {
            BitcoinSignatureTest.validate(q.copy(amount = "2"), enabled = true)
        }
        assertFailsWith<InputException> {
            BitcoinSignatureTest.quote(
                asset.copy(coin = "litecoin"),
                fixture.destination,
                "1",
                true,
            )
        }
        val raw = signed(q)
        assertEquals(Transaction.read(raw).txid.toString(), UtxoStrategy.transactionId(raw, q))
        val tx = Transaction.read(raw)
        val tampered =
            tx.copy(
                txOut =
                    tx.txOut.mapIndexed { i, out ->
                        if (i == 0) out.copy(amount = Satoshi(1)) else out
                    }
            )
        assertFailsWith<InputException> {
            UtxoStrategy.transactionId(Transaction.write(tampered).hex(), q)
        }
    }

    @Test
    fun broadcastUsesSignedBytesAndDistinguishesRejectionFromOutage() = runTest {
        val q = quote()
        val raw = signed(q)
        for ((status, expected) in
            listOf(
                HttpStatusCode.BadRequest to InputError.TRANSACTION_REJECTED,
                HttpStatusCode.ServiceUnavailable to InputError.PROVIDER_UNAVAILABLE,
            )) {
            var posts = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals(HttpMethod.Post, request.method)
                        assertEquals("/api/tx", request.url.encodedPath)
                        assertEquals(raw, request.body.toByteArray().decodeToString())
                        posts++
                        respond("diagnostic failure", status)
                    }
                )
            try {
                val network = ProductionWalletNetwork(client) { VaultSnapshot() }
                assertEquals(
                    expected,
                    assertFailsWith<InputException> { network.broadcast(raw, q) }.reason,
                )
                assertEquals(1, posts)
                assertFailsWith<InputException> { network.broadcast("00", q) }
                assertEquals(1, posts, "Invalid signatures must never reach HTTP")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun zeroBalanceFlowSignsBeforeSubmissionAndNeverCreatesAPayment() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val order = mutableListOf<String>()
            var broadcastFailure = InputError.TRANSACTION_REJECTED
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            vault.update {
                it.copy(
                    appSecurityConfigured = true,
                    accounts =
                        listOf(
                            Account(
                                asset.accountId,
                                "bitcoin",
                                "Bitcoin",
                                "BTC",
                                "",
                                WalletMode.STANDARD,
                            )
                        ),
                    assets = listOf(asset),
                )
            }
            val production = ProductionWalletNetwork()
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun estimate(
                        asset: Asset,
                        destination: String,
                        amount: String,
                    ): TransferQuote {
                        throw InputException(InputError.INSUFFICIENT_BALANCE)
                    }

                    override fun transactionId(
                        signedTransaction: String,
                        quote: TransferQuote,
                    ): String {
                        order += "verified"
                        return production.transactionId(signedTransaction, quote)
                    }

                    override suspend fun broadcast(
                        signedTransaction: String,
                        quote: TransferQuote,
                    ): String {
                        order += "broadcast"
                        assertEquals(signed(quote), signedTransaction)
                        throw InputException(broadcastFailure)
                    }
                }
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override val simulated = false

                    override suspend fun sign(
                        quote: TransferQuote,
                        paymentPin: String,
                        mode: WalletMode,
                    ): String {
                        order += "hardware.sign"
                        return signed(quote)
                    }
                }
            val vm =
                WalletViewModel(
                    vault,
                    hardware,
                    network,
                    DemoPublicKeyDeriver(),
                    transferTestMode = true,
                )
            advanceUntilIdle()
            vm.open(vm.state.value.assets.single())
            vm.send()
            vm.prepareTransfer(fixture.destination, "1")
            advanceUntilIdle()
            assertEquals(Screen.CONFIRM, vm.state.value.screen)
            assertTrue(vm.state.value.quote!!.bitcoinSignatureTest)
            assertTrue(order.isEmpty(), "Preparation cannot sign without user confirmation")
            vm.confirmTransfer("TestPin123!")
            advanceUntilIdle()
            assertEquals(listOf("hardware.sign", "verified", "broadcast"), order)
            assertNull(vm.state.value.error)
            assertNull(vm.state.value.networkFailure)
            assertEquals(WalletMessage.BITCOIN_SIGNATURE_TEST_COMPLETED, vm.state.value.message)
            assertTrue(vm.state.value.bitcoinTestSignatureVerified)
            assertEquals("0", vault.snapshot().assets.single().balance)
            assertTrue(vault.snapshot().pendingSubmissions.isEmpty())
            assertTrue(vault.snapshot().transfers.isEmpty())

            broadcastFailure = InputError.PROVIDER_UNAVAILABLE
            vm.send()
            vm.prepareTransfer(fixture.destination, "1")
            advanceUntilIdle()
            vm.confirmTransfer("TestPin123!")
            advanceUntilIdle()
            assertEquals(InputError.PROVIDER_UNAVAILABLE, vm.state.value.error)
            assertNull(
                vm.state.value.message,
                "API outages must not become expected test rejection",
            )
            assertTrue(vault.snapshot().transfers.isEmpty())
            assertTrue(vault.snapshot().pendingSubmissions.isEmpty())

            val normal =
                WalletViewModel(
                    vault,
                    hardware,
                    network,
                    DemoPublicKeyDeriver(),
                    transferTestMode = false,
                )
            advanceUntilIdle()
            normal.open(normal.state.value.assets.single())
            normal.send()
            normal.prepareTransfer(fixture.destination, "1")
            advanceUntilIdle()
            assertEquals(InputError.INSUFFICIENT_BALANCE, normal.state.value.error)
            assertEquals(6, order.size)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
