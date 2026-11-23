[Português](README.md) · [English](README.en.md) · **Español** · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/hero-mobile.png">
  <img src="docs/assets/hero.png" alt="BJJ-Wallet — carteras físicas asequibles, código abierto y colaboración" width="100%">
</picture>

# BJJ-Cold-Wallet

<a id="testing"></a>

## 🧪 Cartera en fase de pruebas

**BJJ-Cold-Wallet está en fase de pruebas y desarrollo**. Necesitamos ayuda de la comunidad para validar los flujos de la app y del hardware, detectar fallos y mejorar la experiencia.

**¡Ayúdanos a probarla!** Prueba la configuración, la conexión Bluetooth, la sincronización, la revisión y la firma de transacciones en la cold wallet. Comparte problemas y sugerencias en la [organización BJJ-Wallet en GitHub](https://github.com/bjjwallet), indicando el modelo del móvil, las versiones de la app y del firmware y los pasos para reproducirlos.

Prioriza redes de prueba y carteras creadas para pruebas. Nunca compartas tu seed, claves privadas, PIN o Transaction Phrase en informes, capturas de pantalla o registros.

**Una cold wallet de desarrolladores para desarrolladores. Abierta a todos.**

Cartera física basada en ESP32-S3, con pantalla táctil, aprobación de transferencias en el dispositivo y comunicación **Bluetooth Low Energy** con una aplicación para Android e iOS.

<p align="center">
  <img src="docs/assets/badge-open-source.svg" alt="Código abierto" height="28">
  <img src="docs/assets/badge-affordable.svg" alt="Hardware asequible" height="28">
  <img src="docs/assets/badge-community.svg" alt="Construido en comunidad" height="28">
</p>

## 👋 Quiénes somos

Somos **BJJ-Wallet**, un grupo de desarrolladores que trabaja para democratizar las cold wallets mediante hardware asequible, software de código abierto y conocimiento compartido.

**BJJ-Cold-Wallet** materializa esa propuesta: una cartera funcional que la comunidad pueda estudiar, construir y mejorar. Queremos que cada persona entienda qué se ejecuta en el dispositivo que protege sus monedas.

Nuestros principios son accesibilidad, transparencia, ingeniería de seguridad y colaboración. Desarrolladores, makers, diseñadores y personas interesadas en probar son bienvenidos: lean el código, cuestionen decisiones, informen problemas y ayuden a mejorar el proyecto.

Conoce la [organización BJJ-Wallet en GitHub](https://github.com/bjjwallet).

[💚 Apoya a BJJ-Cold-Wallet](#support-project)

## 📱 Aplicación móvil

La aplicación compañera de **BJJ-Cold-Wallet** funciona en Android e iOS. Se conecta a la cartera por Bluetooth, sincroniza los datos necesarios para seguir las cuentas, consulta saldos, prepara transferencias y transmite a la red las transacciones firmadas por el hardware.

El flujo de transferencia es:

1. El usuario selecciona la red, el activo, el destinatario y el importe en la aplicación.
2. La aplicación solicita la firma a la cold wallet por Bluetooth.
3. La cold wallet muestra los detalles y exige aprobación física.
4. El hardware firma y devuelve el resultado a la aplicación.
5. La aplicación verifica la firma y envía los datos firmados mediante la API, RPC o interfaz correspondiente.

**La seed y las claves privadas de gasto permanecen en la cold wallet. La aplicación no solicita ni importa la seed para operar la cartera.** La aplicación procesa credenciales de acceso; en el modo watch-only de Monero, la clave privada de visualización puede conservarse en la bóveda cifrada. Esta clave permite consultar movimientos y no autoriza gastos.

El catálogo actual contiene **23 redes y 86 activos: 23 monedas nativas y 63 tokens**. Incluye Bitcoin, Ethereum y redes EVM, Solana, Cardano, TRON, XRP Ledger, TON, Sui, Stellar, Litecoin, Dogecoin, Bitcoin Cash, Dash, eCash y Monero. El flujo Monero del firmware actual utiliza **stagenet**. La matriz por red y token está en el README de la aplicación.

La aplicación utiliza **Kotlin Multiplatform y Compose Multiplatform**, con integraciones nativas para Android e iOS.

➡️ **[Conoce la aplicación: arquitectura, lenguajes, bibliotecas, redes y compilación](app/README.es.md)**

## 🔐 Hardware

BJJ-Cold-Wallet utiliza la placa **LCDWIKI ES3C28P con pantalla táctil capacitiva**, basada en **ESP32-S3**, con **16 MB de flash**, **8 MB de PSRAM** y pantalla **IPS de 2,8 pulgadas**. La resolución nativa es 240 × 320; la interfaz actual funciona en horizontal a 320 × 240. Especificaciones: [documentación del fabricante](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display).

El firmware utiliza **C/C++ con ESP-IDF**, interfaz **LVGL** y una biblioteca **Rust** para la firma Monero. La integración criptográfica incluye **TrezorCrypto**, procedente de Trust Wallet Core 4.4.2, con versiones y procedencia registradas en el proyecto.

La cold wallet controla el acceso de los móviles, presenta los datos para su aprobación y firma. En la aplicación distribuida, USB suministra alimentación y Bluetooth comunica con la aplicación. El firmware bloquea Wi-Fi y microSD.

### 🛒 Placa y carcasa

- **[Comprar la placa — anuncio recomendado en AliExpress](https://pt.aliexpress.com/item/1005010470966979.html)**. Comprueba la variante **ES3C28P con pantalla táctil**; ES3N28P no tiene función táctil.
- **[Carcasa para impresión 3D — referencia en Printables](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Comprueba dimensiones, fijación y acceso a conectores antes de imprimir; el proyecto aún no ha validado la compatibilidad mecánica de este modelo.

➡️ **[Conoce el hardware: placa, TrezorCrypto, bibliotecas, firmware e instalación](hardware/README.es.md)**

El proyecto está en desarrollo. Consulta los [resultados y límites de las pruebas de firma](app/docs/all-assets-signing-2026-11-21.md), las [normas de código](docs/CODE_STYLE.md) y las licencias de la [aplicación](app/LICENSE) y del [hardware](hardware/LICENSE).

<a id="support-project"></a>

## 💚 Ayúdanos a mantener viva la idea

Queremos democratizar las cold wallets con hardware asequible y código abierto. Tu contribución ayuda a mantener BJJ-Cold-Wallet en desarrollo y esta idea viva.

**Ahora necesitamos ayuda para pagar las cuotas de inscripción en las tiendas de aplicaciones y publicar las apps de Android e iOS.**

### Elige la red antes de donar

| USDT · Ethereum | USDT0 · Polygon |
|:---:|:---:|
| <img src="docs/donate/usdt-ethereum.png" alt="Código QR de la dirección para USDT · Ethereum" width="220"> | <img src="docs/donate/usdt0-polygon.png" alt="Código QR de la dirección para USDT0 · Polygon" width="220"> |
| Red: **Ethereum** | Red: **Polygon** |

Ambos códigos QR contienen únicamente la misma dirección. Selecciona en tu cartera el token y la red indicados antes de enviar.

**Dirección de donación**

En GitHub, usa el botón de copiar en la esquina del bloque siguiente. La dirección es la misma en ambas redes.

```text
0x142f6f4230934c5555772e4de1E8E39E1105CB2b
```

Gracias por apoyar el desarrollo abierto.
