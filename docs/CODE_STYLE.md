# Formatação do código

O código próprio do app, do firmware e da implementação legada usa os seguintes padrões:

| Linguagem | Formatador | Indentação |
|---|---|---|
| Kotlin e Gradle Kotlin | ktfmt 0.54, `--kotlinlang-style` | 4 espaços |
| C e C++ | clang-format 23.1.5 | 4 espaços |
| Python | Black 26.10.1 | 4 espaços |
| JavaScript | Prettier 3.6.2 | 2 espaços |
| Swift | swift-format do Xcode | 4 espaços |
| Rust | rustfmt, edição 2021 | Padrão Rust |

Os arquivos `.editorconfig`, `.clang-format`, `.prettierrc.json` e `.swift-format`
registram as regras do projeto. O limite de referência é de 100 colunas.

Comentários devem explicar uma decisão, uma restrição de protocolo, uma garantia
de segurança ou um detalhe necessário para manutenção. Preserve licenças e
referências técnicas. Atualize comentários quando a implementação mudar; evite
repetir em texto o que o nome de um método já informa.

O script `tools/format_sources.py` percorre os fontes próprios. Dependências,
catálogos gerados, fontes gráficas e dados binários seguem seus processos de
geração e verificação existentes.

Instale as ferramentas Python com `tools/requirements-format.txt`. Disponibilize
Prettier 3.6.2, rustfmt e as ferramentas do Xcode no ambiente. O JAR do ktfmt pode
ficar fora do repositório; informe seu caminho por `KTFMT_JAR` ou `--ktfmt`.

Na raiz do projeto:

```sh
KTFMT_JAR=/caminho/ktfmt-0.54-jar-with-dependencies.jar python3 tools/format_sources.py
KTFMT_JAR=/caminho/ktfmt-0.54-jar-with-dependencies.jar python3 tools/format_sources.py --check
```

O modo `--check` verifica a formatação sem reescrever os arquivos. Os parâmetros
`--clang-format` e `--prettier` permitem usar instalações locais das ferramentas.

Após mudanças de layout em catálogos ou no código legado, verifique também:

```sh
python3 hardware/esp32/tools/generate_signing_catalog.py --check
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/inventory.py --check
```
