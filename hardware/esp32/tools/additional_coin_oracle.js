// SPDX-License-Identifier: GPL-3.0-or-later
// DEVELOPMENT ONLY. Independent reference for the five newly enabled entries.
// Args: official Wallet Core WASM 4.4.2 module; Emurgo CSL nodejs 13.2.1 module.
// No dependency is used by the C/C++ runtime or downloaded by the tests.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const init = require(path.resolve(process.argv[2]));
const csl = require(path.resolve(process.argv[3]));
if (
  require(path.resolve(path.dirname(process.argv[2]), '../../package.json')).version !== '4.4.2' ||
  require(path.resolve(path.dirname(process.argv[3]), 'package.json')).version !== '13.2.1'
) {
  throw new Error('Reference versions must be Wallet Core 4.4.2 and Emurgo CSL 13.2.1');
}
const root = path.resolve(__dirname, '../..');
const registry = JSON.parse(fs.readFileSync(path.join(root, 'registry.json')));
const ids = ['nano', 'nimiq', 'neo', 'ontology', 'cardano'];
const sha = (rows) =>
  crypto.createHash('sha256').update(rows.slice().sort().join('')).digest('hex');
const hard = (n) => n + 0x80000000;

function cardanoAddress(entropy, phrase, index, account = 0, role = 0) {
  // Free the WASM objects too: all inputs are public fixtures.
  const allocated = [];
  const hold = (x) => (allocated.push(x), x);
  try {
    const master = hold(
      csl.Bip32PrivateKey.from_bip39_entropy(entropy, Buffer.from(phrase, 'utf8')),
    );
    const acct = hold(
      hold(hold(master.derive(hard(1852))).derive(hard(1815))).derive(hard(account)),
    );
    const payment = hold(hold(hold(acct.derive(role)).derive(index)).to_public());
    const staking = hold(hold(hold(acct.derive(2)).derive(0)).to_public());
    const paymentHash = hold(hold(payment.to_raw_key()).hash());
    const stakingHash = hold(hold(staking.to_raw_key()).hash());
    const base = hold(
      csl.BaseAddress.new(
        1,
        hold(csl.Credential.from_keyhash(paymentHash)),
        hold(csl.Credential.from_keyhash(stakingHash)),
      ),
    );
    return hold(base.to_address()).to_bech32();
  } finally {
    allocated.reverse().forEach((x) => x.free());
  }
}

(async () => {
  const core = await init();
  const cases = [];
  for (const count of [12, 18, 24])
    for (const withPhrase of [false, true]) {
      const mnemonic = Array(count - 1)
        .fill('abandon')
        .concat({ 12: 'about', 18: 'agent', 24: 'art' }[count])
        .join(' ');
      const rows = [],
        byCoin = {},
        samples = {};
      for (const main of [true, false]) {
        const phrase = main && withPhrase ? 'Public-Passphrase1!' : '';
        const wallet = core.HDWallet.createWithMnemonic(mnemonic, phrase);
        try {
          for (const coin of registry.filter((c) => ids.includes(c.id))) {
            const type = Object.values(core.CoinType).find((t) => t.value === coin.coinId);
            for (let i = 0; i < (main ? 100 : 10); ++i) {
              const hardened = ['nano', 'nimiq'].includes(coin.id);
              const p = coin.derivation[0].path.replace(
                /[^/]+$/,
                String(i) + (hardened ? "'" : ''),
              );
              let address;
              if (coin.id === 'cardano') {
                address = cardanoAddress(wallet.entropy(), phrase, i);
                // With an empty passphrase, our Icarus choice also matches Wallet
                // Core's default Cardano behavior. With a phrase, CSL is the oracle.
                if (!phrase) {
                  const key = wallet.getKey(type, p),
                    pub = key.getPublicKey(type);
                  const a = core.AnyAddress.createWithPublicKey(pub, type);
                  if (a.description() !== address) throw new Error('Cardano default mismatch');
                  a.delete();
                  pub.delete();
                  key.delete();
                }
              } else {
                const key = wallet.getKey(type, p),
                  pub = key.getPublicKey(type);
                const a = core.AnyAddress.createWithPublicKey(pub, type);
                address = a.description();
                a.delete();
                pub.delete();
                key.delete();
              }
              if (!core.AnyAddress.isValid(address, type))
                throw new Error(coin.id + ': invalid address');
              const row =
                [
                  coin.id,
                  coin.symbol,
                  coin.name,
                  address,
                  '',
                  p,
                  String(coin.decimals),
                  coin.blockchain,
                  coin.curve,
                  main ? '1' : '0',
                  String(i),
                ].join('\t') + '\n';
              rows.push(row);
              (byCoin[coin.id] ||= []).push(row);
              if (!i) (samples[coin.id] ||= {})[main ? 'main' : 'standard'] = address;
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
        samples,
        coins: Object.fromEntries(
          Object.entries(byCoin).map(([id, r]) => [id, { rows: r.length, sha256: sha(r) }]),
        ),
      });
    }
  // Extra cases exercise nonzero entropy, UTF-8, nonzero account/change/index.
  const cardanoChecks = [];
  const mnemonic =
    'eight country switch draw meat scout mystery blade tip drift useless good keep usage title';
  for (const phrase of ['', 'foo', 'Pássphrase-漢字1!']) {
    const wallet = core.HDWallet.createWithMnemonic(mnemonic, phrase);
    try {
      for (const [account, role, index] of [
        [0, 0, 0],
        [1, 1, 7],
      ]) {
        cardanoChecks.push({
          mnemonic,
          passphrase: phrase,
          path: `m/1852'/1815'/${account}'/${role}/${index}`,
          address: cardanoAddress(wallet.entropy(), phrase, index, account, role),
        });
      }
    } finally {
      wallet.delete();
    }
  }
  console.log(
    JSON.stringify(
      {
        wallet_core: '4.4.2',
        cardano_reference: '@emurgo/cardano-serialization-lib-nodejs@13.2.1',
        cardano_scheme:
          'CIP-3 Icarus with UTF-8 passphrase; CIP-1852 Shelley mainnet base address; staking role 2/index 0',
        source_urls: [
          'https://github.com/trustwallet/wallet-core/tree/4.4.2',
          'https://github.com/Emurgo/cardano-serialization-lib/tree/846d0f274a436403659fa7b197a09c029d7f2126',
          'https://cips.cardano.org/cip/CIP-0003',
        ],
        registry_sha256: crypto
          .createHash('sha256')
          .update(fs.readFileSync(path.join(root, 'registry.json')))
          .digest('hex'),
        cases,
        cardano_checks: cardanoChecks,
      },
      null,
      2,
    ),
  );
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
