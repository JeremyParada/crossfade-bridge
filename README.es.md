# Crossfade Bridge

[English](README.md) · **Español**

[![Invítame un café](https://img.shields.io/badge/inv%C3%ADtame%20un%20caf%C3%A9-PayPal-35C46B?style=flat-square)](https://paypal.me/jereparada)
[![Licencia: MIT](https://img.shields.io/badge/licencia-MIT-9AA0A6?style=flat-square)](LICENSE)

Crossfade sobre un grupo de altavoces de Google Home, que Spotify Connect desactiva al
castear.

El truco es que el grupo nunca ve una transición de pista: recibe **un solo flujo
continuo** ya fundido. La mezcla la hace librespot, y este proyecto la sirve por HTTP y le
apunta el grupo.

Corre entero dentro de un **Chromecast con Google TV**: no hace falta dejar un PC
encendido.

Probado en un **Chromecast con Google TV (1.ª gen)** contra un **Google Home (1.ª gen)**,
incluido *Dawn FM*, un álbum pensado para sonar como una sola pieza: las pistas seguidas
del disco se mantienen sin hueco, y el fundido solo ocurre donde el álbum termina.

```
Spotify (móvil = mando)  ──Connect──▶  librespot + backend http
                                              │  LPCM/WAV s16le 44100 estéreo, ya fundido
                                              ▼
                                       http://<tv>:8321/  (stream LIVE)
                                              │
                                              ▼
                                       grupo de Google Home
```

---

## Apoyar el proyecto

<h3 align="center">Hecho con cariño por Jeremy ♥</h3>

<p align="center">
Si llegaste hasta aquí es porque te interesa mi trabajo — gracias de verdad por eso.<br>
Esto es trabajo de una persona, hecho a ratos y regalado. Si te ahorra las tardes,<br>
invítame un café: el mío se me acabó como en la tercera amanecida.<br>
Sin ninguna presión, y en la app no cambia nada de un modo u otro.
</p>

<p align="center">
  <a href="https://paypal.me/jereparada"><img alt="Invítame un café" src="https://img.shields.io/badge/Inv%C3%ADtame%20un%20caf%C3%A9-PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white"></a>
  <a href="https://github.com/JeremyParada"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-JeremyParada-181717?style=for-the-badge&logo=github&logoColor=white"></a>
  <a href="https://linkedin.com/in/jeremy-parada"><img alt="LinkedIn" src="https://img.shields.io/badge/LinkedIn-jeremy--parada-0A66C2?style=for-the-badge&logo=linkedin&logoColor=white"></a>
</p>

<p align="center">
Una estrella en el repo también ayuda, y es gratis.
</p>

---

## Qué usa, y a quién hay que dárselo

Nada de esto existiría sin lo siguiente, y conviene decirlo claro:

- **[librespot](https://github.com/librespot-org/librespot)** (MIT) es el 95 % de lo que
  ocurre aquí: la sesión con Spotify, el descifrado, el decodificado y Spotify Connect.
  Este proyecto es un fork suyo con un backend de audio y un caster añadidos, más una app
  de Android que lo embebe. Todo el mérito del trabajo duro es del proyecto librespot y de
  quienes lo mantienen.
- **[PR #1734](https://github.com/librespot-org/librespot/pull/1734)**, el crossfade en el
  reproductor de librespot, escrito por mí y pendiente de revisión upstream. Es la pieza
  que hace la mezcla; lo demás es fontanería alrededor.
- **[rust_cast](https://crates.io/crates/rust_cast)** para hablar el protocolo Cast, y
  **[mdns-sd](https://crates.io/crates/mdns-sd)** para encontrar los altavoces.
- **[Symphonia](https://github.com/pdeljanov/Symphonia)**, vía librespot, para decodificar
  Ogg/Vorbis.
- **Cloudflare Workers** para el relé de informes de fallo.

**Sin relación con Spotify AB.** Ni afiliado, ni autorizado, ni respaldado. Spotify es una
marca registrada de Spotify AB. Como todo lo que se construye sobre librespot, esto
**necesita una cuenta Premium** y su uso está sujeto a los términos de Spotify.

---

## La app de Android TV

### Instalar

No está en ninguna tienda; se instala de lado. Descarga el APK de
[Releases](https://github.com/JeremyParada/crossfade-bridge/releases) y:

```bash
adb connect <ip-de-la-tv>:<puerto>
adb install -r CrossfadeBridge-0.1.2.apk
```

> **¿Vienes de la 0.1.0 o la 0.1.1? Desinstálala antes** (`adb uninstall org.librespot.embed`).
> La 0.1.2 va firmada con una clave nueva y Android se niega a instalarla encima de la
> anterior. Tendrás que volver a iniciar sesión en Spotify, que ahora es escanear un QR.

Para que `adb` funcione, en la tele: **Ajustes → Sistema → Acerca de →** siete pulsaciones
sobre *Compilación* → **Opciones para desarrolladores → Depuración inalámbrica**. El
puerto de emparejamiento y el de conexión son distintos; el que necesita `adb connect` es
el que sale en la pantalla principal de *Depuración inalámbrica*.

### Antes de instalar: el retardo de ~15 segundos

Play, pausa y siguiente tardan **unos 15 segundos** en oírse. Es simétrico, está medido, y
no es un fallo de este puente: librespot reacciona en menos de un segundo y el puente no
retiene nada, pero el Default Media Receiver del Chromecast bufferiza eso de un stream
HTTP en directo, y el emisor no puede configurarlo.

**El crossfade no se ve afectado**: ocurre dentro de librespot, antes del búfer, y llega
ya mezclado. Lo único que se siente lento es el mando. Si eso te va a molestar más de lo
que te va a gustar el fundido entre altavoces, esta no es tu herramienta.

### Usar

1. **Primero, una pregunta opcional:** si la app puede *mostrarse sobre otras apps*. Solo
   hace falta si la propia tele está en el grupo de altavoces (ver [En la pantalla](#en-la-pantalla)).
   **Activar** abre los ajustes de la tele; enciende el interruptor de Crossfade Bridge,
   vuelve con Atrás, y la tarjeta muestra *activado ✓* y sigue sola. **Ahora no** se la
   salta. Se pregunta una vez; luego se cambia desde la fila de ajustes.
2. **Iniciar sesión en Spotify.** La tele muestra un **código QR** y un código corto en
   letra grande. Escanea el QR con el móvil y confirma — o entra en **spotify.com/pair** y
   escribe el código. La tele lo recoge sola a los pocos segundos. No se muestra nada más
   hasta vincular la cuenta, porque sin ella nada más funciona.

   Las credenciales se acuñan **en el aparato** y se quedan ahí.
3. **Elegir dónde suena.** La app busca por mDNS y lista tus altavoces y grupos.
4. **Iniciar.** El botón sigue al puente: **Iniciar** (verde) → **Iniciando…** →
   **Detener** (rojo), con la línea de estado en color. Aparece en Spotify como dispositivo Connect llamado *Crossfade Bridge*, en
   todos tus aparatos, porque librespot se registra en la nube de Spotify igual que los
   clientes oficiales.

El play, la pausa y el siguiente siguen estando en Spotify. La app solo decide el destino
y cómo se funden las pistas.

Si se cae la conexión del puente con Spotify — un corte de red, una sesión que caduca a
los días — lo detecta en segundos y se reconecta solo. Pulsar Iniciar también lo reanima.

### En la pantalla

Cuando empieza una canción, la tele muestra **lo que suena**: la portada, un fondo con
esa misma portada desenfocada, el título, los artistas, el álbum y el progreso. La
pantalla no se apaga mientras suena; en pausa deja entrar el salvapantallas de la tele.

**Si la tele forma parte del grupo de altavoces**, al castear abre el *Default Media
Receiver* de Google a pantalla completa, sin nada. En Android 14 una app no puede volver
a ponerse delante, así que con el permiso opcional de *mostrar sobre otras apps* la
portada se dibuja **encima**. **Atrás** o **Inicio** la cierran; se va sola tras 5
minutos sin música y vuelve cuando empieza la siguiente canción. Los demás botones no
hacen nada, para que una flecha pulsada sin querer no destape el receptor vacío.

Sin el permiso, la portada sigue ahí siempre que la app esté delante, y desde el botón
**Ahora suena**.

### Ajustes

| Ajuste | Qué hace |
|---|---|
| **Reproducir en** | El altavoz o grupo. Obligatorio: el puente no tiene sonido propio, así que sin destino no arranca. |
| **Crossfade** | Segundos de fundido, 0 a 12. Con 0 se desactiva. |
| **Fundir también dentro de un álbum** | Por defecto *No*: las pistas seguidas de un álbum se encadenan sin hueco ni fundido, que es lo que hace Spotify. La masterización del disco ya resuelve ese empalme. Ponlo en *Sí* si prefieres no oír ninguna junta nunca. |
| **Mostrar sobre otras apps** | Si la portada puede dibujarse encima del receptor de Google. Abre los ajustes de la tele, donde se enciende o apaga. |
| **Informes de fallo** | Apagado por defecto. Ver abajo. |
| **Versión** | Consulta los releases de GitHub. Solo avisa: instalar sigue siendo cosa tuya. |

### El volumen lo manda el grupo

Mientras casteas, **el deslizador de Spotify no hace nada**. Es deliberado.

librespot atenúa por software con una curva logarítmica de 60 dB: al 45 % del deslizador,
la amplitud real es del 2,3 % (−33 dB). Medido en el stream, con el deslizador a la mitad,
el pico era de 751 sobre 32767. Si además el grupo aplica su propio volumen, atenúas dos
veces y tiras bits: 33 dB en digital son unos 5 de los 16 que tiene la muestra.

Así que cuando hay un destino de casteo, el puente manda el PCM a escala completa y el
volumen lo pone el grupo, desde Google Home o el mando. Donde están los altavoces.

### Informes de fallo

Apagados por defecto: con el ajuste en *No* no se escribe ni se envía nada.

Encendidos, un fallo escribe un informe en el aparato (modelo, versión de Android, ABI,
traza y las últimas 400 líneas del log) y lo manda a un relé que abre una issue en el
repo. **El ID de cuenta de Spotify se sustituye por `<oculto>` antes de salir**, porque
una issue pública es pública.

Con la app no viaja ninguna credencial: publica contra un relé que guarda el token de
GitHub de su lado, y por eso un APK que cualquiera puede descomprimir no sirve para
escribir en el repositorio.

**Nada de eso lo tienes que montar tú.** La app ya apunta al relé de este proyecto;
activar el ajuste es todo. [relay/README.md](relay/README.md) es para quien haga un fork y
prefiera que sus informes no lleguen a mi repositorio.

---

## Compilar

### La librería nativa

El puente no está en este repositorio: vive en un fork de librespot, porque eso es lo
que es. Clónalo **dentro de la raíz de este repositorio**, como `librespot/` — los
scripts de compilación lo buscan exactamente ahí:

```bash
cd crossfade-bridge
git clone -b http-backend https://github.com/JeremyParada/librespot.git librespot
```

**Lo que necesitas instalado**, y que ningún script instala por ti: Rust con
[`cargo-ndk`](https://crates.io/crates/cargo-ndk) y el NDK de Android, el SDK de Android
con `build-tools` 34.0.0 y `platforms/android-36`, un
[`kotlinc`](https://kotlinlang.org/docs/command-line.html) suelto (no el de Android
Studio), JDK 17 o superior, y Python.

```bash
cd librespot
cargo ndk -t armeabi-v7a -P 26 -o ./embed/jniLibs build --release \
    -p librespot-embed --no-default-features --features rustls-tls-webpki-roots,cast
```

**`armeabi-v7a`, no `arm64-v8a`.** El Chromecast con Google TV es de 32 bits pese a su
hardware ARM64; un APK arm64 falla al instalar con `INSTALL_FAILED_NO_MATCHING_ABIS`.

### El APK, sin Gradle

**`build-apk.sh` corre en Windows, bajo Git Bash.** Invoca `aapt2.exe`, `d8.bat`,
`zipalign.exe`, `apksigner.bat` y `cygpath` directamente, y las rutas al SDK, a `kotlinc`
y al JDK están al principio del fichero — léelas primero y apúntalas a las tuyas. En
Linux o macOS no corre tal cual; las herramientas son las mismas, así que adaptarlo es
sobre todo quitar los sufijos `.exe`/`.bat` y las llamadas a `cygpath`.

```bash
cd android && ./build-apk.sh
```

**Gradle no funciona en la máquina de desarrollo y no es culpa del proyecto:**
`Selector.open()` falla con *Unable to establish loopback connection* en los tres JDK
instalados, y Gradle necesita un `Selector` para hablar con su propio daemon. Descartado
por prueba directa: no es la máquina, ni un proxy, ni un daemon zombi, ni la versión de
Gradle, ni el JDK. El script invoca `aapt2`, `kotlinc`, `d8`, `zipalign` y `apksigner` a
mano, que no necesitan ninguna de esas cosas.

Cosas que costaron y están puestas en el script:

- Hay que **incluir `kotlin-stdlib.jar`** entre las entradas de `d8`. Sin ella el dex no
  lleva `kotlin.jvm.internal.Intrinsics` y la app muere con `NoClassDefFoundError` en el
  primer método que valide un parámetro.
- `kotlinc` es un `.bat`: **rutas absolutas de Windows** en el classpath, o suelta un
  engañoso *unresolved reference 'R'*.
- `aapt2` exige `package` en el manifiesto, que AGP normalmente inyecta desde `namespace`;
  el script parchea una copia y deja el original intacto.

El ABI se cambia por entorno: `ABI=arm64-v8a ./build-apk.sh` para un móvil.

La redacción tiene su propia comprobación, que no necesita ni aparato ni framework:
compila las reglas tal como se publican y las corre contra lo que debe desaparecer y
lo que debe sobrevivir:

```bash
cd android && ./test-redact.sh
```

**Firma.** El script firma con `android/signing/crossfade-bridge.keystore`, que queda
fuera de git, y **se detiene si falta** en vez de fabricar otra. Un APK firmado con otra
clave no puede actualizar uno instalado, así que una clave que cambia en silencio obliga
a todos a desinstalar y volver a iniciar sesión — que es lo que tuvo que hacer la 0.1.2
tras perderse la clave anterior. Un fork necesita su propio keystore ahí
(`keytool -genkeypair`); guárdale copia.

---

## En un PC, sin la app

El mismo puente corre como CLI, que es como se desarrolló:

```bash
cd librespot
cargo build --release --no-default-features --features rustls-tls-webpki-roots,cast,http-backend
./target/release/librespot --name "Crossfade Bridge" --backend http \
    --device 0.0.0.0:8321 --format S16 --crossfade 8 \
    --cast-to "Grupo de la casa" --disable-discovery --system-cache ./cache
```

La primera vez, para autenticarse, añade `--enable-device-auth`.

---

## Limitaciones conocidas

- **Latencia de control: unos 15 segundos, medida** (entre 15 y 17 según la vez).
  Pausar, reanudar o saltar tarda eso en oírse, de forma simétrica. La descomposición no
  deja dudas: librespot reacciona en menos de 1 s y el puente retiene 0,0 s; el resto es
  entero del búfer del Default Media Receiver sobre un stream HTTP live, que no es
  configurable desde el emisor.

  **El crossfade no se ve afectado:** ocurre dentro de librespot, antes del búfer, y llega
  al receptor ya mezclado. Lo único que sufre es el mando.

- **La tele mata su propio receptor Cast.** Con poca memoria, Android liquida
  `com.google.android.apps.mediashell` —el receptor que reproduce el stream— aunque esté
  en primer plano, y la música se corta. No está en nuestra mano evitarlo; el puente
  detecta que nadie tira del stream y vuelve a castear solo.

- **`stream_type = LIVE` y sin `Content-Length`.** El cuerpo lo delimita el cierre de la
  conexión, como Icecast. Con una longitud declarada, el receptor intenta hacer seek sobre
  algo que no la tiene y falla.

- **Mix y DJ de Spotify no son alcanzables.** Mix es metadata de playlist (BPM, tono,
  curvas de EQ) que aplica el cliente oficial; librespot solo recibe los ficheros
  cifrados. DJ es exclusivo de clientes oficiales.

---

## Licencia

MIT. Ver [LICENSE](LICENSE).

No es una elección estética: el lado Rust deriva de librespot, que es MIT, así que tiene
que seguir siéndolo — y el trabajo de crossfade busca volver a upstream, cosa que
cualquier licencia más restrictiva impediría.
