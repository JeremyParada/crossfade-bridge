/**
 * Turns a crash report from the television into a GitHub issue.
 *
 * This exists so the APK never carries a token. An APK is a zip: a token compiled into
 * one can be read out of it in a minute, and whoever does can spam, edit and close
 * issues as its owner. Here the token is a Cloudflare secret, it can be rotated without
 * publishing a new version of the app, and abuse is capped before it reaches GitHub.
 *
 * What this does NOT do is authenticate the sender. It cannot: every caller is a copy
 * of a public APK, so any shared secret it could present is equally public. The defence
 * is that an abused relay costs its owner a rate limit and a `wrangler deploy`, rather
 * than a compromised account.
 *
 * Deploy:
 *   npm install -g wrangler
 *   wrangler login
 *   wrangler secret put GITHUB_TOKEN     # fine-grained, only Issues:write on the repo
 *   wrangler deploy
 */

const REPO = "JeremyParada/crossfade-bridge";

/** Reports are logs, not novels. Measured in bytes, which is what a body costs. */
const MAX_BODY_BYTES = 64 * 1024;

/** Titles land in a list of issues, where a long one is unreadable. */
const MAX_TITLE_CHARS = 120;

const bytes = (s) => new TextEncoder().encode(s).length;

/**
 * Stops `@name` from notifying a real person.
 *
 * Anyone can post here, so anyone could use this relay to mention whoever they like on
 * GitHub. A zero-width space after the @ leaves the text readable and the mention dead.
 */
const noMentions = (s) => s.replace(/@(?=[A-Za-z0-9_-])/g, "@\u200B");

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("POST a {title, body}", { status: 405 });
    }

    // An unset secret makes the Authorization header read "Bearer undefined", which
    // GitHub answers with a 401 that looks exactly like a rejected report. Say what is
    // actually wrong, and say it where whoever deployed this will see it.
    if (!env.GITHUB_TOKEN) {
      console.log("GITHUB_TOKEN no está puesto: wrangler secret put GITHUB_TOKEN");
      return new Response("relé sin configurar", { status: 503 });
    }

    // Keyed by address: one television that has gone wrong in a loop cannot fill the
    // repository, and everyone else is unaffected. Absent binding means no limiting,
    // which is the case when this is deployed without the wrangler.toml.
    if (env.RATE_LIMITER) {
      const key = request.headers.get("cf-connecting-ip") ?? "unknown";
      const { success } = await env.RATE_LIMITER.limit({ key });
      if (!success) {
        return new Response("demasiados informes, prueba más tarde", { status: 429 });
      }
    }

    // A declared length that is already too large saves reading the body at all. It is
    // only a shortcut: Transfer-Encoding: chunked carries no length, so the real check
    // is on the parsed values below.
    const declared = Number(request.headers.get("content-length") ?? 0);
    if (declared > MAX_BODY_BYTES) {
      return new Response("informe demasiado grande", { status: 413 });
    }

    let report;
    try {
      report = await request.json();
    } catch {
      return new Response("cuerpo ilegible", { status: 400 });
    }

    // `report?.` and not `report.`: a body of literally `null` is valid JSON, and
    // reading a field off it throws where a 400 belongs.
    const title = String(report?.title ?? "").trim().slice(0, MAX_TITLE_CHARS);
    const body = String(report?.body ?? "");
    if (!title || !body) {
      return new Response("faltan title o body", { status: 400 });
    }
    if (bytes(body) > MAX_BODY_BYTES) {
      return new Response("informe demasiado grande", { status: 413 });
    }

    const issue = await fetch(`https://api.github.com/repos/${REPO}/issues`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github+json",
        // GitHub rejects requests without one, and this names the sender honestly.
        "User-Agent": "crossfade-bridge-relay",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        title: noMentions(title),
        // Marked as automatic so a real person's report is never confused with one of
        // these, and stamped because the app cannot be trusted to know the time.
        body: `${noMentions(body)}\n\n---\nEnviado automáticamente por la app el ${new Date().toISOString()}.`,
        labels: ["informe-automatico"],
      }),
    });

    if (!issue.ok) {
      // The detail goes to the worker's log, not to the app: it can carry token or repo
      // information, and the app can do nothing with it anyway.
      console.log("github respondió", issue.status, await issue.text());
      return new Response("no se pudo crear la issue", { status: 502 });
    }

    return new Response("recibido", { status: 202 });
  },
};
