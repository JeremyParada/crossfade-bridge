# The failure-report relay

This is for whoever runs the project. **If you just installed the app, you can close this
tab.** Reports already have somewhere to go: the app ships pointing at the relay this
project runs, and turning the setting on is all there is to it. Nothing here is something
a user has to set up.

The app can send a failure report, and that report becomes a GitHub issue. This relay is
what stands in between, and it exists for one reason: **the GitHub token is not in the
APK, and will not be.** An APK is a zip. Anyone holding the file can pull an embedded
token out of it in a minute and then write to the repository as its owner. Here the token
is a Cloudflare secret, it can be rotated without publishing a new version of the app,
and abuse is capped before it reaches GitHub.

What it does **not** do is authenticate the sender, and it cannot: every caller is a copy
of a public APK, so any secret it could present would be public too. What that buys is
that abusing it costs its owner a rate limit and a `wrangler deploy`, not an account.

## Deploying your own

```bash
npm install -g wrangler
```

```bash
wrangler login
```

```bash
wrangler secret put GITHUB_TOKEN
```

That last one asks for the value at a prompt — `GITHUB_TOKEN` is the **name** of the
variable, not a placeholder for your token. Paste the token when it asks, with no
trailing newline. A secret name is not secret: it shows up in `wrangler secret list` and
in the Cloudflare dashboard, so a token used as a name has to be treated as leaked.

```bash
wrangler deploy
```

The deploy prints the URL. Put it in `RELAY_URL` in
`android/app/src/main/kotlin/org/librespot/embed/Debug.kt` and rebuild, or reports will
keep going to somebody else's relay.

**The token:** fine-grained, `Issues: Read and write`, on the report repository only, and
nothing else. When it expires the relay answers 502 and reports quietly pile up on
people's televisions, so give it a date you will remember.

## What it enforces

| | |
|---|---|
| Method | POST only; anything else is 405 |
| Missing token | 503 and a line in the log, instead of a 401 from GitHub that reads like a rejected report |
| Rate | 10 reports per minute per address (`wrangler.toml`). Cloudflare's counter is approximate: measured, it tripped around the 21st of 25 rapid requests |
| Size | 64 KiB, measured in bytes on the parsed body, because a chunked request carries no `content-length` |
| Mentions | `@name` is defused, so nobody can use this to notify strangers on GitHub |
| Preview URLs | Off. Each one is another public door to the same secret |

Errors from GitHub go to the worker's log, never to the app: they can carry token or
repository detail, and the app can do nothing with them anyway. To watch them:

```bash
wrangler tail
```
