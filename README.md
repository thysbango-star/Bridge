# Flow Share

A native Android app for sending files phone-to-phone, in the spirit of AirDrop and Samsung Quick Share.
Built with Kotlin, Jetpack Compose and Google **Nearby Connections**. No internet or cloud is involved: files go
straight between the phones over Bluetooth and Wi-Fi.

## Features

| | |
|---|---|
| **Discovery** | Each phone advertises and scans at the same time with Nearby Connections (BLE + Bluetooth + Wi-Fi). Nearby phones show up as cards with a live status: Available, Connecting, Connected. |
| **Send anything** | Photos, videos, documents, APKs and archives, many files at once. You can also share into Flow Share from Gallery or My Files. |
| **Accept / decline** | The receiver sees the sender's name, the file list, the total size and a verification code. Nothing is saved until they tap **Accept**. |
| **Progress** | Live percentage, speed (smoothed), time remaining, and progress for each file. |
| **Large transfers** | Files are streamed as `FILE` payloads (never loaded into memory). Nearby upgrades the link to Wi-Fi Direct or a hotspot for speed. A foreground service keeps the transfer going with the screen off. |
| **Security** | Every link is authenticated and encrypted by Nearby's UKEY2 handshake (AES-256). Both phones show the same 4-digit code. Incoming file names are cleaned so they can't escape the save folder, unexpected payloads are rejected, and received files go to `Downloads/Flow Share`. |
| **QR pairing** | The receiver shows a QR code containing a random token that changes on every launch. The sender scans it and connects to exactly that phone, and the receiver sees "QR verified". |
| **Bump to share** | With one phone nearby, tapping the phones together starts the connection (accelerometer bump detection). |

## Sharing from Gallery (works over other apps)

