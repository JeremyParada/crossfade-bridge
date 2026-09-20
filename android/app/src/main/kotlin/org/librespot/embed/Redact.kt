package org.librespot.embed

/**
 * Takes out of a log anything that identifies a person, or would let someone act as them.
 *
 * A failure report can end up in a public issue, and reporting gets switched on precisely
 * when somebody is about to raise the log level to chase a bug -- which is when librespot
 * starts printing access and client tokens at trace level.
 *
 * Pure on purpose: no Android in here, so `android/test-redact.sh` can compile it and run
 * its cases against the real code rather than against a copy of the rules.
 *
 * The balance it tries to strike: a report missing an id is still useful, a report that
 * leaked one cannot be fixed afterwards -- but a report where every long word has been
 * blanked is not a report. So the blunt catch-all at the end spares the two shapes that
 * are long by construction and never secret: SCREAMING_CASE error constants and JNI
 * symbols.
 */
object Redact {

    const val HIDDEN = "<oculto>"

    /** librespot says this on every sign-in. */
    private val AUTHENTICATED = Regex("""(Authenticat\w+ as )'?([^'\s]+)'?""")

    /**
     * `token: xyz`, `password=xyz`, `Bearer xyz`.
     *
     * The value runs to the next space, quote, comma or closing bracket rather than
     * being a list of allowed characters: a password with a `&` in it used to end the
     * match early and publish the rest of itself.
     *
     * `username` is here; bare `user` is not, because "User agent: Spotify/..." would
     * then lose the only line that says which client this was.
     */
    private val NAMED = Regex(
        """(?i)\b(access[_-]?token|client[_-]?token|auth[_-]?token|refresh[_-]?token""" +
            """|id[_-]?token|api[_-]?key|token|password|passwd|secret|credentials?""" +
            """|authorization|bearer|username)\b(\s*[:=]\s*|\s+)"?([^\s,;"')\]}]{6,})"?"""
    )

    /** `https://user:password@host`, which no amount of key matching would catch. */
    private val URL_CREDENTIALS = Regex("""://[^\s/:@]+:[^\s/@]+@""")

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    /** Three dot-separated base64url runs, starting the way every JWT header does. */
    private val JWT = Regex("""eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]+""")

    /**
     * Standard base64, which the catch-all below cannot see: its `+`, `/` and `=` split
     * a secret into pieces short enough to pass through in the clear. Measured on a
     * leaked-looking blob, about 44 % of it survived.
     */
    private val BASE64 = Regex("""[A-Za-z0-9+/]{32,}={0,2}""")

    /** Anything else long enough to be a token rather than a word. */
    private val LONG_RUN = Regex("""[A-Za-z0-9_\-]{40,}""")

    fun scrub(text: String): String {
        var out = AUTHENTICATED.replace(text) { "${it.groupValues[1]}'$HIDDEN'" }
        out = URL_CREDENTIALS.replace(out, "://$HIDDEN@")
        out = EMAIL.replace(out, HIDDEN)
        out = JWT.replace(out, HIDDEN)
        out = NAMED.replace(out) { it.groupValues[1] + it.groupValues[2] + HIDDEN }
        out = BASE64.replace(out) { if (keep(it.value)) it.value else HIDDEN }
        return LONG_RUN.replace(out) { if (keep(it.value)) it.value else HIDDEN }
    }

    /**
     * Whether a long run is something a reader needs rather than something to hide.
     *
     * Both shapes here are long because of how they are written, not because they are
     * random: `INSTALL_FAILED_NO_MATCHING_ABIS` and
     * `Java_org_librespot_embed_Librespot_nativeStart` are the two things a report about
     * this app most often turns on, and blanking them leaves a report that says a
     * failure happened and nothing else.
     */
    private fun keep(run: String) =
        run.none { it.isLowerCase() } || run.startsWith("Java_")
}
