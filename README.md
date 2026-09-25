# OmniCam

A privacy-first, open-source camera app for Android. OmniCam combines ideas from Open Camera, GrapheneOS Camera,
FreeDcam, Fossify Camera, MA Camera, Libre Camera and CameraX Info into one app. The code is written from scratch in
Kotlin with Jetpack Compose, CameraX and Camera2 interop.

> **Status: early test build.** OmniCam builds successfully but has only been tested on one device so far.
> Expect bugs, especially in RAW capture, vendor extensions and manual controls. Please report issues.

**Requirements:** Android 11 (API 30) or newer.

## Download
Get the latest APK from [Releases](../../releases). Test builds are marked as *pre-release*.

Test builds are signed with a temporary debug key. To move to a newer test build you may need to uninstall the
old one first. Your photos and videos stay in your gallery; only the app's settings are reset.

## Features
| Feature | Inspired by |
|---|---|
| Photo, video and QR modes | GrapheneOS Camera |
| QR/barcode scanner (ZXing, no Play Services; never opens links automatically) | GrapheneOS Camera |
| Strips GPS and device identifiers from JPEG EXIF; location off by default; permissions asked only when needed | GrapheneOS Camera |
| Safe probing of vendor extensions (HDR / Night / Portrait / Retouch) | GrapheneOS Camera |
| Manual ISO, shutter speed, focus, white balance presets, EV compensation | Open Camera, FreeDcam, Native Camera |
| RAW (DNG), RAW+JPEG, Ultra HDR (where the device supports them) | Open Camera, FreeDcam, Native Camera |
| Histogram, zebra stripes, focus peaking | FreeDcam |
| Timer (3/10 s), burst (3/5/10), grids (3×3, 4×4, golden), volume keys as shutter | Open Camera, Fossify, Libre Camera |
| Zoom chips and lens picker (35 mm equivalent) | GrapheneOS Camera, MA Camera |
| Video: 4K/1080p/720p/480p, 30/60 fps, HDR10 HLG, stabilization, mic toggle, pause/resume, torch | GrapheneOS Camera, Libre Camera |
| Horizon level indicator | Open Camera |
| Works as the camera for other apps (`IMAGE_CAPTURE` / `VIDEO_CAPTURE`) | Open Camera, Fossify, GrapheneOS Camera |
| Camera info screen (hardware level, capabilities, sensor, RAW sizes, extensions) with a copyable report | CameraX Info |

Every feature is enabled only if your camera reports support for it. Open **Settings → Camera info** to see what
your device exposes, and use **Copy report** when filing a bug.

## Privacy and security
- **No INTERNET permission.** The app cannot send data anywhere.
- No Play Services, ads, analytics or trackers. App backups are disabled (`allowBackup=false`).
- Location is off by default and only requested when you enable geotagging. The microphone is only requested for video with audio.
- JPEG metadata (GPS, device and lens identifiers) is stripped by default. QR codes never open links automatically.
- Not yet done: independent security audit, reproducible builds, broad device testing. OmniCam does not claim to be as
  hardened as GrapheneOS Camera.

## Tested devices
| Device | Android | Result |
|---|---|---|
| Samsung Galaxy S9+ (Exynos 9810), Pixel Experience 13 (custom ROM) | 13 | Testing in progress |

Tested it on another device? Open an issue with the output of **Copy report**.

## Roadmap
- RAW video (MotionCam-style)
- Exposure/focus bracketing, timelapse, panorama
- Choosing a save folder (SAF / SD card); honoring duration/size limits for `VIDEO_CAPTURE`
- Direct access to physical cameras not exposed through CameraX; slow motion
- RGB histogram / waveform, translations, automated tests
- A permanent release signing key and a stable release

## Building
**GitHub Actions:** every push runs the *Build APK* workflow. Download the `omnicam-debug-apk` artifact from the run page.
Forks: open the *Actions* tab and enable workflows first.

**Android Studio:** open the project, let Gradle sync, then *Build → Build APK(s)*. Output: `app/build/outputs/apk/debug/`.
`gradle-wrapper.jar` is not included; Android Studio generates it, or run `gradle wrapper`.

## Project structure
```
app/src/main/java/app/omnicam/
  MainActivity.kt, CameraViewModel.kt
  ExternalCapture.kt          # IMAGE_CAPTURE / VIDEO_CAPTURE for other apps
  camera/CameraEngine.kt      # CameraX binding, photo/video, manual controls, extensions
  camera/Analyzers.kt         # histogram / zebra / peaking and QR scanner
  camera/CameraInspector.kt   # camera info screen
  storage/Storage.kt          # preferences, MediaStore output, EXIF scrubbing, geotagging
  model/Models.kt             # UI state and helpers
  ui/                         # CameraScreen, Panels, Components, SettingsAndInfo, Level
```

## License
GPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
