// SPDX-License-Identifier: GPL-3.0-or-later
// DEVELOPMENT ONLY. Runs the unmodified original helpers as a parity oracle.
// No JavaScript/Node process is part of the new C++ wallet runtime.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const init = require(path.resolve(process.argv[2]));
const original = path.resolve(__dirname, '../..');
const utils = require(path.join(original, 'js/Utils.js'));
const coins = JSON.parse(fs.readFileSync(path.join(original, 'registry.json')));
const words =
  'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';
const phrase = 'Public-Passphrase1!';
(async () => {
  const core = await init();
  const rows = [],
    failures = [];
  for (const main of [true, false]) {
    const wallet = core.HDWallet.createWithMnemonic(words, main ? phrase : '');
    for (const coin of coins) {
      if (coin.id === 'nimiq' || !['secp256k1', 'ed25519'].includes(coin.curve)) continue;
      try {
        const count = coin.curve === 'secp256k1' ? 1 : main ? 100 : 10;
        for (let i = 0; i < count; ++i) {
          const r =
            coin.curve === 'secp256k1'
              ? await utils.getSecp256k1(core, wallet, coin)
              : await utils.getEd25519(core, wallet, coin, i);
          rows.push([
            r.coin,
            r.symbol,
            r.name,
            r.address,
            r.extendedPublicKey,
            r.path,
            String(r.decimals),
            r.blockchain,
            r.curve,
            main ? '1' : '0',
            r.extendedPublicKey ? '-1' : String(i),
          ]);
        }
      } catch (e) {
        failures.push({ coin: coin.id, main, error: e.message });
      }
    }
    wallet.delete();
  }
  rows.sort((a, b) => (a.join('\t') < b.join('\t') ? -1 : a.join('\t') > b.join('\t') ? 1 : 0));
  const canonical = rows.map((row) => row.join('\t') + '\n').join('');
  const digest = crypto.createHash('sha256').update(canonical).digest('hex');
  console.log(
    JSON.stringify(
      {
        source: 'Unmodified js/Utils.js + official Wallet Core WASM 4.4.2',
        mnemonic: words,
        passphrase: phrase,
        rows: rows.length,
        sha256: digest,
        failures,
        samples: rows.filter(
          (r) => ['bitcoin', 'ethereum', 'solana'].includes(r[0]) && ['-1', '0'].includes(r[10]),
        ),
      },
      null,
      2,
    ),
  );
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
