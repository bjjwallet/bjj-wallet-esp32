@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import org.kotlincrypto.hash.sha3.Keccak256
import pub.airlock.data.*
import pub.airlock.data.network.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

private fun ByteArray.hexText() =
    joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

private fun selector(signature: String) =
    "0x" + Keccak256().digest(signature.encodeToByteArray()).copyOf(4).hexText()

private fun word(value: String) =
    rpc(EvmEncoding.hex(value).drop(2).padStart(64, '0').let { "0x$it" })

/** Public r/s from EIP-155, recovered against a Base message. No private key or signing code. */
private fun baseFixture(): Pair<String, TransferQuote> {
    val plan = publicQuote().evmPlan!!.copy(chainId = 8453, l1Fee = "1200", operatorFee = "120")
    val fields = LegacyRlp.decode(EvmEncoding.bytes(EIP155_RAW)).toMutableList()
    fields[6] = byteArrayOf(0x42, 0x2d) // 8453 * 2 + 35, recovery parity zero.
    val publicKey =
        Crypto.recoverPublicKey(
            ByteVector64(fields[7] + fields[8]),
            Keccak256().digest(EvmEncoding.unsigned(plan)),
            0,
        )
    val sender =
        "0x" +
            Keccak256()
                .digest(publicKey.toUncompressedBin().drop(1).toByteArray())
                .takeLast(20)
                .toByteArray()
                .hexText()
    val asset =
        evmAsset.copy(
            coin = "base",
            id = "base:0",
            accountId = "base",
            name = "Base",
            address = sender,
        )
    val quote =
        TransferQuote(asset, publicQuote().destination, "1", "0.00042000000000132", evmPlan = plan)
    return ("0x" + LegacyRlp.encode(fields).hexText()) to quote
}

private fun oracleReply(data: String): Reply =
    when (data.take(10)) {
        selector("getL1FeeUpperBound(uint256)") -> word("1000")
        selector("getOperatorFee(uint256)") -> word("100")
        else -> error("Unexpected oracle method")
    }

class BaseRpcTest {
    @Test
    fun configurationKeepsEachServersRequiredKeyAndPublicUrlSeparate() {
        var settings = NetworkSettings()
        assertFalse(NetworkProvider.AIRLOCK.requiresApiKey)
        assertTrue(settings.coinbaseKey.isEmpty()) // Old serialized settings use this default.
        for (provider in NetworkProvider.available.filter { it.requiresApiKey }) {
            assertEquals(
                InputError.API_KEY_REQUIRED,
                assertFailsWith<InputException> { settings.copy(preferred = provider).validated() }
                    .reason,
            )
            settings = settings.withKey(provider, "client_${provider.name}")
        }
        for (provider in NetworkProvider.available) {
            val selected = settings.copy(preferred = provider).validated()
            assertEquals(
                if (provider.requiresApiKey) "client_${provider.name}" else "",
                selected.keyFor(provider),
            )
            assertFalse(selected.toString().contains("client_"))
            assertFalse(provider.serviceUrl("https://custom.example").contains("client_"))
        }
        assertEquals(
            "https://api.moralis.com",
            NetworkProvider.MORALIS.serviceUrl("https://custom.example"),
        )
        for (invalid in
            listOf(
                "https://wrong.example/key",
                "key?extra=1",
                "key/other",
                "line\nbreak",
                "-----BEGIN PRIVATE KEY-----",
            )) {
            assertEquals(
                InputError.INVALID_API_KEY,
                assertFailsWith<InputException> {
                        settings
                            .copy(preferred = NetworkProvider.MORALIS, moralisKey = invalid)
                            .validated()
                    }
                    .reason,
            )
        }
    }

