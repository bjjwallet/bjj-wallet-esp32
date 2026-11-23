package pub.airlock.domain

/** Separate token amounts from native network fees, including their different precisions. */
object TransferAccounting {
    fun feeAsset(asset: Asset, assets: List<Asset>): Asset? =
        assets.firstOrNull {
            it.accountId == asset.accountId &&
                it.coin == asset.coin &&
                it.address == asset.address &&
                it.contract.isEmpty()
        }

    fun remaining(quote: TransferQuote, assets: List<Asset>): Map<String, String> {
        val asset = assets.firstOrNull { it.id == quote.asset.id }
        requireInput(asset == quote.asset, InputError.INVALID_QUOTE)
        asset!!
        val currency = CoinCatalog.get(asset.coin)
        requireInput(quote.feeCurrency == currency, InputError.INVALID_QUOTE)
        requireInput(
            asset.contract.isEmpty() || Stablecoins.definition(asset) != null,
            InputError.INTEGRATION_UNAVAILABLE,
        )
        val amount = Amount.atomic(quote.amount, asset.decimals)
        requireInput(amount != "0", InputError.AMOUNT_POSITIVE)
        val fee =
            Amount.add(
                Amount.atomic(quote.fee, currency.decimals),
                if (asset.coin == "cardano" && asset.contract.isNotEmpty())
                    (quote.cardanoPlan ?: throw InputException(InputError.INVALID_QUOTE))
                        .recipientLovelace
                        .toString()
                else "0",
            )
        val native = feeAsset(asset, assets)
        requireInput(native != null, InputError.INSUFFICIENT_FEE_BALANCE)
        native!!
        requireInput(
            native.decimals == currency.decimals && native.symbol == currency.symbol,
            InputError.INVALID_QUOTE,
        )
        if (native.id == asset.id) {
            val remaining =
                Amount.subtract(
                    Amount.atomic(asset.balance, asset.decimals),
                    Amount.add(amount, fee),
                )
            return mapOf(asset.id to Amount.display(remaining, asset.decimals))
        }
        val tokenRemaining = Amount.subtract(Amount.atomic(asset.balance, asset.decimals), amount)
        val nativeBalance = Amount.atomic(native.balance, currency.decimals)
        requireInput(Amount.compare(nativeBalance, fee) >= 0, InputError.INSUFFICIENT_FEE_BALANCE)
        return mapOf(
            asset.id to Amount.display(tokenRemaining, asset.decimals),
            native.id to Amount.display(Amount.subtract(nativeBalance, fee), currency.decimals),
        )
    }
}
