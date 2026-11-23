# Fonte da interface

`airlock_chinese_16.c` é um subconjunto de **WenQuanYi Zen Hei**, a fonte
`../../../ui/font/font.ttc` da carteira original. Inclui os caracteres dos oito
idiomas: português, inglês, espanhol, francês, mandarim, japonês, italiano e alemão.
Também fornece os acentos usados como fallback das fontes Montserrat.

O gerador lê todos os catálogos de `ui/locales/` e falha se a fonte de origem não
contiver algum caractere necessário. Os builds usam o arquivo C já gerado.

Licença: **GPL v2 com exceção para incorporação de fontes**, conforme
`../../../ui/font/LICENSE`. O símbolo C histórico foi preservado.

Gerado com `../../tools/generate_ui_font.py`, fonttools e lv_font_conv 1.5.3.
