# PeerTalk

6-char room code → direct P2P chat, files, voice and video. The server only relays the handshake.

## 1. Signaling (free, ~25 lines)
    cd worker && npx wrangler deploy      # prints https://peertalk.<you>.workers.dev

## 2. App
    cp .env.example .env                  # VITE_SIGNAL=wss://peertalk.<you>.workers.dev
    npm i
    npx cap add android
    sed -i 's|<uses-permission android:name="android.permission.INTERNET" />|&\n<uses-permission android:name="android.permission.CAMERA" />\n<uses-permission android:name="android.permission.RECORD_AUDIO" />\n<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />|' android/app/src/main/AndroidManifest.xml
    npm run apk                           # android/app/build/outputs/apk/debug/

Browser test: `npm run dev`, open two tabs.

## Notes
- Strict NATs / some 5G carriers need a TURN relay: set `VITE_ICE` in `.env`.
- Rooms hold 2 people and vanish when empty.
