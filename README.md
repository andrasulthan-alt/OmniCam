# OmniCam

A privacy-first, open-source camera app for Android, built to get the most out of older phones' cameras:
it uses every capability the camera reports (manual controls, RAW, high-speed video, variable aperture, OIS) and relies on
the phone's own image processing for clean, natural results. OmniCam combines ideas from Open Camera, GrapheneOS Camera,
FreeDcam, Fossify Camera, MA Camera, Libre Camera and CameraX Info into one app. The code is written from scratch in
Kotlin with Jetpack Compose, CameraX and Camera2 interop.

<p align="center">
  <img src="docs/screenshots/1-photo.png" width="19%" alt="PHOTO mode">
  <img src="docs/screenshots/2-pro.png" width="19%" alt="PRO mode with manual ISO and shutter">
  <img src="docs/screenshots/3-video.png" width="19%" alt="VIDEO mode while recording">
  <img src="docs/screenshots/4-slomo.png" width="19%" alt="SLO-MO mode at 240 fps">
  <img src="docs/screenshots/5-settings.png" width="19%" alt="Settings">
</p>
<p align="center"><sub>PHOTO · PRO · VIDEO · SLO-MO · Settings (interface previews rendered from the app's UI; the scenes are illustrations)</sub></p>

> **Status: test build.** OmniCam is used day to day on a few phones but is still young.
> Slow motion, RAW and manual controls depend a lot on the phone and its firmware. If something breaks, open an
> issue and paste the output of **Settings → Camera info → Copy report**.

**Requirements:** Android 11 (API 30) or newer.

## Download
Get the latest APK from [Releases](../../releases). Test builds are marked as *pre-release*.

From 0.3.5 on, releases are optimized (R8) and signed with a permanent key, so future updates install over the
previous version. Versions up to 0.3.4 were debug builds with a temporary key (installed as a separate app,
package `app.omnicam.dev`): uninstall that one once. Your photos and videos stay in your gallery.

## Features
| Feature | Inspired by |
|---|---|
| Photo, video and QR modes | GrapheneOS Camera |
| QR/barcode scanner (ZXing, no Play Services; never opens links automatically). Reads white-on-black codes, product barcodes held either way, and refocuses every 2 s for close-up codes | GrapheneOS Camera, Aperture |
| Strips device identifiers from JPEG EXIF. Location is off by default; when you turn it on, photos get a live position (never one older than 15 minutes), and it is never passed to other apps that ask OmniCam for a photo | GrapheneOS Camera, Aperture |
| Manual ISO, shutter speed, focus, white balance presets, EV compensation | Open Camera, FreeDcam, Native Camera |
| Tap to focus like the iPhone camera: a focus box with a sun appears, slide a finger up or down to change brightness; the spot holds until you turn the phone to a new scene (front and back cameras) | iOS Camera |
| Aperture control on variable-aperture lenses (e.g. Galaxy S9/S9+: f/1.5 and f/2.4) in manual exposure | Native Camera |
| RAW (DNG), RAW+JPEG, Ultra HDR (where the device supports them) | Open Camera, FreeDcam, Native Camera |
| Histogram, zebra stripes, focus peaking | FreeDcam |
| Timer (3/10 s), burst (3/5/10), grids (3×3, 4×4, golden), volume keys as shutter | Open Camera, Fossify, Libre Camera |
| Zoom chips and lens picker (35 mm equivalent) | GrapheneOS Camera, MA Camera |
| Video: 4K/1080p/720p/480p, 30/60 fps, HDR10 HLG, stabilization (1080p30 and below, where Android guarantees it), mic toggle, pause/resume, torch. A setting the camera rejects is stepped down automatically instead of leaving a black screen | GrapheneOS Camera, Libre Camera |
| SLO-MO mode: hardware high-speed recording (e.g. 120/240 fps) with OmniCam's own Camera2 recorder, saved as slow-motion video that plays 4x/8x slower. Built like Google's official sample: framing runs at a lighter frame rate, the bitrate follows the phone maker's own high-speed profile, and clips shot in landscape play upright | Google camera-samples, FreeDcam |
| TIME-LAPSE mode, iPhone-style: one button, no settings. Speed starts at 15x and doubles automatically the longer you record, so the finished clip stays about 20–40 s at a uniform speed (frames are all-intra, so older frames can be thinned without re-encoding). Smooth by design: digital stabilisation (global motion from the median of 3x3 tiles, smoothed camera path, moving crop), deflicker (weighted moving average of brightness), and focus/white balance locked while recording. Landscape clips are saved upright, and if the encoder fails mid-way the part already recorded is kept | iOS Camera, vid.stab, timelapse-deflicker |
| Video bitrate set at or above stock camera apps (e.g. 18 Mbps at 1080p30, 48 Mbps at 4K30) instead of the device default | Open Camera |
| Optical image stabilization kept on for photo and video when the lens has OIS | — |
| Screen-as-flash for photo, and a bright white-screen light with a small live-preview corner while recording video, on cameras with no physical flash (typically the front camera) | Snapchat-style front flash |
| Horizon level indicator | Open Camera |
| Works as the camera for other apps (`IMAGE_CAPTURE` / `VIDEO_CAPTURE`): only the requested mode is shown, and the caller can never make OmniCam overwrite your own photos | Open Camera, Fossify, GrapheneOS Camera |
| Camera info screen (hardware level, capabilities, sensor, RAW sizes, extensions) with a copyable report | CameraX Info |

Every feature is enabled only if your camera reports support for it. Open **Settings → Camera info** to see what
your device exposes, and use **Copy report** when filing a bug.

## Design
Nothing-inspired look: pure black, white and a single red accent, outline pills, and the dot-matrix typeface
[Doto](https://github.com/oliverlalan/Doto) (SIL OFL 1.1) for modes, numbers and readouts. The font is subset to Latin
and digits (about 50 KB). OmniCam is not affiliated with Nothing Technology.

## Privacy and security
- **No INTERNET permission.** The app cannot send data anywhere.
- No Play Services, ads, analytics or trackers. App backups are disabled (`allowBackup=false`).
- Location is off by default and only requested when you enable geotagging. The microphone is only requested for video with audio.
  If a permission was denied for good, the button opens OmniCam's page in system settings instead of doing nothing.
- Device and lens identifiers are stripped from JPEGs by default, and GPS too unless you turned geotagging on.
  QR codes never open links automatically.
- Not yet done: independent security audit, reproducible builds, broad device testing. OmniCam does not claim to be as
  hardened as GrapheneOS Camera.

## Known issues
- **4K viewfinder on some custom ROMs:** the camera driver tears the viewfinder
  while recording 4K; the saved video is clean and a steady 30 fps. Native Camera shows the same. Routing the viewfinder
  through the recording stream (CameraX stream sharing, tried in 0.3.6 and with a minimal custom GL copy) removes the
  tearing but drops 4K video to about 24 fps on this phone, because every 4K frame then has to pass through the GPU and be
  converted again for the video encoder. OmniCam therefore keeps the plain path: smooth 30 fps recordings first.
- Tap to focus works like the iPhone camera: a focus box with a sun appears at the tapped spot; slide a finger up or
  down anywhere on the viewfinder to make the picture brighter or darker. The spot holds until you turn the phone to a
  new scene, then focus and brightness go back to fully automatic.
- Photos are saved at about 12 MP by default, like stock camera apps: 48/50/108 MP sensors combine several pixels
  into one, which gives less noise and better low light. "Full sensor resolution" in Settings saves the largest size.
- Video stabilization is on by default (like stock camera apps) where the device supports it. At 4K and at 60 fps it
  is off: Android does not guarantee it there, and on many phones asking for it freezes the preview or lowers the quality.
- Vendor modes (HDR / Night / Portrait from the phone maker) were removed in 0.4.3: on tested Samsung phones they froze
  the camera even when run with the phone's own settings only. PHOTO uses the phone's normal image processing.
- Slow motion: high-speed recording by third-party apps is known to be unreliable on many Samsung models (reported for
  the Galaxy S9, S10e and S20 main camera, while newer models such as the S24+ work), and the result differs per model
  and firmware. OmniCam retries once, then a lower frame rate, and as a last resort writes the file at the capture rate
  (that file plays at normal speed in most players). If nothing works, SLO-MO is hidden on that phone until the next
  update. A camera that is only busy for a moment (a call, another app) no longer hides it.
- The phone can get hot during long 4K or slow-motion recordings. OmniCam warns when it does and stops and saves the
  recording before the system shuts the camera down.
- Compared with a manufacturer's own camera app, video may still look less polished: stock apps use private
  processing (tuned noise reduction and sharpening, gyro stabilization such as Samsung Super Steady, HDR10+) that
  Android does not expose to other apps. OmniCam matches what it can control, such as bitrate and OIS.
- On phones with a depth-sensing auxiliary camera (used for portrait blur), that sensor is filtered out of the lens
  picker: it cannot take a normal photo or video.

## Roadmap
- RAW video (MotionCam-style)
- Exposure/focus bracketing, panorama
- Opening from the lock screen (double-press power), as GrapheneOS Camera does
- Choosing a save folder (SAF / SD card); honoring duration/size limits for `VIDEO_CAPTURE`
- Direct access to physical cameras not exposed through CameraX
- Night mode and HDR from a multi-frame burst (GCam-style merge), only once it beats the phone's own processing
- Screen light auto-brightness matched to ambient darkness, instead of a fixed on/off toggle
- RGB histogram / waveform, translations, automated tests
- A stable 1.0 release

## References
Behaviour was checked against these open-source camera apps and samples. Two small parts are adapted from them and
credited in [NOTICE](NOTICE); everything else is written for OmniCam:
Google's [android/camera-samples](https://github.com/android/camera-samples) (Camera2 and CameraX slow motion),
[FreeDcam](https://github.com/KillerInk/FreeDcam) (vendor quirks, high-speed sessions),
[LineageOS Aperture](https://github.com/LineageOS/android_packages_apps_Aperture) (CameraX on many devices),
[GrapheneOS Camera](https://github.com/GrapheneOS/Camera), [Fossify Camera](https://github.com/FossifyOrg/Camera),
[MotionCam](https://github.com/mirsadm/motioncam), CameraX Info, and for time-lapse encoding
[Grafika](https://github.com/google/grafika) and [TimeLapseRecordingSample](https://github.com/saki4510t/TimeLapseRecordingSample) (Apache-2.0);
for smooth time-lapses [timelapse-deflicker](https://github.com/cyberang3l/timelapse-deflicker) (GPL-3.0),
[Karry/TimeLapse](https://github.com/Karry/TimeLapse) (weighted-moving-average deflicker) and the path-smoothing idea of
[vid.stab](https://github.com/georgmartius/vid.stab).

## Building
**GitHub Actions:** every push runs the *Build APK* workflow. With the signing secrets configured
(`OMNICAM_KEYSTORE_B64`, `OMNICAM_KEYSTORE_PASSWORD`, `OMNICAM_KEY_ALIAS`, `OMNICAM_KEY_PASSWORD`) it produces a signed,
optimized `omnicam-release-apk`; without them (e.g. in forks) it falls back to `omnicam-debug-apk`.
Forks: open the *Actions* tab and enable workflows first.

**Smoke test:** every push also runs *Smoke test (emulator)*. It installs a debug build on an Android emulator, opens
each mode, takes photos and short recordings, flips the camera, sends the app to the background mid-recording,
answers a photo request from another app, and then runs a few thousand random taps. The job fails if OmniCam
crashes or stops responding; screenshots and the log are attached to the run. The emulator's camera is simulated,
so picture quality and slow motion still need a real phone.

**Android Studio:** open the project, let Gradle sync, then *Build → Build APK(s)*. Output: `app/build/outputs/apk/debug/`.
`gradle-wrapper.jar` is not included; Android Studio generates it, or run `gradle wrapper`.

## Project structure
```
app/src/main/java/app/omnicam/
  MainActivity.kt, CameraViewModel.kt
  Permissions.kt              # permission requests, settings page when denied for good
  ExternalCapture.kt          # IMAGE_CAPTURE / VIDEO_CAPTURE for other apps
  camera/CameraEngine.kt      # CameraX binding, photo/video, manual controls
  camera/Analyzers.kt         # histogram / zebra / peaking and QR scanner
  camera/HighSpeedRecorder.kt # Camera2 constrained high-speed slow-motion recorder
  camera/TimelapseRecorder.kt # automatic time-lapse: frame sampling, all-intra encoding, uniform thinning
  camera/CameraInspector.kt   # camera info screen
  storage/Storage.kt          # preferences, MediaStore output, EXIF scrubbing, geotagging
  model/Models.kt             # UI state and helpers
  ui/                         # CameraScreen, Panels, Components, SettingsAndInfo, Level, ScreenLight (screen-as-flash),
                              # SlowMoSurface (slow-motion viewfinder)
```

## License
GPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
