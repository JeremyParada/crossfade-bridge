# Crossfade Bridge

**English** · [Español](README.es.md)

[![Buy me a coffee](https://img.shields.io/badge/buy%20me%20a%20coffee-PayPal-35C46B?style=flat-square)](https://paypal.me/jereparada)
[![Licence: MIT](https://img.shields.io/badge/licence-MIT-9AA0A6?style=flat-square)](LICENSE)

Crossfade across a Google Home speaker group, which Spotify Connect switches off as soon
as you cast.

The trick is that the group never sees a track change: it receives **one continuous
stream** that has already been blended. librespot does the mixing; this project serves it
over HTTP and points the group at it.

It runs entirely inside a **Chromecast with Google TV** — no PC left switched on.

Tested on a **Chromecast with Google TV (1st gen)** driving a **Google Home (1st gen)**,
including *Dawn FM*, an album meant to play as one continuous piece: consecutive album
tracks stay gapless, and the crossfade only happens where the record ends.

```
Spotify (phone = remote)  ──Connect──▶  librespot + http backend
                                              │  LPCM/WAV s16le 44100 stereo, already blended
                                              ▼
                                        http://<tv>:8321/  (LIVE stream)
                                              │
                                              ▼
                                        Google Home group
```

---

## Supporting the project

<h3 align="center">Made with love by Jeremy ♥</h3>

<p align="center">
If you got this far, my work interests you — thank you for that, genuinely.<br>
This is one person's work, made in spare hours and given away. If it saves your evenings,<br>
consider buying me a coffee: I finished mine somewhere around the third late night.<br>
No pressure at all, and nothing in the app is locked either way.
</p>

<p align="center">
  <a href="https://paypal.me/jereparada"><img alt="Buy me a coffee" src="https://img.shields.io/badge/Buy%20me%20a%20coffee-PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white"></a>
  <a href="https://github.com/JeremyParada"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-JeremyParada-181717?style=for-the-badge&logo=github&logoColor=white"></a>
  <a href="https://linkedin.com/in/jeremy-parada"><img alt="LinkedIn" src="https://img.shields.io/badge/LinkedIn-jeremy--parada-0A66C2?style=for-the-badge&logo=linkedin&logoColor=white"></a>
</p>

<p align="center">
A star on the repository helps too, and costs nothing.
</p>

---

## What it uses, and who deserves the credit

None of this would exist without the following, and it is worth saying plainly:

- **[librespot](https://github.com/librespot-org/librespot)** (MIT) is 95 % of what
  happens here: the Spotify session, the decryption, the decoding and Spotify Connect.
  This project is a fork of it with an audio backend and a caster added, plus an Android
  app that embeds it. All the credit for the hard part belongs to the librespot project
  and the people who maintain it.
- **[PR #1734](https://github.com/librespot-org/librespot/pull/1734)**, crossfade in
  librespot's player, written by me and awaiting review upstream. It is the piece that
  does the blending; everything else is plumbing around it.
- **[rust_cast](https://crates.io/crates/rust_cast)** to speak the Cast protocol, and
  **[mdns-sd](https://crates.io/crates/mdns-sd)** to find the speakers.
- **[Symphonia](https://github.com/pdeljanov/Symphonia)**, through librespot, to decode
  Ogg/Vorbis.
- **Cloudflare Workers** for the failure-report relay.

**Not connected to Spotify AB.** Not affiliated, not authorised, not endorsed. Spotify is
a trademark of Spotify AB. Like everything built on librespot, this **requires a Premium
account** and its use is subject to Spotify's terms.

---

## The Android TV app

### Installing

It is in no store; you sideload it. Download the APK from
[Releases](https://github.com/JeremyParada/crossfade-bridge/releases) and:

```bash
adb connect <tv-ip>:<port>
adb install -r CrossfadeBridge-0.1.0.apk
```

For `adb` to work, on the television: **Settings → System → About →** press *Build* seven
times → **Developer options → Wireless debugging**. The pairing port and the connection
port are different; the one `adb connect` needs is the one shown on the main *Wireless
debugging* screen.

### Before you install: the ~15 second delay

Play, pause and skip take **about 15 seconds** to be heard. It is symmetric, it is
measured, and it is not a bug in this bridge: librespot reacts in under a second and the
bridge holds nothing, but the Chromecast's Default Media Receiver buffers that much of a
live HTTP stream, and a sender cannot configure it.

**Crossfade itself is unaffected** — it happens inside librespot, before the buffer, and
arrives already mixed. Only the remote feels slow. If that would annoy you more than
gapless speakers would please you, this is the wrong tool.

### Using it

1. **Sign in to Spotify.** On the television, press the **"Sign in to Spotify"**
   button — it sits next to Start, and only appears while nobody is signed in. A code
   shows up on screen. Open **spotify.com/pair** on your phone or computer, log in there
   if it asks, and type that code. The television picks it up on its own after a few
   seconds; the button then disappears, which is how you know it worked.

   Credentials are minted **on the device** and stay there.
2. **Choose where it plays.** The app browses mDNS and lists your speakers and groups.
3. **Start.** It shows up in Spotify as a Connect device called *Crossfade Bridge*, on all
   your devices, because librespot registers with Spotify's cloud the same way official
   clients do.

Play, pause and skip stay in Spotify. The app only decides the destination and how tracks
blend into each other.

### Settings

| Setting | What it does |
|---|---|
| **Play on** | The speaker or group. Required: the bridge has no sound of its own, so it will not start without a destination. |
| **Crossfade** | Seconds of blend, 0 to 12. Zero switches it off. |
| **Crossfade within an album too** | *No* by default: consecutive album tracks run on with no gap and no blend, which is what Spotify does. The record's own mastering already handles that join. Set it to *Yes* if you would rather never hear a seam. |
| **Failure reports** | Off by default. See below. |
| **Version** | Checks GitHub releases. It only tells you: installing is still your job. |

### The group owns the volume

While casting, **Spotify's slider does nothing**. That is deliberate.

librespot attenuates in software on a 60 dB logarithmic curve: at 45 % of the slider, the
real amplitude is 2.3 % (−33 dB). Measured on the stream with the slider at half, the peak
was 751 out of 32767. If the group then applies its own volume on top, you attenuate twice
and throw away bits: 33 dB in the digital domain is about 5 of the sample's 16.

So when a cast target is set, the bridge sends full-scale PCM and the group sets the
volume, from Google Home or the remote. Where the speakers are.

### Failure reports

Off by default: with the setting on *No*, nothing is written and nothing is sent.

Switched on, a failure writes a report on the device (model, Android version, ABI, stack
trace and the last 400 log lines) and posts it to a relay that opens an issue on the
repository. **The Spotify account id is replaced with `<oculto>` before it leaves**,
because a public issue is public.

No credential travels with the app: it posts to a relay that holds the GitHub token on
its own side, which is why an APK anyone can unzip cannot be used to write to the
repository.

**You do not have to set any of that up.** The app already points at the relay this
project runs; switching the setting on is the whole of it. [relay/README.md](relay/README.md)
is for someone who forks this and would rather their reports did not land in my
repository.

---

## Building

### The native library

The bridge itself is not in this repository: it lives in a fork of librespot, because
that is what it is. Clone it **into the root of this repository**, as `librespot/` —
the build scripts look for it exactly there:

```bash
cd crossfade-bridge
git clone -b http-backend https://github.com/JeremyParada/librespot.git librespot
```

**What you need installed**, none of which the scripts install for you: Rust with
[`cargo-ndk`](https://crates.io/crates/cargo-ndk) and the Android NDK, the Android SDK
with `build-tools` 34.0.0 and `platforms/android-36`, a standalone
[`kotlinc`](https://kotlinlang.org/docs/command-line.html) (not the one inside Android
Studio), JDK 17 or later, and Python.

```bash
cd librespot
cargo ndk -t armeabi-v7a -P 26 -o ./embed/jniLibs build --release \
    -p librespot-embed --no-default-features --features rustls-tls-webpki-roots,cast
```

**`armeabi-v7a`, not `arm64-v8a`.** The Chromecast with Google TV is 32-bit despite its
ARM64 hardware; an arm64 APK fails to install with `INSTALL_FAILED_NO_MATCHING_ABIS`.

### The APK, without Gradle

**`build-apk.sh` runs on Windows, under Git Bash.** It drives `aapt2.exe`, `d8.bat`,
`zipalign.exe`, `apksigner.bat` and `cygpath` directly, and the paths to the SDK, to
`kotlinc` and to the JDK are set at the top of the file — read those first and point
them at your own. On Linux or macOS it will not run as written; the same tools exist
there, so adapting it is mostly dropping the `.exe`/`.bat` suffixes and the `cygpath`
calls.

```bash
cd android && ./build-apk.sh
```

**Gradle does not run on the development machine, and that is not the project's fault:**
`Selector.open()` fails with *Unable to establish loopback connection* on all three
installed JDKs, and Gradle needs a `Selector` to talk to its own daemon. Ruled out by
direct test: not the machine, not a proxy, not a zombie daemon, not the Gradle version,
not the JDK. The script drives `aapt2`, `kotlinc`, `d8`, `zipalign` and `apksigner` by
hand, none of which need any of that.

Things that cost time and are baked into the script:

- `kotlin-stdlib.jar` **must** be among `d8`'s inputs. Without it the dex has no
  `kotlin.jvm.internal.Intrinsics` and the app dies with `NoClassDefFoundError` at the
  first method that validates a parameter.
- `kotlinc` is a `.bat` file: **absolute Windows paths** on the classpath, or it emits a
  misleading *unresolved reference 'R'*.
- `aapt2` requires `package` in the manifest, which AGP normally injects from `namespace`;
  the script patches a copy and leaves the original alone.

The ABI is an environment variable: `ABI=arm64-v8a ./build-apk.sh` for a phone.

Redaction has its own check, which needs neither a device nor a framework — it
compiles the shipped rules and runs them against what must disappear and what must
survive:

```bash
cd android && ./test-redact.sh
```

**The published APK is signed with a debug keystore**, which is what a build without
Gradle produces. Android accepts it for sideloading; it is not a release signature, and
an APK signed with a different key cannot upgrade it in place.

---

## On a PC, without the app

The same bridge runs as a CLI, which is how it was developed:

```bash
cd librespot
cargo build --release --no-default-features --features rustls-tls-webpki-roots,cast,http-backend
./target/release/librespot --name "Crossfade Bridge" --backend http \
    --device 0.0.0.0:8321 --format S16 --crossfade 8 \
    --cast-to "Grupo de la casa" --disable-discovery --system-cache ./cache
```

Add `--enable-device-auth` the first time, to sign in.

---

## Known limitations

- **Control latency: about 15 seconds, measured** (15 to 17 depending on the run).
  Pause, resume and skip take that long to be heard, symmetrically. The breakdown leaves
  no doubt: librespot reacts in under a second and the bridge holds 0.0 s; the rest
  belongs entirely to the Default Media Receiver's buffer over a live HTTP stream, which
  the sender cannot configure.

  **Crossfade is unaffected:** it happens inside librespot, before the buffer, and reaches
  the receiver already mixed. Only the remote suffers.

- **The television kills its own Cast receiver.** Under memory pressure Android kills
  `com.google.android.apps.mediashell` — the receiver playing the stream — even in the
  foreground, and the music stops. That is not ours to prevent; the bridge notices that
  nobody is pulling the stream any more and casts again by itself.

- **`stream_type = LIVE` and no `Content-Length`.** The body is delimited by the
  connection closing, like Icecast. With a declared length the receiver tries to seek
  something that has none, and fails.

- **Spotify's Mix and DJ are out of reach.** Mix is playlist-level metadata (BPM, key, EQ
  curves) applied by the official client; librespot only receives the encrypted files. DJ
  is exclusive to official clients.

---

## Licence

MIT. See [LICENSE](LICENSE).

Not an aesthetic choice: the Rust side derives from librespot, which is MIT, so it has to
stay MIT — and the crossfade work is meant to go back upstream, which anything more
restrictive would prevent.