    @Test
    fun baseAndUsdcBalancesUseFixedMainnetRpcAndBindTheChain() = runTest {
        val asset = baseFixture().second.asset
        val token = Stablecoins.forNetwork("base").single { it.symbol == "USDC" }.asset(asset)
        val h =
            ProviderHarness(NetworkProvider.BLOCKSTREAM) { request ->
                assertEquals("mainnet.base.org", request.url.host)
                assertEquals("", request.url.encodedPath)
                when (request.methodName()) {
                    "eth_chainId" -> rpc("0x2105")
                    "eth_getBalance" -> rpc("0xde0b6b3a7640000")
                    "eth_call" -> {
                        val call = request.bodyJson()["params"]!!.jsonArray.first().jsonObject
                        assertEquals(
                            "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                            call["to"]!!.jsonPrimitive.content,
                        )
                        word("1234567")
                    }
                    else -> error("Unexpected method")
                }
            }
        try {
            assertEquals("1", h.network.balance(asset))
            assertEquals("1.234567", h.network.balance(token))
            h.calls.clear()
            val other = ProviderHarness { rpc("0x2105") }
            try {
                assertEquals(
                    InputError.NETWORK_MISMATCH,
                    assertFailsWith<InputException> { other.network.balance(evmAsset) }.reason,
                )
                assertEquals(1, other.calls.size)
                assertEquals("ethereum-rpc.publicnode.com", other.calls.single().url.host)
            } finally {
                other.close()
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun baseRejectsSepoliaAndAuthenticationFailuresWithoutSwitchingProvider() = runTest {
        for (reply in listOf(rpc("0x14a34"), Reply("credential", HttpStatusCode.Unauthorized))) {
            val h = ProviderHarness(NetworkProvider.BLOCKSTREAM) { reply }
            try {
                val error =
                    assertFailsWith<InputException> {
                        h.network.balance(baseFixture().second.asset)
                    }
                assertEquals(
                    if (reply.status == HttpStatusCode.OK) InputError.NETWORK_MISMATCH
                    else InputError.API_KEY_REQUIRED,
                    error.reason,
                )
                assertEquals(1, h.calls.size)
                assertFalse(error.toString().contains("coinbase_test_key"))
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun baseEstimateIncludesOracleFeesAndRejectsAStalePendingNonce() = runTest {
        var nonce = "0x9"
        val asset = baseFixture().second.asset
        val h =
            ProviderHarness(NetworkProvider.BLOCKSTREAM) { request ->
                when (request.methodName()) {
                    "eth_chainId" -> rpc("0x2105")
                    "eth_getTransactionCount" -> {
                        assertEquals(
                            "pending",
                            request.bodyJson()["params"]!!.jsonArray.last().jsonPrimitive.content,
                        )
                        rpc(nonce)
                    }
                    "eth_gasPrice" -> rpc("0x4a817c800")
                    "eth_estimateGas" -> rpc("0x5208")
                    "eth_call" -> {
                        val call = request.bodyJson()["params"]!!.jsonArray.first().jsonObject
                        assertEquals(BaseFees.oracle, call["to"]!!.jsonPrimitive.content)
                        oracleReply(call["data"]!!.jsonPrimitive.content)
                    }
                    else -> error("Unexpected method")
                }
            }
        try {
            val quote = h.network.estimate(asset, publicQuote().destination, "1")
            assertEquals("0.00050400000000132", quote.fee)
            assertEquals("1200", quote.evmPlan!!.l1Fee)
            assertEquals("120", quote.evmPlan.operatorFee)
            h.snapshot =
                h.snapshot.copy(
                    assets = listOf(asset),
                    transfers =
                        listOf(
                            Transfer(
                                "accepted",
                                asset.id,
                                "1",
                                quote.destination,
                                status = TransferStatus.SUBMITTED,
                                nonce = "0x9",
                            )
                        ),
                )
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> {
                        h.network.estimate(asset, publicQuote().destination, "1")
                    }
                    .reason,
            )
            // A payment of another token from the same sender also consumes the nonce.
            val token = Stablecoins.forNetwork("base").single { it.symbol == "USDC" }.asset(asset)
            h.snapshot =
                h.snapshot.copy(
                    assets = listOf(asset, token),
                    transfers =
                        listOf(
                            Transfer(
                                "token-accepted",
                                token.id,
                                "1",
                                quote.destination,
                                status = TransferStatus.SUBMITTED,
                                nonce = "0x9",
                            )
                        ),
                )
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> {
                        h.network.estimate(asset, publicQuote().destination, "1")
                    }
                    .reason,
            )
            nonce = "0xa"
            assertEquals(
                "0xa",
                h.network.estimate(asset, publicQuote().destination, "1").evmPlan!!.nonce,
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun missingFeeOracleFailsInsteadOfAssumingZeroL1Fee() = runTest {
        val h =
            ProviderHarness(NetworkProvider.BLOCKSTREAM) { request ->
                when (request.methodName()) {
                    "eth_chainId" -> rpc("0x2105")
                    "eth_getTransactionCount" -> rpc("0x0")
                    "eth_gasPrice" -> rpc("0x1")
                    "eth_estimateGas" -> rpc("0x5208")
                    else -> rpc("0x")
                }
            }
        try {
            assertEquals(
                InputError.PROVIDER_UNAVAILABLE,
                assertFailsWith<InputException> {
                        h.network.estimate(
                            baseFixture().second.asset,
                            publicQuote().destination,
                            "1",
                        )
                    }
                    .reason,
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun baseTimeoutManualRetryPreservesSignedBytesAndUsesFixedSequencer() = runTest {
        val (raw, quote) = baseFixture()
        val hash = EvmEncoding.transactionId(raw, quote)
        var submitted = false
        val h =
            ProviderHarness(NetworkProvider.BLOCKSTREAM) { request ->
                when (request.methodName()) {
                    "eth_chainId" -> rpc("0x2105")
                    "eth_getTransactionByHash",
                    "eth_getTransactionReceipt" -> rpc(JsonNull)
                    "eth_call" ->
                        oracleReply(
                            request
                                .bodyJson()["params"]!!
                                .jsonArray
                                .first()
                                .jsonObject["data"]!!
                                .jsonPrimitive
                                .content
                        )
                    "eth_sendRawTransaction" -> {
                        assertEquals(
                            raw,
                            request.bodyJson()["params"]!!.jsonArray.single().jsonPrimitive.content,
                        )
                        if (!submitted) {
                            submitted = true
                            throw IOException("Lost response")
                        }
                        rpc(hash)
                    }
                    else -> error("Unexpected method")
                }
            }
        try {
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> { h.network.broadcast(raw, quote) }.reason,
            )
            assertTrue(
                h.calls.all {
                    it.url.host in setOf("mainnet.base.org", "mainnet-sequencer.base.org")
                }
            )
            h.snapshot =
                h.snapshot.copy(
                    networkSettings =
                        h.snapshot.networkSettings.copy(preferred = NetworkProvider.MEMPOOL)
                )
            h.calls.clear()
            assertEquals(hash, h.network.broadcast(raw, quote))
            assertTrue(
                h.calls
                    .filter { it.methodName() != "eth_sendRawTransaction" }
                    .all { it.url.host == "mainnet.base.org" }
            )
            assertEquals(
                "mainnet-sequencer.base.org",
                h.calls.single { it.methodName() == "eth_sendRawTransaction" }.url.host,
            )
            assertEquals(
                listOf("eth_chainId", "eth_getTransactionByHash", "eth_getTransactionReceipt"),
                h.calls.take(3).map { it.methodName() },
            )
            assertEquals(1, h.calls.count { it.methodName() == "eth_sendRawTransaction" })
            assertFailsWith<InputException> {
                EvmEncoding.transactionId(
                    raw,
                    quote.copy(evmPlan = quote.evmPlan!!.copy(l1Fee = null)),
                )
            }
            assertFailsWith<InputException> {
                EvmEncoding.transactionId(
                    raw,
                    quote.copy(asset = quote.asset.copy(coin = "ethereum")),
                )
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun baseKnownReceiptAndLookupFailureBothPreventBroadcast() = runTest {
        val (raw, quote) = baseFixture()
        val hash = EvmEncoding.transactionId(raw, quote)
        for (available in listOf(true, false)) {
            val h =
                ProviderHarness(NetworkProvider.BLOCKSTREAM) { request ->
                    when (request.methodName()) {
                        "eth_chainId" -> rpc("0x2105")
                        "eth_getTransactionByHash" ->
                            if (available) rpc(JsonNull) else throw IOException("Unavailable")
                        "eth_getTransactionReceipt" ->
                            rpc(
                                buildJsonObject {
                                    put("transactionHash", hash)
                                    put("status", "0x1")
                                }
                            )
                        else -> error("No oracle or broadcast allowed")
                    }
                }
            try {
                if (available) {
                    assertEquals(hash, h.network.broadcast(raw, quote))
                    assertEquals(TransferStatus.CONFIRMED, h.network.lastSubmissionStatus)
                } else
                    assertEquals(
                        InputError.SUBMISSION_PENDING,
                        assertFailsWith<InputException> { h.network.broadcast(raw, quote) }.reason,
                    )
                assertFalse(h.calls.any { it.methodName() == "eth_sendRawTransaction" })
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun baseFeeIncreaseBlocksSubmissionWithoutChangingTheSignedPayment() = runTest {
        val (raw, quote) = baseFixture()
        val hash = EvmEncoding.transactionId(raw, quote)
        var increased = true
        val h =
            ProviderHarness(NetworkProvider.BLOCKSTREAM) { request ->
                when (request.methodName()) {
                    "eth_chainId" -> rpc("0x2105")
                    "eth_getTransactionByHash",
                    "eth_getTransactionReceipt" -> rpc(JsonNull)
                    "eth_call" -> {
                        val data =
                            request
                                .bodyJson()["params"]!!
                                .jsonArray
                                .first()
                                .jsonObject["data"]!!
                                .jsonPrimitive
                                .content
                        if (increased && data.startsWith(selector("getL1FeeUpperBound(uint256)")))
                            word("2000")
                        else oracleReply(data)
                    }
                    "eth_sendRawTransaction" -> {
                        assertFalse(increased)
                        assertEquals(
                            raw,
                            request.bodyJson()["params"]!!.jsonArray.single().jsonPrimitive.content,
                        )
                        rpc(hash)
                    }
                    else -> error("Unexpected method")
                }
            }
        try {
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> { h.network.broadcast(raw, quote) }.reason,
            )
            assertFalse(h.calls.any { it.methodName() == "eth_sendRawTransaction" })
            assertEquals(2, h.calls.count { it.methodName() == "eth_getTransactionByHash" })
            increased = false
            h.calls.clear()
            assertEquals(hash, h.network.broadcast(raw, quote))
            assertEquals(1, h.calls.count { it.methodName() == "eth_sendRawTransaction" })
        } finally {
            h.close()
        }
    }

    @Test
    fun oldPublicVaultAddsBaseForBothModesWithoutCopyingBalancesOrNeedingHardware() = runTest {
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler))
        val deriver = Bip32PublicKeyDeriver()
        val importer = PublicAccountImporter(deriver)
        val data = WalletMode.entries.map { importer.decode(PublicWalletFixtures.response(it), it) }
        vault.update {
            it.copy(
                accounts = data.flatMap { d -> d.accounts },
                assets = data.flatMap { d -> d.assets },
            )
        }
        vault.registerBaseAccounts()
        for (mode in WalletMode.entries) {
            val eth = vault.publicAccounts(mode).single { it.coin == "ethereum" }
            val base = vault.publicAccounts(mode).single { it.coin == "base" }
            assertEquals(eth.xpub, base.xpub)
            assertFalse(base.enabled)
            vault.enableAccount(mode, "base")
            val original = vault.assets(mode).first { it.coin == "base" }
            assertEquals(deriver.derive(eth, 0).address, original.address)
            assertEquals("0", original.balance)
            val next = vault.addPublicAddress(mode, base.id, deriver)
            assertEquals(deriver.derive(eth, 1).address, next.address)
            assertEquals(
                1L,
                vault.publicAccounts(mode).single { it.coin == "ethereum" }.nextReceiveIndex,
            )
            assertEquals(
                2L,
                vault.publicAccounts(mode).single { it.coin == "base" }.nextReceiveIndex,
            )
            assertEquals(2, vault.assets(mode).count { it.coin == "base" && it.symbol == "USDC" })
        }
        val before = vault.snapshot()
        vault.registerBaseAccounts()
        assertEquals(before, vault.snapshot())
        assertEquals(
            before,
            VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler)).snapshot(),
        )
        val bases = before.accounts.filter { it.coin == "base" }
        assertNotEquals(bases[0].xpub, bases[1].xpub)
    }

    @Test
    fun choosingMoralisKeepsWalletDataAndPendingPaymentsAndClearsItsKeyOnLock() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val vm =
                WalletViewModel(
                    vault,
                    DemoHardwareWallet(),
                    MockWalletNetwork(),
                    DemoPublicKeyDeriver(),
                )
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vault.update {
                it.copy(
                    pendingSubmissions =
                        listOf(
                            PendingSubmission(
                                "eth-pending",
                                EIP155_RAW,
                                publicQuote(),
                                WalletMode.STANDARD,
                            )
                        )
                )
            }
            val original = vault.snapshot()
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.MORALIS, moralisKey = "public_test_key"),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()
            assertEquals(original.accounts, vault.snapshot().accounts)
            assertEquals(original.assets, vault.snapshot().assets)
            assertEquals(
                original.assets.filter { asset ->
                    original.accounts.any {
                        it.id == asset.accountId && it.mode == WalletMode.STANDARD && it.enabled
                    }
                },
                vm.state.value.assets,
            )
            assertEquals(
                original.accounts.filter { it.mode == WalletMode.STANDARD && it.enabled },
                vm.state.value.accounts,
            )
            assertEquals("eth-pending", vm.state.value.pendingTransfers.single().txId)
            vm.showAddAccount()
            advanceUntilIdle()
            assertTrue(vm.state.value.availableCoins.any { it.coin == "base" })
            vm.addAccount(vm.state.value.availableCoins.single { it.coin == "base" })
            advanceUntilIdle()
            assertTrue(vm.state.value.accounts.any { it.coin == "base" })
            assertTrue(vm.state.value.assets.any { it.coin == "base" && it.symbol == "ETH" })
            vm.lock()
            advanceUntilIdle()
            assertTrue(vm.state.value.networkSettings.moralisKey.isEmpty())
            vm.unlock("normal123")
            advanceUntilIdle()
            assertEquals("public_test_key", vm.state.value.networkSettings.moralisKey)
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.BLOCKSTREAM),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()
            assertTrue(vm.state.value.accounts.any { it.coin == "bitcoin" })
            assertTrue(vault.snapshot().accounts.any { it.coin == "base" && it.enabled })
            assertEquals("eth-pending", vm.state.value.pendingTransfers.single().txId)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
