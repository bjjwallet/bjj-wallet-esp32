# Wallet Core 4.4.2 TrezorCrypto source subset

Source files and headers are copied **without modifications** from the official
Wallet Core 4.4.2 source archive. `provenance.json` records the archive URL/SHA256
and individual file hashes. `crypto/LICENSE`, AUTHORS and CONTRIBUTORS are
preserved; most code is MIT, and Ed25519-donna carries its upstream notices.

`../../cmake/trezor_crypto.cmake` is our build adapter. It disables BIP39/BIP32
caches (no retained mnemonic/passphrase/seed cache), leaves precomputed curve
tables in flash and replaces the POSIX `/dev/urandom` adapter with
`core/trezor_random.cpp`. The upstream `rand.c` is not copied or compiled.
Cardano sources must remain enabled because this upstream version references
its curve descriptor unconditionally; the new HD API does not yet expose it.

Each synchronous EC operation binds its RNG through a thread-local scope. The
RNG must remain usable for the operation. Loss of entropy aborts through the
upstream void RNG interface rather than returning a deterministic substitute.
Generate/import and storage code must not treat a failed operation as committed.

The source subset is built for Xtensa by `idf_components/airlock_trezor` and for
host verification by the optional `AIRLOCK_MBEDTLS_DIR` build. Never link this
archive and the official Apple WalletCoreCommon binary into one reference test:
their C symbol names overlap. The differential oracle is a separate executable.

`tools/check_trezor_sources.py` checks each pinned file and equality between the
upstream English wordlist and the original `words.txt` at configure time.
