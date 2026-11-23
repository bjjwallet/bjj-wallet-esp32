package pub.airlock.domain

/** Exact input limit for the selected address and currency, without network-fee estimation. */
object TransferAmountPolicy {
    fun availableBalance(asset: Asset): String =
        if (asset.coin == "monero") asset.unlockedBalance ?: "0" else asset.balance

    fun requireWithinBalance(
        amount: String,
        asset: Asset,
        testMode: Boolean = TransferTestConfig.TEST_MODE,
    ) {
        val atomic = Amount.atomic(DecimalInput.canonical(amount, asset.decimals), asset.decimals)
        requireAtomicWithinBalance(atomic, availableBalance(asset), asset.decimals, testMode)
    }

    fun requireAtomicWithinBalance(
        digits: String,
        balance: String,
        decimals: Int,
        testMode: Boolean = TransferTestConfig.TEST_MODE,
    ) {
        val amount = AtomicAmountInput.edit(digits, decimals)
        requireInput(amount.isNotEmpty(), InputError.INVALID_AMOUNT)
        if (!testMode)
            requireInput(
                Amount.compare(amount, Amount.atomic(balance, decimals)) <= 0,
                InputError.INSUFFICIENT_BALANCE,
            )
    }
}
