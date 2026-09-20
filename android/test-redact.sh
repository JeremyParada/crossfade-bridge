#!/usr/bin/env bash
# Runs the redaction rules against the cases that matter, on the JVM.
#
# Redact.kt has no Android in it precisely so this can exist: the rules are checked as
# they actually ship, not as a copy of themselves in another language. Nothing in the
# build calls this; run it by hand after touching Redact.kt.
#
#   ./test-redact.sh
set -euo pipefail

KOTLINC="${KOTLINC:-/c/kotlinc/bin/kotlinc.bat}"
JAVA_HOME="${JAVA_HOME:-C:\Program Files\Java\jdk-21.0.10}"
OUT="build-manual/redact-test"
HERE="$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)"

mkdir -p "$OUT"
cat > "$OUT/cases.kt" <<'KOTLIN'
import org.librespot.embed.Redact

private var failures = 0

/** The secret must be gone, and gone completely. */
private fun hides(label: String, line: String, secret: String) {
    val out = Redact.scrub(line)
    if (out.contains(secret)) {
        println("FALLA  $label")
        println("       esperaba que desapareciera: $secret")
        println("       quedo: $out")
        failures++
    } else {
        println("ok     $label")
    }
}

/** The text must survive: a report of blanks is not a report. */
private fun keeps(label: String, line: String, needed: String) {
    val out = Redact.scrub(line)
    if (!out.contains(needed)) {
        println("FALLA  $label")
        println("       esperaba conservar: $needed")
        println("       quedo: $out")
        failures++
    } else {
        println("ok     $label")
    }
}

fun main() {
    // --- lo que tiene que desaparecer ---
    hides("cuenta de Spotify", "session: Authenticated as '12144530487' !", "12144530487")
    hides("otra forma de la frase", "Authenticating as jerparada now", "jerparada")
    hides("token con nombre", "Got auth token: BQAaBcD123-_xyzQQ", "BQAaBcD123-_xyzQQ")
    hides(
        "contrasena con simbolo, que antes se publicaba a medias",
        "password=Tr0ub4dor&3xxxxxxxxxxx next",
        "3xxxxxxxxxxx",
    )
    hides(
        "base64 estandar, que antes se escapaba en trozos",
        "blob=TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24rLy9hYmM=",
        "TWFuIGlzIGRpc3Rpbmd1aXNoZWQ",
    )
    hides(
        "credenciales dentro de una URL",
        "GET https://jerparada:hunter2hunter2@api.spotify.com/v1/me",
        "hunter2hunter2",
    )
    hides("correo", "contact jer.parada@example.com for details", "jer.parada@example.com")
    hides(
        "JWT suelto, sin palabra clave delante",
        "header eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r",
        "eyJhbGciOiJIUzI1NiJ9",
    )
    hides(
        "cadena larga sin forma reconocible",
        "connection-id ODkzMWY2MzQtMDg3Mi00NjFiLWIzZTYtYTllMWEwYzRiNGI1K2RlYWxlcg",
        "ODkzMWY2MzQtMDg3Mi00NjFiLWIzZTYtYTllMWEwYzRiNGI1K2RlYWxlcg",
    )

    // --- lo que tiene que sobrevivir ---
    // Con 40+ caracteres a proposito: por debajo de eso el filtro no actua y el caso
    // pasaria sin probar nada. Lo que se comprueba es la forma, no esta constante.
    keeps(
        "constante larga en mayusculas",
        "Failure [INSTALL_FAILED_NO_MATCHING_ABIS_FOR_THIS_DEVICE: extract failed]",
        "INSTALL_FAILED_NO_MATCHING_ABIS_FOR_THIS_DEVICE",
    )
    keeps(
        "simbolo JNI, que es largo por construccion",
        "UnsatisfiedLinkError: Java_org_librespot_embed_Librespot_nativeStart",
        "Java_org_librespot_embed_Librespot_nativeStart",
    )
    keeps(
        "user agent, que dice que cliente era",
        "http_client: User agent: Spotify/124200290 Linux/0 (librespot-6f6ba2c)",
        "Spotify/124200290",
    )
    keeps(
        "URI de pista",
        "Loading <Dawn FM> with URI <spotify:track:0eaVIYo2zeOaGJeqZ5TwYz>",
        "spotify:track:",
    )
    keeps(
        "clase y metodo de una traza",
        "at org.librespot.embed.BridgeService.onStartCommand",
        "BridgeService.onStartCommand",
    )

    println()
    if (failures == 0) {
        println("todo en verde")
    } else {
        println("$failures caso(s) fallando")
        kotlin.system.exitProcess(1)
    }
}
KOTLIN

echo "==> compilando"
"$KOTLINC" -nowarn -jvm-target 17 -include-runtime \
    -d "$HERE/$OUT/redact-test.jar" \
    app/src/main/kotlin/org/librespot/embed/Redact.kt "$OUT/cases.kt" 2>&1 | grep -v "^warning:" || true

echo "==> ejecutando"
"$JAVA_HOME/bin/java" -jar "$OUT/redact-test.jar"
