#!/bin/zsh
set -eu
airlock_directory="${0:A:h}"
airlock_simulator="$airlock_directory/build-simulator/airlock_simulator"
if [[ ! -x "$airlock_simulator" ]]; then
    print -u2 "O simulador ainda precisa ser compilado. Consulte: $airlock_directory/README.md"
    exit 1
fi
exec "$airlock_simulator" "$@"
