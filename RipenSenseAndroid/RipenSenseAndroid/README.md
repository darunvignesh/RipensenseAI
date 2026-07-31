# RipenSense AI — Android WebView Shell

A thin native Android app that wraps the RipenSense AI Flask scanner in a
WebView, so it can live as an app icon on your phone / build to an installable
APK. **All the actual logic (camera UI, fruit detection, ML, etc.) still runs
in the Flask app** — this project doesn't reimplement any of it. The Flask
server needs to be running somewhere your phone can reach (your computer, on
the same Wi-Fi, by default).

## ⚠️ Important — I could not build or run this project myself

Everything else I've handed you (the Flask app, the report, the slides) I
actually executed and tested. This Android Studio project I could not — my
environment doesn't have the Android SDK, an emulator, or Gradle's Android
tooling. I wrote it carefully and it should build cleanly, but **you'll be
the first one to actually compile it.** If Android Studio flags something
(most likely a Gradle/AGP/Kotlin version mismatch — those shift often), tell
me the exact error and I'll fix it.

## Setup

1. **Start the Flask server** on your computer (see the main project):
   ```bash
   python app/app.py
   ```
   Find your computer's local IP address:
   - Windows: `ipconfig` → "IPv4 Address"
   - Mac/Linux: `ifconfig` or `ip addr` → look for something like `192.168.1.42`

   Your computer and phone must be on the **same Wi-Fi network**.

2. **Open this folder in Android Studio**: `File → Open` → select the
   `RipenSenseAndroid` folder (the one with `settings.gradle` in it). Let it
   sync Gradle (first sync will download the Android Gradle Plugin, Kotlin,
   and a few AndroxidX libraries — needs internet).

3. **Run it**: plug in your Android phone (with USB debugging enabled in
   Developer Options), or use an emulator, and hit ▶ Run in Android Studio.

4. **On first launch**, the app will ask for the Flask server's address —
   enter `http://192.168.1.42:5000` (using your computer's actual IP from
   step 1). It's saved, so you only enter it once. You can change it later
   from the toolbar's "Set server address" icon.

5. **Grant camera permission** when prompted — this is required for the
   "Scan with Camera" tab to work inside the WebView.

## Building a shareable APK

`Build → Generate Signed Bundle / APK` in Android Studio, or from the command
line:
```bash
./gradlew assembleDebug
# APK will be at app/build/outputs/apk/debug/app-debug.apk
```
A debug APK installs fine for personal/testing use. For real distribution
(e.g. sending to friends who'll install it, or the Play Store), you'd want a
signed release build — Android Studio's build wizard walks through that.

## What's actually in this project

| File | Purpose |
|---|---|
| `MainActivity.kt` | Hosts the WebView, grants its camera permission requests (the #1 thing that silently breaks camera-in-WebView setups), supports the photo-upload file picker, and manages the server-address setting |
| `AndroidManifest.xml` | Declares CAMERA/INTERNET permissions and allows cleartext (plain `http://`) traffic, since a local Flask dev server has no TLS certificate |
| `network_security_config.xml` | The actual cleartext-allow rule (Android blocks plain http by default since API 28) |
| `activity_main.xml` | Toolbar + WebView + a friendly "no server set yet" screen |

## Limitations of this approach (be aware)

- **The phone needs network access to your computer.** If you're not on the
  same Wi-Fi (e.g. mobile data, different network), it won't connect unless
  you deploy the Flask app somewhere phone-reachable (a cloud host, ngrok
  tunnel, etc.) — happy to help set that up if you want the app usable
  anywhere, not just at home.
- **This is a wrapper, not a rewrite.** It doesn't work offline, doesn't run
  the ML model on-device, and won't appear "native" in animations/feel — it's
  a browser tab with the chrome stripped off. That's the tradeoff for how
  fast this was to build versus a full Kotlin + CameraX + on-device-model
  rewrite.
- **Cleartext HTTP is inherently insecure** on an open network — fine for
  home Wi-Fi testing, not something to ship publicly as-is. If you deploy the
  Flask app for real use, put it behind HTTPS and remove the cleartext
  allowance.
