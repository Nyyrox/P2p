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

- Text chat and **file sharing** (paperclip button). Files go straight to the other phone over the same encrypted
  data channel, with live progress and a cancel button on both sides. The receiver gets **Open** and **Save**.
- Each person's name is shown to the other; tap **You: name** to edit yours any time.
- Tap the **phone** icon for a voice call or the **camera** icon for a video call. The other person gets an
  incoming-call card and can **Accept** or **Decline**.
  - Accept: both phones switch to the call screen.
  - Decline / no answer (45 s) / cancel: you simply stay in the chat. A line in the chat records what happened.
- **Mic and camera permissions are only requested on the call screen**, never when creating, joining or chatting.
  Deny them and you can still listen or watch; the call screen has an *Allow* button (or *Open settings* if blocked).
- Ending a call returns both people to the chat; the session stays open until someone leaves.

## In the call

- Voice with mute, speaker/earpiece and a call timer.
- **Video**: toggle your camera any time (flip front/back from the preview). A voice call can become a video call.
- **Screen share**: tap Share, accept the system prompt, then switch to any app. A red "You're sharing" card (and a
  *Stop sharing* button in the notification) makes it obvious. The viewer gets a full-screen picture with
  tap-to-hide controls, a fit / fill toggle and a loading state until the first frame arrives.
- Everyone can share their screen and use their camera at the same time; the screen takes the main stage and the
  camera becomes a small picture-in-picture.

## Minimise and maximise a call

- Tap the **down arrow** (or press Back) on the call screen: you return to the chat and the call keeps running.
  A bar at the top of the chat shows the call and timer, with mute and end buttons. Tap it to maximise again.
- Press **Home** during a call and Android shows it in a small floating **picture-in-picture** window; tap the
  window to expand it. (Needs "picture-in-picture" allowed for the app in Android settings.)

## Design

Plain black and white, light and dark following the system, no gradients and no accent colours. Green, amber and
red appear only as status colours (online dot, accept / decline / end).

## How media is wired

Three media lines are negotiated once (audio, camera, screen), all send/receive. Their senders stay empty until
needed, so starting or stopping mic, camera or screen share never needs a new code exchange.
Video is drawn with a TextureView-based renderer (`ui/VideoTextureRenderer.kt`), because a SurfaceView goes black inside
Compose clips, fades and rounded corners. Video uses software VP8 on both phones. Hardware H.264 encoders differ a lot between devices and were the most
likely reason screen share worked on one phone and showed nothing on another. The viewer also keeps the last
received frame, so a still screen (which sends no new frames) is not black after the view is recreated.

If screen share still fails, run `adb logcat -s PeerTalk` while you start it. Every step logs there, and errors
also appear as a banner on the call screen.

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
| `CallService.kt` | Foreground service. Chat = dataSync only; mic / camera / mediaProjection types are added only while in use (Android 14+) |
| `ui/PeerTalkApp.kt` | Root navigation: home, create, join, chat, call, ended |
| `ui/Theme.kt`, `ui/Components.kt` | Material 3 theme (light/dark) and shared composables |
| `ui/SetupScreens.kt` | Home, create (host), join (guest), ended |
| `ui/ChatScreen.kt` | Chat, name editing, call button, ringing banner, incoming-call card |
| `ui/CallScreen.kt` | In-call UI (stage, camera preview, screen-share viewer); the only place mic / camera are requested |
| `ui/VideoViews.kt`, `ui/VideoTextureRenderer.kt` | Compose video view on a TextureView renderer (fit / fill, mirror, first-frame callback) |
| `rtc/VideoRelay.kt` | Fan-out of video frames to renderers, replays the last frame to late joiners |

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
