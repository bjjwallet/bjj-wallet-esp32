package pub.airlock.data.network

import fr.acinq.bitcoin.*
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.domain.*

/**
 * Bounded mainnet P2PKH/P2WPKH inputs; P2PKH/P2SH/witness outputs. No token or inscription
 * handling.
 */
internal object UtxoStrategy {
    fun script(coin: String, address: String): ByteArray =
        try {
            require(coin in UtxoNetworks.supported && address.length in 25..110)
            if (UtxoNetworks.usesForkId(coin)) cashScript(coin, address)
            else if (address.lowercase().startsWith(if (coin == "litecoin") "ltc1" else "bc1")) {
                require(coin in setOf("bitcoin", "litecoin"))
                val (prefix, data, encoding) = Bech32.decode(address)
                require(data.isNotEmpty())
                val version = data[0]
                val program = Bech32.five2eight(data, 1)
                require(
                    encoding ==
                        if (version == 0.toByte()) Bech32.Encoding.Bech32
                        else Bech32.Encoding.Bech32m
                )
                require(prefix == if (coin == "bitcoin") "bc" else "ltc")
                require(
                    (version == 0.toByte() && program.size in setOf(20, 32)) ||
                        (version == 1.toByte() && program.size == 32)
                )
                byteArrayOf(if (version == 0.toByte()) 0 else 0x51, program.size.toByte()) + program
            } else {
                val (version, hash) = Base58Check.decode(address)
                require(hash.size == 20)
                val (pkh, sh) =
                    when (coin) {
                        "bitcoin" -> 0 to 5
                        "litecoin" -> 48 to 50
                        "dogecoin" -> 30 to 22
                        "dash" -> 76 to 16
                        else -> error("Unsupported")
                    }
                when (version.toInt() and 255) {
                    pkh -> Script.write(Script.pay2pkh(hash))
                    sh -> byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
                    else -> error("Wrong network")
                }
            }
        } catch (_: Exception) {
            throw InputException(InputError.INVALID_DESTINATION)
        }

    private fun cashScript(coin: String, address: String): ByteArray {
        require(address == address.lowercase() || address == address.uppercase())
        val value = address.lowercase().let { if (':' in it) it else "$coin:$it" }
        require(value.substringBefore(':') == coin)
        val data =
            value.substringAfter(':').map {
                Bech32.alphabet.indexOf(it).also { v -> require(v >= 0) }
            }
        require(data.size == 42)
        var checksum = 1L
        val generators =
            longArrayOf(0x98f2bc8e61, 0x79b76d99e2, 0xf33e5fb3c4, 0xae2eabe2a8, 0x1e4f43e470)
        for (v in coin.map { it.code and 31 } + 0 + data) {
            val top = checksum ushr 35
            checksum = ((checksum and 0x07ffffffff) shl 5) xor v.toLong()
            for (i in 0..4) if ((top ushr i) and 1L != 0L) checksum = checksum xor generators[i]
        }
        require(checksum == 1L)
        val decoded = Bech32.five2eight(data.dropLast(8).map { it.toByte() }.toTypedArray(), 0)
        require(decoded.size == 21 && decoded[0].toInt() in setOf(0, 8))
        return if (decoded[0] == 0.toByte())
            Script.write(Script.pay2pkh(decoded.drop(1).toByteArray()))
        else byteArrayOf(0xa9.toByte(), 20) + decoded.drop(1) + byteArrayOf(0x87.toByte())
    }

    private fun tokenProtocol(previous: Transaction) =
        previous.txOut.any {
            val script = it.publicKeyScript.toByteArray()
            script.firstOrNull() == 0x6a.toByte() &&
                script.hex().let { hex -> hex.contains("534c5000") || hex.contains("534c5032") }
        }

    fun tokenFunding(input: UtxoInput) = tokenProtocol(Transaction.read(input.previousTransaction))

    fun input(input: UtxoInput, senderScript: ByteArray, coin: String? = null) {
        require(
            input.txId.matches(Regex("[0-9a-f]{64}")) &&
                input.index in 0..0xffffffffL &&
                input.amount > 0
        )
        require(input.previousTransaction.length in 20..200000)
        val previous = Transaction.read(input.previousTransaction)
        require(previous.txid.toString() == input.txId)
        require(coin != "ecash" || !tokenProtocol(previous)) {
            "Native eCash payments cannot consume SLP/ALP funding transactions"
        }
        val output = previous.txOut.getOrNull(input.index.toInt()) ?: error("Missing output")
        require(
            output.amount.toLong() == input.amount &&
                output.publicKeyScript.toByteArray().contentEquals(senderScript)
        )
        require(input.script == senderScript.hex())
    }

