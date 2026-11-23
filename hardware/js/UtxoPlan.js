// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const sha = (b) => crypto.createHash('sha256').update(b).digest();
const hash = (b) => sha(sha(b));
function bytes(s) {
  assert.equal(typeof s, 'string');
  assert(s.length <= 200000 && /^(?:[0-9a-f]{2})+$/.test(s));
  return Buffer.from(s, 'hex');
}
function quantity(s) {
  assert.equal(typeof s, 'string');
  assert(/^(0|[1-9][0-9]{0,15})$/.test(s));
  const n = BigInt(s);
  assert(n <= 9000000000000000n);
  return n;
}
function parseTransaction(raw) {
  const data = bytes(raw);
  let pos = 0;
  const take = (n) => {
    assert(Number.isSafeInteger(n) && n >= 0 && pos + n <= data.length);
    const b = data.subarray(pos, pos + n);
    pos += n;
    return b;
  };
  const read = (n) => {
    const b = take(n);
    return n === 1
      ? BigInt(b[0])
      : n === 2
        ? BigInt(b.readUInt16LE())
        : n === 4
          ? BigInt(b.readUInt32LE())
          : b.readBigUInt64LE();
  };
  const compact = () => {
    const prefix = read(1);
    if (prefix < 253n) return Number(prefix);
    const n = read(prefix === 253n ? 2 : prefix === 254n ? 4 : 8);
    assert(n <= 100000n && n >= (prefix === 253n ? 253n : prefix === 254n ? 65536n : 4294967296n));
    return Number(n);
  };
  const script = () => take(compact());
  const version = take(4);
  const witness = data[pos] === 0;
  if (witness) {
    assert.equal(read(1), 0n);
    assert.equal(read(1), 1n);
  }
  const inputStart = pos;
  const count = compact();
  assert(count > 0 && count <= 10000);
  const inputs = [];
  for (let i = 0; i < count; i++)
    inputs.push({
      hash: take(32).toString('hex'),
      index: Number(read(4)),
      script: script(),
      sequence: Number(read(4)),
    });
  const inputEnd = pos;
  const outputStart = pos;
  const outputCount = compact();
  assert(outputCount > 0 && outputCount <= 10000);
  const outputs = [];
  for (let i = 0; i < outputCount; i++)
    outputs.push({ amount: read(8), script: script().toString('hex') });
  const outputEnd = pos;
  const witnesses = [];
  if (witness)
    for (let i = 0; i < count; i++) {
      const n = compact();
      assert(n <= 10000);
      const stack = [];
      for (let j = 0; j < n; j++) stack.push(script());
      witnesses.push(stack);
    }
  const locktime = take(4);
  assert.equal(pos, data.length);
  const stripped = Buffer.concat([
    version,
    data.subarray(inputStart, inputEnd),
    data.subarray(outputStart, outputEnd),
    locktime,
  ]);
  return {
    txid: Buffer.from(hash(stripped)).reverse().toString('hex'),
    inputs,
    outputs,
    witnesses,
    version: version.readUInt32LE(),
    locktime: locktime.readUInt32LE(),
  };
}
function validatePlan(core, coinType, tx) {
  assert.equal(tx.utxoVersion, 1);
  assert.equal(tx.useMax, false);
  const amount = quantity(tx.amount),
    fee = quantity(tx.fee),
    change = quantity(tx.changeAmount),
    rate = quantity(tx.byteFee);
  assert(amount > 0n && fee > 0n && rate > 0n && rate <= 10000000n);
  assert.notEqual(tx.toAddress, tx.changeAddress);
  const scriptFor = (a) => {
    const script = core.BitcoinScript.lockScriptForAddress(a, coinType);
    try {
      return core.HexCoding.encode(script.data()).toLowerCase().replace(/^0x/, '');
    } finally {
      script.delete();
    }
  };
  assert.equal(scriptFor(tx.toAddress), tx.recipientScript);
  assert.equal(scriptFor(tx.changeAddress), tx.changeScript);
  assert(Array.isArray(tx.utxos) && tx.utxos.length > 0 && tx.utxos.length <= 16);
  let total = 0n;
  const seen = new Set();
  for (const input of tx.utxos) {
    assert.equal(input.sequence, 4294967295);
    assert(Number.isInteger(input.index) && input.index >= 0 && input.index <= 4294967295);
    assert(/^[0-9a-f]{64}$/.test(input.txHash));
    const key = input.txHash + ':' + input.index;
    assert(!seen.has(key));
    seen.add(key);
    const value = quantity(input.amount);
    assert(value > 0n);
    total += value;
    assert(total <= 9000000000000000n);
    assert.equal(input.script, tx.changeScript);
    const parent = parseTransaction(input.previousTransaction);
    assert.equal(parent.txid, input.txHash);
    assert(parent.outputs[input.index]);
    assert.equal(parent.outputs[input.index].amount, value);
    assert.equal(parent.outputs[input.index].script, input.script);
  }
  assert.equal(total, amount + fee + change);
  return total.toString();
}
function validateSigned(tx, raw) {
  const result = parseTransaction(raw);
  assert(result.version === 1 || result.version === 2);
  assert.equal(result.locktime, 0);
  assert.equal(result.inputs.length, tx.utxos.length);
  result.inputs.forEach((input, i) => {
    assert.equal(input.hash, Buffer.from(tx.utxos[i].txHash, 'hex').reverse().toString('hex'));
    assert.equal(input.index, tx.utxos[i].index);
    assert.equal(input.sequence, 4294967295);
  });
  const expected = [{ amount: quantity(tx.amount), script: tx.recipientScript }];
  if (quantity(tx.changeAmount) > 0n)
    expected.push({ amount: quantity(tx.changeAmount), script: tx.changeScript });
  const order = (a, b) => a.script.localeCompare(b.script);
  assert.deepEqual(result.outputs.sort(order), expected.sort(order));
}
module.exports = { validatePlan, validateSigned, parseTransaction };
