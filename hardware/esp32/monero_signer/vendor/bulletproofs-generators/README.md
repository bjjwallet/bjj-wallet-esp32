# Monero Bulletproofs(+) Generators

Generators used by Monero to instantiate Bulletproofs(+). This is an internal
crate not covered by semver or any guarantees, with no public API.

This library is usable under no-std when the `std` feature (on by default) is
disabled.

### Cargo Features

- `std` (on by default): Enables `std` (and with it, more efficient internal
  implementations).

Airlock vendors this small crate from Monero Oxide revision
`a6b46701fb9b331006d7f2f2278bf51f123b1447`. The firmware needs only two
commitments (recipient and change). The table is capped at two commitments
to reduce the on-device initialization time and stack requirements.
