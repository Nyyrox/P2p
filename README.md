# PeerTalk

Serverless peer-to-peer chat, voice and screen sharing for Android. Kotlin + Jetpack Compose + WebRTC.
No signaling server, no accounts, no backend. Two people swap two codes and the phones connect directly.

## Build

1. Open this folder in Android Studio (Ladybug or newer). Let it sync, it creates the Gradle wrapper.
2. Run on two real devices (emulators have no usable mic and make P2P awkward).
3. Min SDK 24, target SDK 35. WebRTC library: `io.getstream:stream-webrtc-android` (bump the version in
   `app/build.gradle.kts` if a newer one is out).

## How to connect

1. **Phone A** taps *Create a connection*, then Copy or Share the **invite code** and sends it to Phone B by any channel.
2. **Phone B** taps *Join with a code*, pastes the invite, taps *Create reply code*, and sends the **reply code** back.
3. **Phone A** pastes the reply and taps *Connect*. Both phones switch to the call screen.

Two codes are unavoidable: WebRTC needs both sides' network details, and with no server there is no
other way to carry them. After connecting nothing else is needed.

## In the call

- Text chat (WebRTC data channel)
- Live voice (mic and speaker, with mute and speaker/earpiece toggle)
- Screen share (either side, or both at once). Starts the system capture prompt, streams at up to 1280px, 15 fps.

## Internet vs same Wi-Fi

- **"Connect over the internet" ON** (default): each phone asks a public STUN server (Google) what its public
  address is. That is the only outside contact, and no media or messages go through it.
- **OFF**: no outside server at all. Works on the same Wi-Fi / hotspot only.
- No TURN relay is included, so two phones behind strict/symmetric NATs (some mobile carriers) may fail to
  connect. Adding a TURN server is the only fix, and that needs a server. Hotspot or same Wi-Fi always works.

## Layout

| File | Role |
|---|---|
| `rtc/PeerEngine.kt` | WebRTC core: peer connection, data channel, audio, screen capture |
| `rtc/CodeCodec.kt` | SDP to gzip + Base64 code and back |
| `CallService.kt` | Foreground service (mic + MediaProjection), required by Android 14+ |
| `ui/PeerTalkApp.kt` | Compose UI: home, host, join, call |

## Notes

- Audio and screen video are encrypted (DTLS-SRTP) by WebRTC. Anyone holding both codes could attempt to
  connect, so only share codes with the person you mean to call.
- System/internal audio is not shared with the screen stream, only the mic.
- Each code is single-use. To reconnect, create a new connection.

## Release APK via GitHub Actions

`.github/workflows/release.yml` builds a release APK on every push to main/master and on every `v*` tag (e.g. `git tag v1.0 && git push --tags`)
or manually from the Actions tab. It uploads the APK as an artifact and attaches it to a GitHub Release (tags only).

The APK is signed with the bundled random keystore (`app/release.jks`, credentials in `keystore.properties`),
so builds install and update over each other with no setup. That key is committed to the repo, so treat it as a
sideloading key only: anyone with repo access can sign as you. For Play Store or a private repo-free key, replace
it, or add repo secrets that override it: `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`.