    fun plan(
        asset: Asset,
        destination: String,
        amount: String,
        available: List<UtxoInput>,
        rate: Long,
    ): TransferQuote {
        requireInput(
            asset.contract.isEmpty() &&
                asset.coin in UtxoNetworks.supported &&
                asset.decimals == CoinCatalog.get(asset.coin).decimals,
            InputError.NETWORK_UNSUPPORTED,
        )
        val sender = script(asset.coin, asset.address)
        requireInput(
            Script.isPay2pkh(sender) || Script.isPay2wpkh(sender),
            InputError.NETWORK_UNSUPPORTED,
        )
        val recipient = script(asset.coin, destination)
        requireInput(!recipient.contentEquals(sender), InputError.INVALID_DESTINATION)
        val value =
            Amount.atomic(amount, asset.decimals).toLongOrNull()
                ?: throw InputException(InputError.INVALID_AMOUNT)
        requireInput(value >= UtxoNetworks.dust(asset.coin), InputError.INVALID_AMOUNT)
        requireInput(
            rate in 1..10_000_000 && available.size <= 1000,
            InputError.PROVIDER_UNAVAILABLE,
        )
        val selected = mutableListOf<UtxoInput>()
        var total = 0L
        val seen = mutableSetOf<Pair<String, Long>>()
        for (item in
            available.sortedWith(
                compareByDescending<UtxoInput> { it.amount }.thenBy { it.txId }.thenBy { it.index }
            )) {
            require(seen.add(item.txId to item.index))
            input(item, sender, asset.coin)
            selected += item
            total = checkedAdd(total, item.amount)
            // Conservative signed sizes including compact sizes, maximum DER signature and witness
            // rounding.
            val size =
                12L +
                    selected.size * (if (Script.isPay2wpkh(sender)) 69 else 149) +
                    9 +
                    recipient.size +
                    9 +
                    sender.size
            val targetFee = MathSafe.multiply(size, rate)
            if (total >= value && total - value >= targetFee) {
                val remainder = total - value - targetFee
                val change = if (remainder >= UtxoNetworks.dust(asset.coin)) remainder else 0L
                val fee = total - value - change
                val plan =
                    UtxoTransactionPlan(
                        selected.toList(),
                        recipient.hex(),
                        sender.hex(),
                        asset.address,
                        change,
                        fee,
                        rate,
                    )
                return TransferQuote(
                    asset,
                    destination,
                    amount,
                    Amount.display(fee.toString(), asset.decimals),
                    utxoPlan = plan,
                )
            }
            requireInput(selected.size < 16, InputError.INSUFFICIENT_BALANCE)
        }
        throw InputException(InputError.INSUFFICIENT_BALANCE)
    }

    fun validate(quote: TransferQuote): UtxoTransactionPlan {
        if (quote.bitcoinSignatureTest)
            BitcoinSignatureTest.validate(quote, TransferTestConfig.TEST_MODE)
        val plan = quote.utxoPlan ?: throw InputException(InputError.INVALID_QUOTE)
        require(
            quote.evmPlan == null &&
                quote.asset.coin in UtxoNetworks.supported &&
                quote.asset.contract.isEmpty() &&
                quote.asset.decimals == CoinCatalog.get(quote.asset.coin).decimals
        )
        require(quote.feeCurrency == CoinCatalog.get(quote.asset.coin))
        require(
            plan.inputs.size in 1..16 &&
                plan.fee > 0 &&
                plan.change >= 0 &&
                plan.feeRate in 1..10_000_000
        )
        val sender = script(quote.asset.coin, quote.asset.address)
        require(Script.isPay2pkh(sender) || Script.isPay2wpkh(sender))
        require(plan.changeAddress == quote.asset.address && plan.changeScript == sender.hex())
        require(
            plan.recipientScript == script(quote.asset.coin, quote.destination).hex() &&
                plan.recipientScript != plan.changeScript
        )
        require(plan.inputs.map { it.txId to it.index }.distinct().size == plan.inputs.size)
        plan.inputs.forEach { input(it, sender, quote.asset.coin) }
        val total = plan.inputs.fold(0L) { acc, v -> checkedAdd(acc, v.amount) }
        val value = Amount.atomic(quote.amount, quote.asset.decimals).toLong()
        require(value >= UtxoNetworks.dust(quote.asset.coin))
        require(total == checkedAdd(checkedAdd(value, plan.change), plan.fee))
        require(plan.change == 0L || plan.change >= UtxoNetworks.dust(quote.asset.coin))
        require(Amount.atomic(quote.fee, quote.asset.decimals) == plan.fee.toString())
        return plan
    }

