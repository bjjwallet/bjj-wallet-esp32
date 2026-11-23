#!/bin/zsh
set -eu
cd "${0:A:h}"
for candidate in /private/tmp/airlock-flash-env/bin/python /private/tmp/airlock-idf-tools/python_env/idf5.4_py3.9_env/bin/python /usr/bin/python3; do
    if [[ -x "$candidate" ]] && "$candidate" -c 'import esptool, serial' 2>/dev/null; then
        "$candidate" tools/install_onboarding.py "$@"
        read -r '?Pressione Enter para fechar.'
        exit
    fi
done
print "Faltam esptool/pyserial no Python local. Restaure o ambiente de desenvolvimento antes de gravar."
read -r '?Pressione Enter para fechar.'
