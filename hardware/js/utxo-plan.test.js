'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { initWasm, TW } = require('@trustwallet/wallet-core');
const { validatePlan, validateSigned } = require('./UtxoPlan');
const BitcoinSigner = require('./signer/BitcoinSigner');
const BitcoinCashSigner = require('./signer/BitcoinCashSigner');
(async () => {
  const core = await initWasm();
  let count = 0;
  const vectors = JSON.parse(
    fs.readFileSync(
      process.argv[2] ||
        require('node:path').join(__dirname, '../esp32/tests/utxo_signing_vectors.json'),
      'utf8',
    ),
  );
  for (const vector of vectors) {
    const request = vector.request;
    const tx = request.txData;
    tx.useMax = false;
    const type = {
      bitcoin: core.CoinType.bitcoin,
      litecoin: core.CoinType.litecoin,
      doge: core.CoinType.dogecoin,
      dash: core.CoinType.dash,
      bitcoincash: core.CoinType.bitcoinCash,
    }[request.asset.coin];
    validatePlan(core, type, tx);
    validateSigned(tx, vector.signed);
    const seed = Uint8Array.from({ length: 64 }, (_, i) => i);
    const wallet = core.HDWallet.createWithMnemonic(
      'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about',
      '',
    );
    const key = wallet.getKey(type, request.asset.derivation_path);
    try {
      tx.privateKeys = [core.HexCoding.encode(key.data())];
      const signer =
        request.asset.coin === 'bitcoincash' ? new BitcoinCashSigner() : new BitcoinSigner();
      signer.core = core;
      const result = signer.signTransfer(tx, type);
      result.encoded = result.encoded.replace(/^0x/, '');
      validateSigned(tx, result.encoded);
      vector.walletCoreSigned = result.encoded;
      const changed = structuredClone(tx);
      changed.fee = '1';
      assert.throws(() => validatePlan(core, type, changed));
      const duplicate = structuredClone(tx);
      duplicate.utxos.push(duplicate.utxos[0]);
      assert.throws(() => validatePlan(core, type, duplicate));
      count++;
    } finally {
      key.delete();
      wallet.delete();
      delete tx.privateKeys;
    }
  }
  if (process.argv[3]) fs.writeFileSync(process.argv[3], JSON.stringify(vectors));
  process.stderr.write(`${count} Wallet Core fixed-plan signatures verified\n`);
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
