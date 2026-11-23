// SPDX-License-Identifier: GPL-3.0-or-later
// DEVELOPMENT ONLY. The unchanged original Utils.js and Wallet Core WASM 4.4.2
// are a reference oracle; no Node/WASM is used in the new wallet runtime.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const init = require(path.resolve(process.argv[2]));
const root = path.resolve(__dirname, '../..');
const utils = require(path.join(root, 'js/Utils.js'));
const coins = JSON.parse(fs.readFileSync(path.join(root, 'registry.json')));
const sha = (rows) => crypto.createHash('sha256').update(rows.sort().join('')).digest('hex');
(async () => {
  const core = await init();
  const cases = [];
  for (const count of [12, 18, 24])
    for (const withPhrase of [false, true]) {
      const mnemonic = Array(count - 1)
        .fill('abandon')
        .concat({ 12: 'about', 18: 'agent', 24: 'art' }[count])
        .join(' ');
      if (!core.Mnemonic.isValid(mnemonic)) throw new Error('Invalid public fixture');
      const rows = [],
        byCoin = {},
        failures = [];
      for (const main of [true, false]) {
        const wallet = core.HDWallet.createWithMnemonic(
          mnemonic,
          main && withPhrase ? 'Public-Passphrase1!' : '',
        );
        try {
          for (const coin of coins) {
            if (coin.id === 'nimiq' || !['secp256k1', 'ed25519'].includes(coin.curve)) continue;
            try {
              const total = coin.curve === 'secp256k1' ? 1 : main ? 100 : 10;
              for (let i = 0; i < total; ++i) {
                const r =
                  coin.curve === 'secp256k1'
                    ? await utils.getSecp256k1(core, wallet, coin)
                    : await utils.getEd25519(core, wallet, coin, i);
                const row =
                  [
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
                  ].join('\t') + '\n';
                rows.push(row);
                (byCoin[coin.id] ||= []).push(row);
              }
            } catch (e) {
              failures.push({ coin: coin.id, main, error: e.message });
            }
          }
        } finally {
          wallet.delete();
        }
      }
      cases.push({
        id: count + (withPhrase ? '-with' : '-without'),
        rows: rows.length,
        sha256: sha(rows),
        failures,
        coins: Object.fromEntries(
          Object.entries(byCoin).map(([id, values]) => [
            id,
            { rows: values.length, sha256: sha(values) },
          ]),
        ),
      });
    }
  const source_sha256 = Object.fromEntries(
    ['registry.json', 'js/Utils.js', 'js/crypto_worker.js', 'js/SignerManager.js'].map((name) => [
      name,
      crypto
        .createHash('sha256')
        .update(fs.readFileSync(path.join(root, name)))
        .digest('hex'),
    ]),
  );
  console.log(
    JSON.stringify(
      { wallet_core: '4.4.2', source: 'Unmodified original js/Utils.js', source_sha256, cases },
      null,
      2,
    ),
  );
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
