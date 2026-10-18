# PeerTalk

Serverless peer-to-peer chat, voice and screen sharing for Android. Kotlin + Jetpack Compose + WebRTC.
No signaling server, no accounts, no backend. Two people swap two codes and the phones connect directly.

## Build

1. Open this folder in Android Studio (Ladybug or newer). Let it sync, it creates the Gradle wrapper.
2. Run on two real devices (emulators have no usable mic and make P2P awkward).
3. Min SDK 24, target SDK 35. WebRTC library: `io.getstream:stream-webrtc-android` (bump the version in
   `app/build.gradle.kts` if a newer one is out).

## How to connect

1. **Phone A** taps *Create a connection*, enters a name, and taps *Generate invite code*. Copy or Share the code.
2. **Phone B** taps *Join with a code*, enters a name, pastes the invite, taps *Create reply code*, and sends the reply back.
3. **Phone A** pastes the reply and taps *Connect*. Both phones open the **chat screen**.

Two codes are unavoidable: WebRTC needs both sides' network details, and with no server there is no
other way to carry them. After connecting nothing else is needed.

## In the chat

- Text chat (WebRTC data channel). Each person's name is shown to the other; tap **You: name** to edit yours any time.
- Tap the **phone icon** to ask for a voice call. The other person gets an incoming-call card and can **Accept** or **Decline**.
  - Accept: both phones switch to the call screen.
  - Decline / no answer (45 s) / cancel: you simply stay in the chat. A line in the chat records what happened.
- **The microphone permission is only requested when the call screen opens**, never when creating, joining or chatting.
  If you deny it you can still listen, and the call screen has an *Allow* button (or *Open settings* if blocked).
- Ending a call returns both people to the chat; the session stays open until someone leaves.

## In the call

- Live voice (mic and speaker, with mute and speaker/earpiece toggle) and a call timer
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
| `CallService.kt` | Foreground service. Chat = dataSync only; mic type is added only during a call (Android 14+) |
| `ui/PeerTalkApp.kt` | Root navigation: home, create, join, chat, call, ended |
| `ui/Theme.kt`, `ui/Components.kt` | Material 3 theme (light/dark) and shared composables |
| `ui/SetupScreens.kt` | Home, create (host), join (guest), ended |
| `ui/ChatScreen.kt` | Chat, name editing, call button, ringing banner, incoming-call card |
| `ui/CallScreen.kt` | In-call UI; the only place the mic permission is requested |

## Notes

- Audio and screen video are encrypted (DTLS-SRTP) by WebRTC. Anyone holding both codes could attempt to
  connect, so only share codes with the person you mean to call.
- System/internal audio is not shared with the screen stream, only the mic.
- Each code is single-use. To reconnect, create a new connection.
- While only chatting, the app keeps a low-key foreground notification so Android does not kill the connection in the background.

## Release APK via GitHub Actions

`.github/workflows/release.yml` builds a release APK on every push to main/master and on every `v*` tag (e.g. `git tag v1.0 && git push --tags`)
or manually from the Actions tab. It uploads the APK as an artifact and attaches it to a GitHub Release (tags only).

The APK is signed with the bundled random keystore (`app/release.jks`, credentials in `keystore.properties`),
so builds install and update over each other with no setup. That key is committed to the repo, so treat it as a
sideloading key only: anyone with repo access can sign as you. For Play Store or a private repo-free key, replace
it, or add repo secrets that override it: `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`.
