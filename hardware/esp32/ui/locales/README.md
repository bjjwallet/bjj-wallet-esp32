# Catálogos de idioma

Cada arquivo JSON contém as strings de um idioma: `pt_br`, `en`, `es`, `fr`,
`zh` (mandarim simplificado), `ja`, `it` e `de`.

`setup` é a interface atual da ESP32, `original` e `custom` são a interface
legada do simulador. `../translations.json` lista os catálogos; não duplica textos.
`generate_ui_strings.py` gera `setup_strings.hpp` e `original_strings.hpp` e
valida chaves, formatos numéricos e espaços reservados em todos os idiomas.
A interface usa `locale_text(Text, Language)` e nunca traduz dados de carteiras,
nomes escolhidos pelo usuário, palavras BIP39, PINs, passphrases ou endereços.

Depois de modificar caracteres, regenere a fonte com `generate_ui_font.py`.
Os nomes nativos e códigos persistidos dos idiomas estão em `../language.hpp`.