**Sending:** in Samsung Gallery, select photos → **Share → Flow Share**. A see-through Flow Share layer
opens *on top of Gallery*, showing your thumbnails and "Tap phones together". Bump the phones (or tap the
phone's chip) and the liquid bloom plays over Gallery. A compact card with a mini liquid vessel shows
progress, then the layer closes and you're back in Gallery.

**Receiving:** on Home, turn on **Receive over other apps** and allow **Display over other apps**.
Flow Share keeps a small "Ready to receive" notification, and while you're in Gallery (or any app):
- the bloom plays on top of the screen the moment the other phone connects,
- the accept card drops in from the top (contact) edge,
- liquid flows along the top edge during the transfer, followed by a settling wave and a success mark,
- photos and videos land in a **Flow Share album** in Gallery (Pictures/Flow Share, Movies/Flow Share).

Without the overlay permission, a heads-up notification with **Accept / Decline** is shown instead.

Limits (Android security rules):
- Apps can't read another app's selection, so Gallery's **Share → Flow Share** step is needed to tell Flow Share which photos to send.
- Over another app the bloom is drawn *on top* as a glowing liquid layer. Bending Gallery's own pixels would require screen recording.
- The full-screen bloom layer is 80% opaque so that Android 12+ keeps passing your touches through to Gallery.
- Samsung may put background apps to sleep. If receiving stops, go to Settings → Battery → Background usage limits and add Flow Share to **Never sleeping apps**.

## The liquid animation

The phone-to-phone animation is rebuilt from the reference video, studied frame by frame:

| Reference moment | Flow Share |
|---|---|
| Phones touch at the top edges | The connection starts from a tap or a bump. On success **both** phones play the contact effect at the same moment (`FlowEvent.Contact`). |
| A bright bloom with an iridescent rim swells out of the contact edge (~0.2 s) | AGSL `RuntimeShader` applied as a `RenderEffect` over the whole UI. A wobbly, flattened circular front grows from the top-centre with a violet → orchid → warm-white rim. |
| Content behind it bends, blurs and splits into colours | The real screen pixels are moved outward along the front, with a separate offset for each colour channel and a 4-tap frosted blur. |
| The screen washes out milky-bright, brightest at the contact edge (~0.6 s) | A liquid body with slow light ripples floods in behind the front and fades toward the far side. An ocean swell keeps rolling inside it. |
| The front leaves the screen and the share card settles in (~1.1–1.5 s) | Screens switch underneath the bloom at its brightest moment. The incoming card rises out of the top edge on a spring. |
| Transfer | Full-screen `LiquidTank`. Liquid pours *out of* the sender's top edge and *into* the receiver's, like two connected vessels. Faster transfers make the surface more agitated. Bubbles flow in the direction of the transfer. |
| Completion | A long, low settling wave plus a success mark (the ring draws in, the check writes itself, one soft shock ring). |

Timing: `LiquidController.contact()` – radius 0 → 1.22 × the screen diagonal over 1250 ms (`cubic-bezier(.25,.1,.12,1)`),
strength up over 260 ms, held, then down over 620 ms; the milky fill peaks at 640 ms. Haptics follow the same curve.

Android 13+ gets the full shader. Android 8–12 gets a Canvas version (wobbly front, sweep-gradient rim, glow).

> **Why "tap or bump" instead of true proximity?** Android has no public API that tells two phones they are touching.
> Flow Share finds phones with Nearby Connections. You then tap the phone's card, or bump the phones together when
> only one is nearby, and the contact animation starts the moment the encrypted link is up.

## Project layout

```
app/src/main/java/com/flowshare/app/
├── FlowShareApp.kt / MainActivity.kt / FlowViewModel.kt
├── core/       DeviceIdentity, FileStore (MediaStore saving), Permissions, BumpDetector, Haptics, Models
├── nearby/     NearbyManager (discovery, connection, offer/accept protocol, payloads), Protocol, Session, TransferService
└── ui/
    ├── liquid/ LiquidSurface (AGSL contact shader + controller), LiquidDraw (tank, orb, rings, success)
    ├── screens/ Home, Nearby, Transfer, Overlays (incoming card, QR, permissions)
    ├── components/ and theme/
```

## Build the APK

### Option A: GitHub Actions (no setup)
Every push to `main` runs `.github/workflows/build-apk.yml`, which:
1. builds `FlowShare.apk` (release, faster animations) and `FlowShare-debug.apk`
2. attaches both to the **"latest"** release on the repo's Releases page, and to the run's artifacts.

### Option B: Android Studio
1. Install **Android Studio** (Ladybug or newer), with JDK 17 included.
2. **File → Open** this folder and let Gradle sync. It downloads AGP 8.6, Kotlin 2.0 and the Android SDK 35 platform.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or from a terminal:
   ```bash
   ./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
   ```

Both build types are signed with the development key in `keystore/`, so every build installs over the previous one.
Replace it with your own private key before publishing.

## Install on Samsung phones

**Directly on the phone (easiest):**
1. On each Galaxy phone, open the repo's **Releases → latest** page in Chrome or Samsung Internet and download `FlowShare.apk`.
2. Open it from the download notification or **My Files → Downloads**.
3. When asked, allow **Install unknown apps** for that browser or My Files, then tap **Install**.
   If **Auto Blocker** is on (Settings → Security and privacy → Auto Blocker), turn it off for the install.

**Over USB with adb:**
1. Settings → About phone → Software information → tap **Build number** 7× to unlock Developer options.
2. Settings → Developer options → enable **USB debugging**, then connect the phone and accept the prompt.
3. `adb install -r FlowShare.apk` (or press **Run ▶** in Android Studio).

**First run (on both phones):** allow **Nearby devices** and **Location**, and turn on Bluetooth and Wi-Fi.
Location only needs to be on for discovery; Flow Share never reads your position. Notifications are optional (transfer progress).

## Using it
1. **Receiver:** open Flow Share and leave **Visible** on.
2. **Sender:** tap **Share Nearby**, pick files, then tap the receiver's card. You can also bump the phones together, or tap **Scan QR code instead** while the receiver shows **Receive via QR**.
3. Both phones play the contact animation. The receiver checks the code matches and taps **Accept**.
4. Files are saved to **Downloads / Flow Share**.

## Requirements
- Android 8.0+ (API 26). Full shader effect on Android 13+ (all recent Galaxy S, A and Z phones).
- Google Play services (present on every Samsung phone sold with Play Store).
- Both phones need Flow Share installed. Quick Share and AirDrop use their own protocols, so Flow Share can't talk to them.

## Tech
Kotlin 2.0.21 · AGP 8.6.1 · Gradle 8.9 · Compose BOM 2024.09.03 · Material 3 · play-services-nearby 19.3.0 · ZXing 3.5.3 / zxing-android-embedded 4.3.0 · compileSdk/targetSdk 35 · minSdk 26
