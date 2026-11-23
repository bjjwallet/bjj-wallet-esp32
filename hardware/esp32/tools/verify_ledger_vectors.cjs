// Independent SDK verification of PUBLIC firmware test fixtures. Never signs or sends.
// NODE_PATH must provide @stellar/stellar-base and tronweb.
const fs = require('fs');
const assert = require('assert/strict');
const stellar = require('@stellar/stellar-base');
const tron = require('tronweb');
const xrp = require('xrpl');
const { bcs } = require('@mysten/sui/bcs');
const { toBase58 } = require('@mysten/bcs');
const { verifyTransactionSignature } = require('@mysten/sui/verify');
const { TransactionDataBuilder } = require('@mysten/sui/transactions');
const ton = require('@ton/core');
const { WalletContractV4 } = require('@ton/ton');
const { signVerify } = require('@ton/crypto');
async function main() {
  const [input, output, catalog] = process.argv.slice(2);
  const tokens = [
    ...fs.readFileSync(catalog, 'utf8').matchAll(/\{"([^"]+)", "([^"]+)", "([^"]+)", (\d+)\}/g),
  ].map(([, network, symbol, contract]) => ({ network, symbol, contract }));
  const rows = JSON.parse(fs.readFileSync(input, 'utf8'));
  for (const row of rows) {
    const r = row.request;
    const token = tokens.find((t) => `${t.network}-mainnet` === r.network && t.symbol === r.asset);
    if (r.network === 'stellar-mainnet') {
      const amount =
        (BigInt(r.amount_atomic) / 10000000n).toString() +
        '.' +
        (BigInt(r.amount_atomic) % 10000000n).toString().padStart(7, '0');
      const tx = new stellar.TransactionBuilder(
        new stellar.Account(r.sender, (BigInt(r.sequence) - 1n).toString()),
        {
          fee: r.fee_atomic,
          networkPassphrase: stellar.Networks.PUBLIC,
          timebounds: { minTime: 0, maxTime: r.expires },
        },
      )
        .addOperation(
          stellar.Operation.payment({
            destination: r.recipient,
            asset: token ? new stellar.Asset(...token.contract.split(':')) : stellar.Asset.native(),
            amount,
          }),
        )
        .build();
      assert(
        stellar.Keypair.fromPublicKey(r.sender).verify(
          tx.hash(),
          Buffer.from(row.signature, 'hex'),
        ),
      );
      row.reference_hash = tx.hash().toString('hex');
      row.reference_body_hex = tx.toEnvelope().v1().tx().toXDR().toString('hex');
    } else if (r.network === 'tron-mainnet') {
      const hex = (s) => tron.TronWeb.address.toHex(s).toLowerCase();
      const value = token
        ? {
            owner_address: hex(r.sender),
            contract_address: hex(token.contract),
            data:
              'a9059cbb' +
              hex(r.recipient).slice(2).padStart(64, '0') +
              BigInt(r.amount_atomic).toString(16).padStart(64, '0'),
          }
        : {
            owner_address: hex(r.sender),
            to_address: hex(r.recipient),
            amount: Number(r.amount_atomic),
          };
      const type = token ? 'TriggerSmartContract' : 'TransferContract';
      const raw_data = {
        ref_block_bytes: r.ref_block_bytes,
        ref_block_hash: r.ref_block_hash,
        expiration: Number(r.expires),
        contract: [
          { parameter: { value, type_url: `type.googleapis.com/protocol.${type}` }, type },
        ],
        timestamp: Number(r.timestamp),
      };
      if (token) raw_data.fee_limit = Number(r.fee_atomic);
      const tx = tron.utils.transaction.txJsonToPb({ raw_data });
      row.reference_hash = tron.utils.transaction.txPbToTxID(tx).replace(/^0x/, '');
      row.reference_body_hex = tron.utils.transaction.txPbToRawDataHex(tx).toLowerCase();
      assert.equal(
        tron.utils.crypto.ecRecover(row.reference_hash, row.signature).toLowerCase(),
        hex(r.sender),
      );
    } else if (r.network === 'ripple-mainnet') {
      const publicKey = row.signature.slice(0, 66);
      const integer = (s) => {
        let b = Buffer.from(s, 'hex');
        while (b.length > 1 && b[0] === 0) b = b.subarray(1);
        if (b[0] & 128) b = Buffer.concat([Buffer.from([0]), b]);
        return Buffer.concat([Buffer.from([2, b.length]), b]);
      };
      const rs = Buffer.concat([
        integer(row.signature.slice(66, 130)),
        integer(row.signature.slice(130, 194)),
      ]);
      const signature = Buffer.concat([Buffer.from([48, rs.length]), rs]).toString('hex');
      const decimal = token
        ? (BigInt(r.amount_atomic) / 1000000000000000n).toString() +
          '.' +
          (BigInt(r.amount_atomic) % 1000000000000000n).toString().padStart(15, '0')
        : r.amount_atomic;
      const tx = {
        TransactionType: 'Payment',
        Account: r.sender,
        Destination: r.recipient,
        Flags: 2147483648,
        Sequence: Number(r.sequence),
        LastLedgerSequence: Number(r.last_ledger),
        Fee: r.fee_atomic,
        Amount: token
          ? {
              currency: token.contract.split('.')[0],
              issuer: token.contract.split('.')[1],
              value: decimal,
            }
          : decimal,
        SigningPubKey: publicKey,
        TxnSignature: signature,
      };
      assert(xrp.verifySignature(tx));
      row.reference_body_hex = xrp.encodeForSigning(tx).slice(8).toLowerCase();
      row.reference_hash = xrp.hashes.hashSignedTx(xrp.encode(tx)).toLowerCase();
    } else if (r.network === 'sui-mainnet') {
      const ref = (o) => ({
        objectId: o.id,
        version: o.version,
        digest: toBase58(Buffer.from(o.digest, 'hex')),
      });
      const input = (i) => ({ Input: i });
      const u64 = Buffer.alloc(8);
      u64.writeBigUInt64LE(BigInt(r.amount_atomic));
      const inputs = r.coins.map((o) => ({ Object: { ImmOrOwnedObject: ref(o) } }));
      inputs.push(
        { Pure: { bytes: u64.toString('base64') } },
        { Pure: { bytes: Buffer.from(r.recipient.slice(2), 'hex').toString('base64') } },
      );
      const commands = [];
      if (r.coins.length > 1)
        commands.push({
          MergeCoins: {
            destination: input(0),
            sources: r.coins.slice(1).map((_, i) => input(i + 1)),
          },
        });
      const splitIndex = commands.length;
      if (token)
        commands.push({
          MoveCall: {
            package: '0x2',
            module: 'coin',
            function: 'split',
            typeArguments: [token.contract],
            arguments: [input(0), input(r.coins.length)],
          },
        });
      else commands.push({ SplitCoins: { coin: { GasCoin: true }, amounts: [input(0)] } });
      commands.push({
        TransferObjects: {
          objects: [token ? { Result: splitIndex } : { NestedResult: [splitIndex, 0] }],
          address: input(r.coins.length + 1),
        },
      });
      const raw = bcs.TransactionData.serialize({
        V1: {
          kind: { ProgrammableTransaction: { inputs, commands } },
          sender: r.sender,
          gasData: {
            payment: r.gas.map(ref),
            owner: r.sender,
            price: r.gas_price,
            budget: r.fee_atomic,
          },
          expiration: { Epoch: Number(r.epoch) },
        },
      }).toBytes();
      const encoded = Buffer.from(row.signature, 'hex');
      const signature = Buffer.concat([
        Buffer.from([0]),
        encoded.subarray(32),
        encoded.subarray(0, 32),
      ]).toString('base64');
      await verifyTransactionSignature(raw, signature, { address: r.sender });
      row.reference_body_hex = Buffer.from(raw).toString('hex');
      row.reference_hash = TransactionDataBuilder.getDigestFromBytes(raw);
    } else if (r.network === 'ton-mainnet') {
      const bytes = Buffer.from(row.signature, 'hex'),
        publicKey = bytes.subarray(0, 32),
        signature = bytes.subarray(32);
      const wallet = WalletContractV4.create({ workchain: 0, walletId: 698983191, publicKey });
      assert(wallet.address.equals(ton.Address.parse(r.sender)));
      let payload;
      if (token) {
        const code = ton.Cell.fromBoc(
          Buffer.from(
            'te6cckEBAQEAIwAIQgKPRS16Tf10BmtoI2UXclntBXNENb52tf1L1divK3w9aCBrv3Y=',
            'base64',
          ),
        )[0];
        assert.equal(
          code.hash().toString('hex'),
          '89468f02c78e570802e39979c8516fc38df07ea76a48357e0536f2ba7b3ee37b',
        );
        const data = ton
          .beginCell()
          .storeUint(0, 4)
          .storeCoins(0)
          .storeAddress(wallet.address)
          .storeAddress(ton.Address.parse(token.contract))
          .endCell();
        assert(ton.contractAddress(0, { code, data }).equals(ton.Address.parse(r.jetton_wallet)));
        payload = ton
          .beginCell()
          .storeUint(0x0f8a7ea5, 32)
          .storeUint(r.sequence, 64)
          .storeCoins(BigInt(r.amount_atomic))
          .storeAddress(ton.Address.parse(r.recipient))
          .storeAddress(wallet.address)
          .storeBit(false)
          .storeCoins(1)
          .storeBit(false)
          .endCell();
      }
      let signing;
      const body = await wallet.createTransfer({
        seqno: Number(r.sequence),
        timeout: Number(r.expires),
        sendMode: 3,
        messages: [
          ton.internal({
            to: ton.Address.parse(token ? r.jetton_wallet : r.recipient),
            value: BigInt(token ? '50000000' : r.amount_atomic),
            bounce: !!token,
            body: payload,
          }),
        ],
        signer: async (cell) => {
          signing = cell;
          assert(signVerify(cell.hash(), signature, publicKey));
          return signature;
        },
      });
      const message = ton
        .beginCell()
        .store(
          ton.storeMessage(
            ton.external({
              to: wallet.address,
              body,
              init: r.sequence === '0' ? wallet.init : undefined,
            }),
            { forceRef: true },
          ),
        )
        .endCell();
      row.reference_body_hex = signing.toBoc({ idx: false, crc32: false }).toString('hex');
      row.reference_hash = message.hash().toString('hex');
    } else throw Error('Unsupported reference protocol');
  }
  fs.writeFileSync(output, JSON.stringify(rows, null, 2) + '\n');
  console.log(`Independent SDK verification: ${rows.length} signatures passed`);
}
main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
