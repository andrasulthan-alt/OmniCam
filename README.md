# OmniCam

A privacy-first, open-source camera app for Android, built to get the most out of older phones' cameras:
it uses every capability the camera reports (manual controls, RAW, high-speed video, variable aperture, OIS) and adds
its own processing, such as built-in HDR, where custom ROMs lack the manufacturer's extensions. OmniCam combines ideas from Open Camera, GrapheneOS Camera,
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
| Aperture control on variable-aperture lenses (e.g. Galaxy S9/S9+: f/1.5 and f/2.4) in manual exposure | Native Camera |
| Built-in HDR photo mode: 3 hand-held exposures (−2 / 0 / +2 EV), automatic alignment, ghost removal and exposure fusion. Works without vendor extensions | Camera Go, Open Camera |
| RAW (DNG), RAW+JPEG, Ultra HDR (where the device supports them) | Open Camera, FreeDcam, Native Camera |
| Histogram, zebra stripes, focus peaking | FreeDcam |
| Timer (3/10 s), burst (3/5/10), grids (3×3, 4×4, golden), volume keys as shutter | Open Camera, Fossify, Libre Camera |
| Zoom chips and lens picker (35 mm equivalent) | GrapheneOS Camera, MA Camera |
| Video: 4K/1080p/720p/480p, 30/60 fps, HDR10 HLG, stabilization, mic toggle, pause/resume, torch | GrapheneOS Camera, Libre Camera |
| SLO-MO mode: hardware high-speed recording (e.g. 120/240 fps), saved as slow-motion video | Open Camera |
| Video bitrate set at or above stock camera apps (e.g. 18 Mbps at 1080p30, 48 Mbps at 4K30) instead of the device default | Open Camera |
| Optical image stabilization kept on for photo and video when the lens has OIS | — |
| Screen-as-flash for photo, and a bright white-screen light with a small live-preview corner while recording video, on cameras with no physical flash (typically the front camera) | Snapchat-style front flash |
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
| Samsung Galaxy S9+ (Exynos 9810), Pixel Experience 13 (custom ROM) | 13 | Photo and video work. See known issues. |
| Samsung Galaxy S20 Ultra (SM-G988B), stock One UI | 13 | Viewfinder reported blurry; fix pending confirmation (0.3.3). |

Tested it on another device? Open an issue with the output of **Copy report**.

## Known issues
- **Fixed in 0.3.3:** the viewfinder previously rendered through `TextureView` (`ImplementationMode.COMPATIBLE`),
  which is documented by Android to add an extra blending pass and can look softer than the default `SurfaceView`
  (`PERFORMANCE`) mode — reported as a blurry/pixelated live preview on a Galaxy S20 Ultra. OmniCam now defaults to
  `PERFORMANCE` and only switches to `COMPATIBLE` for the brief moments the preview is resized into the small corner
  thumbnail (front-camera white-light video mode). Not yet confirmed fixed on the reporter's device.
- **Galaxy S9+ on Pixel Experience 13:** while recording in 4K, the viewfinder shows smeared lines near the edges when the
  phone moves. The saved 4K video is not affected, and 1080p is clean. Other Camera2 apps (e.g. Native Camera) show the same
  artifact at 4K on this ROM, so it comes from the ROM's camera driver, not from OmniCam.
- Video stabilization and vendor extensions (HDR / Night / Portrait) are only offered when the device reports support.
  Many custom ROMs do not ship the manufacturer's extension libraries; the built-in HDR mode works without them.
- Compared with a manufacturer's own camera app, video may still look less polished: stock apps use private
  processing (tuned noise reduction and sharpening, gyro stabilization such as Samsung Super Steady, HDR10+) that
  Android does not expose to other apps. OmniCam matches what it can control, such as bitrate and OIS.
- On phones with a depth-sensing auxiliary camera (used for portrait blur), that sensor is filtered out of the lens
  picker: it cannot take a normal photo or video.
- Built-in HDR corrects hand shake and removes most ghosts from moving subjects by falling back to the normal exposure
  where something moved. In very bright (clipped) areas movement cannot be detected, so faint ghosts are still possible
  there. Processing takes a few seconds on older phones.

## Roadmap
- RAW video (MotionCam-style)
- Exposure/focus bracketing, timelapse, panorama
- Choosing a save folder (SAF / SD card); honoring duration/size limits for `VIDEO_CAPTURE`
- Direct access to physical cameras not exposed through CameraX
- Night mode (multi-frame noise reduction); smarter HDR ghost handling in clipped highlights
- Screen light auto-brightness matched to ambient darkness, instead of a fixed on/off toggle
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
  camera/HdrProcessor.kt      # built-in HDR: frame alignment (MTB) + exposure fusion
  camera/CameraInspector.kt   # camera info screen
  storage/Storage.kt          # preferences, MediaStore output, EXIF scrubbing, geotagging
  model/Models.kt             # UI state and helpers
  ui/                         # CameraScreen, Panels, Components, SettingsAndInfo, Level, ScreenLight (screen-as-flash)
```

## License
GPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