    fun transactionId(raw: String, quote: TransferQuote): String =
        try {
            val plan = validate(quote)
            require(raw.length <= 100000 && raw.matches(Regex("(?:[0-9a-fA-F]{2})+")))
            val tx = Transaction.read(raw)
            require(Transaction.write(tx).hex() == raw.lowercase())
            require(tx.version in 1..2 && tx.lockTime == 0L && tx.txIn.size == plan.inputs.size)
            val expected =
                listOf(
                    Amount.atomic(quote.amount, quote.asset.decimals).toLong() to
                        plan.recipientScript
                ) + if (plan.change > 0) listOf(plan.change to plan.changeScript) else emptyList()
            require(
                tx.txOut
                    .map { it.amount.toLong() to it.publicKeyScript.toString() }
                    .sortedBy { it.second } == expected.sortedBy { it.second }
            )
            tx.txIn.forEachIndexed { index, txIn ->
                val input = plan.inputs[index]
                require(
                    txIn.outPoint.txid.toString() == input.txId &&
                        txIn.outPoint.index == input.index &&
                        txIn.sequence == 0xffffffffL
                )
                if (!UtxoNetworks.usesForkId(quote.asset.coin)) {
                    val signature =
                        if (Script.isPay2wpkh(EvmEncoding.bytes("0x" + input.script))) {
                            require(txIn.witness.stack.size == 2 && txIn.signatureScript.isEmpty())
                            txIn.witness.stack[0].toByteArray()
                        } else {
                            val pushes = Script.parse(txIn.signatureScript)
                            require(pushes.size == 2 && pushes.all { it is OP_PUSHDATA })
                            (pushes[0] as OP_PUSHDATA).data.toByteArray()
                        }
                    require(signature.isNotEmpty() && signature.last() == 1.toByte())
                }
                if (UtxoNetworks.usesForkId(quote.asset.coin)) {
                    require(txIn.witness.stack.isEmpty())
                    val pushes = Script.parse(txIn.signatureScript)
                    require(pushes.size == 2 && pushes.all { it is OP_PUSHDATA })
                    val signature = (pushes[0] as OP_PUSHDATA).data.toByteArray()
                    val pub = PublicKey((pushes[1] as OP_PUSHDATA).data)
                    require(Script.write(Script.pay2pkh(pub.hash160())).hex() == input.script)
                    require(
                        signature.last() == 0x41.toByte() &&
                            Crypto.isDERSignature(signature) &&
                            Crypto.isLowDERSignature(signature.dropLast(1).toByteArray())
                    )
                    val hash =
                        tx.hashForSigning(
                            index,
                            EvmEncoding.bytes("0x" + input.script),
                            0x41,
                            Satoshi(input.amount),
                            SigVersion.SIGVERSION_WITNESS_V0,
                        )
                    require(Crypto.verifySignature(hash, Crypto.decodeSignatureLax(signature), pub))
                }
            }
            if (!UtxoNetworks.usesForkId(quote.asset.coin))
                tx.correctlySpends(
                    plan.inputs.associate {
                        OutPoint(TxId(it.txId), it.index) to
                            TxOut(Satoshi(it.amount), ByteVector(it.script))
                    },
                    ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
                )
            tx.txid.toString()
        } catch (_: Exception) {
            throw InputException(InputError.INVALID_QUOTE)
        }

    private fun checkedAdd(a: Long, b: Long): Long {
        require(a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b)
        return a + b
    }
}

private object MathSafe {
    fun multiply(a: Long, b: Long): Long {
        require(a >= 0 && b > 0 && a <= Long.MAX_VALUE / b)
        return a * b
    }
}
