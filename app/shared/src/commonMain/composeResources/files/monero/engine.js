// Adapter for the pinned monero-ts wallet2 WebAssembly module.
(() => {
  'use strict';
  let daemon;
  let module;
  let wallet;
  let identity;
  let pending;
  let result;
  let running = false;

  const encode = (bytes) => {
    let text = '';
    for (let i = 0; i < bytes.length; i += 8192) {
      text += String.fromCharCode(...bytes.subarray(i, i + 8192));
    }
    return btoa(text);
  };
  const decode = (text) => Uint8Array.from(atob(text), (char) => char.charCodeAt(0));
  const textDecoder = new TextDecoder();

  // Only the native host can make requests. The page itself has no network access.
  globalThis.LibraryUtils = { isRejectUnauthorized: () => true };
  globalThis.HttpClient = {
    createCancelToken: () => ({ token: undefined, cancel: () => {} }),
    request: ({ uri, method, body }) => {
      const url = new URL(uri);
      if (
        pending ||
        url.origin !== daemon ||
        url.search ||
        url.hash ||
        url.username ||
        url.password
      ) {
        return Promise.reject(new Error('Invalid daemon request'));
      }
      const binary = body instanceof Uint8Array;
      return new Promise((resolve, reject) => {
        const data = binary ? encode(body) : body || '';
        pending = {
          resolve,
          reject,
          binary,
          message: { kind: 'rpc', path: url.pathname, method, binary, data },
        };
      });
    },
  };

  let replyChunks = [];
  globalThis.airlockReplyChunk = (chunk) => {
    replyChunks.push(chunk);
  };
  globalThis.airlockReplyDone = () => {
    const data = replyChunks.join('');
    replyChunks = [];
    globalThis.airlockReply({ status: 200, data });
  };
  globalThis.airlockReply = (reply) => {
    const request = pending;
    pending = undefined;
    if (!request) return;
    if (!reply) {
      request.reject(new Error('Daemon request failed'));
      return;
    }
    const bytes = decode(reply.data);
    request.resolve({
      statusCode: reply.status,
      statusText: '',
      headers: {},
      body: request.binary ? bytes : textDecoder.decode(bytes),
    });
  };

  const call = (method, ...args) => new Promise((resolve) => module[method](...args, resolve));
  const object = (text) => {
    const value = JSON.parse(text);
    if (!value || typeof value !== 'object' || Array.isArray(value))
      throw new Error('Invalid wallet2 result');
    return value;
  };
  const createWatchWallet = async (input, server) => {
    const handle = await call(
      'create_full_wallet',
      JSON.stringify({
        networkType: input.networkType,
        primaryAddress: input.address,
        privateViewKey: input.viewKey,
        privateSpendKey: '',
        restoreHeight: input.restoreHeight,
        language: 'English',
        password: '',
        server,
      }),
      'airlock-daemon',
    );
    if (typeof handle !== 'number' || !module.is_view_only(handle))
      throw new Error('Invalid view-only wallet');
    return handle;
  };
  const open = async (input) => {
    const next = [input.networkType, input.address, input.viewKey, input.restoreHeight].join(':');
    if (wallet !== undefined && identity === next) return;
    if (wallet !== undefined) await call('close', wallet, false, 0);
    wallet = undefined;
    daemon = new URL(input.daemon).origin;
    module ??= await monero_ts({ print: () => {}, printErr: () => {} });
    wallet = await createWatchWallet(input, { uri: daemon });
    identity = next;
  };
  const countArchiveOutputs = async (input, archive) => {
    // The native importer counts the same ordered records the cold receives.
    // Using a separate wallet also handles outputs with the same index in different amount groups.
    const scratch = await createWatchWallet(input);
    try {
      const count = await call('import_outputs', scratch, archive);
      if (!Number.isSafeInteger(count) || count < 0) throw new Error('Invalid output archive');
      return count;
    } finally {
      await call('close', scratch, false, 0);
    }
  };
  const scan = async (input) => {
    await open(input);
    const start = await call('get_height', wallet);
    object(await call('sync', wallet, Math.max(start, input.restoreHeight)));
    const height = await call('get_height', wallet);
    const tip = await call('get_daemon_height', wallet);
    if (
      !Number.isSafeInteger(tip) ||
      !Number.isSafeInteger(height) ||
      height < tip ||
      height < input.restoreHeight
    ) {
      return { kind: 'pending' };
    }

    const hex = await call('export_outputs', wallet, true);
    if (typeof hex !== 'string' || !hex.length || hex.length % 2 || /[^0-9a-f]/.test(hex))
      throw new Error('Invalid output archive');
    const count = await countArchiveOutputs(input, hex);
    const keyImages = input.keyImages;
    if (count && keyImages.length !== count) return { kind: 'outputs', hex, count };
    if (keyImages.length !== count) return { kind: 'pending' };
    if (count) {
      // wallet2 verifies each proof against the output at the corresponding offset and queries its spent status.
      object(await call('import_key_images', wallet, JSON.stringify({ offset: 0, keyImages })));
    }
    // Preserve the full atomic integer without converting through a JS Number.
    const balance = /"balance"\s*:\s*"?([0-9]+)"?/.exec(module.get_balance_wallet(wallet));
    if (!balance) throw new Error('Invalid balance');
    const unlocked = /"unlockedBalance"\s*:\s*"?([0-9]+)"?/.exec(
      module.get_unlocked_balance_wallet(wallet),
    );
    if (!unlocked) throw new Error('Invalid unlocked balance');
    if (input.payment) {
      if (BigInt(input.payment.amount) > BigInt(unlocked[1])) return { kind: 'insufficient' };
      const encoded = await call(
        'create_txs',
        wallet,
        JSON.stringify({
          accountIndex: 0,
          canSplit: false,
          relay: false,
          destinations: [{ address: input.payment.recipient, amount: input.payment.amount }],
        }),
      );
      const prepared = object(encoded);
      if (prepared.txs?.length !== 1) throw new Error('Invalid transaction count');
      const unsigned = prepared.unsignedTxHex;
      if (
        typeof unsigned !== 'string' ||
        !unsigned.length ||
        unsigned.length % 2 ||
        /[^0-9a-f]/.test(unsigned)
      ) {
        throw new Error('Invalid unsigned transfer');
      }
      // Extract the exact atomic fee from wallet2's serialization before JS number conversion.
      const fee = /"fee"\s*:\s*"?([0-9]+)"?/.exec(encoded);
      if (!fee) throw new Error('Invalid transfer fee');
      return { kind: 'prepared', hex: unsigned, feeAtomic: fee[1] };
    }
    return { kind: 'verified', height, atomic: balance[1], unlockedAtomic: unlocked[1] };
  };

  globalThis.airlockStart = (input) => {
    if (running) return false;
    running = true;
    result = undefined;
    scan(input)
      .then((value) => {
        result = value;
      })
      .catch(() => {
        result = { kind: 'failed' };
      })
      .finally(() => {
        running = false;
      });
    return true;
  };
  globalThis.airlockNext = () => {
    if (pending) return JSON.stringify(pending.message);
    if (!result) return '';
    const value = JSON.stringify(result);
    result = undefined;
    return value;
  };
})();
