# OmniCam — kamera Android gabungan (v0.2, belum pernah di-build)

Aplikasi kamera baru yang **menggabungkan fitur** Open Camera, GrapheneOS Camera, FreeDcam,
MA Camera, Fossify Camera, Libre Camera, CameraX Info, dan konsep Native Camera / MotionCam.
Kode ditulis dari nol (bukan salinan), Kotlin + Jetpack Compose + CameraX 1.6.1 + Camera2Interop.

## Status jujur
- Sintaks Kotlin sudah dicek parser; **belum dikompilasi** terhadap Android SDK/CameraX (Maven Google/Central tidak bisa
  diakses dari lingkungan pembuatnya). Kemungkinan ada beberapa error kecil saat build pertama.
- Verifikasi tambahan (v0.1.1): setiap `import androidx.camera.*` dicek ada di file API publik resmi CameraX
  (`current.txt`, androidx-main), semua anggota `Kelas.member` CameraX yang dipakai ditemukan, dan import Compose/Lifecycle/
  Activity/Core/ExifInterface tidak ada yang hilang. Overload `takePicture` dua-`OutputFileOptions` (RAW+JPEG) memang ada.
  Ini menangkap salah ketik dan API yang tidak ada, **bukan** pengganti kompilasi (tipe, nullability, opt-in tidak teruji).
- Sumber yang benar-benar dibaca kodenya: GrapheneOS Camera, FreeDcam, Fossify Camera, MotionCam (arsip), CameraX Info.
  Open Camera (SourceForge), MA Camera, dan Libre Camera hanya dipakai sebagai acuan fitur, bukan dibaca kodenya.
- Versi toolchain disamakan dengan GrapheneOS Camera (AGP 9.3.1, Kotlin 2.4.10, Gradle 9.6.1, CameraX 1.6.1,
  ZXing 3.5.4); Compose BOM 2026.06.00.
- Belum diuji di perangkat. Area paling berisiko: RAW/RAW+JPEG, ekstensi vendor, kontrol manual per-vendor.

## Fitur v0.1 dan asalnya
| Fitur | Terinspirasi dari |
|---|---|
| Foto, video, mode QR, binding per-mode | GrapheneOS Camera |
| Pindai QR/barcode (ZXing, tanpa Play Services; tidak pernah membuka tautan otomatis) | GrapheneOS Camera |
| Hapus GPS + identitas perangkat dari EXIF JPEG; lokasi default MATI; izin diminta saat dibutuhkan | GrapheneOS Camera |
| Probe aman ekstensi vendor (HDR/Malam/Potret/Retouch) agar tidak crash | GrapheneOS Camera |
| ISO, kecepatan rana, fokus manual, white balance preset, kompensasi EV | Open Camera, FreeDcam, Native Camera |
| RAW (DNG) dan RAW+JPEG, Ultra HDR | Open Camera, FreeDcam, Native Camera |
| Histogram, zebra, focus peaking | FreeDcam |
| Timer 3/10 dtk, burst 3/5/10, grid (3×3, 4×4, golden), tombol volume = rana | Open Camera, Fossify, Libre |
| Chip zoom 0.5×/1×/2×… + pilih lensa (ekuivalen mm) | GrapheneOS Camera, MA Camera |
| Video: 4K/1080p/720p, 30/60 fps, HDR10 HLG, stabilisasi, mic, pause/resume, senter | GrapheneOS Camera, Libre Camera |
| Indikator level horizon (akselerometer, hijau saat rata) | Open Camera |
| Dipakai app lain sebagai kamera: `IMAGE_CAPTURE` / `VIDEO_CAPTURE` (+ `EXTRA_OUTPUT`, thumbnail) | Open Camera, Fossify, GrapheneOS Camera |
| Layar Info kamera (level hardware, kapabilitas, sensor, RAW size, ekstensi, dll) | CameraX Info |
| UI Compose minimalis, tanpa iklan/tracker/Play Services | MA Camera, Fossify |

## Catatan v0.2
- Ditambahkan: level horizon dan dukungan intent capture (tidak teruji di perangkat). Intent capture memakai `launchMode=singleTop`
  agar hasil `startActivityForResult` sampai ke pemanggil. Level hanya akurat pada orientasi potret (aplikasi terkunci potret).
- Kalau build pertama gagal, kirim log error Gradle-nya; perbaikannya biasanya kecil (tipe/nullability/opt-in).

## Belum ada (roadmap)
- **RAW video** ala MotionCam (butuh pipeline Camera2 + encoder DNG/CinemaDNG native; proyek besar tersendiri).
- Exposure/focus bracketing, timelapse, panorama, remote control (Open Camera).
- Pilih folder simpan via SAF/SD card; `EXTRA_DURATION_LIMIT`/`EXTRA_SIZE_LIMIT` pada VIDEO_CAPTURE belum dihormati.
- Kamera fisik langsung lewat Camera2 (di luar yang diekspos CameraX), slow-motion (CameraX 1.5 high-speed).
- Histogram RGB/waveform, i18n (string masih hardcode Indonesia), tes otomatis.

## Build
1. Buka folder ini di Android Studio versi terbaru (mendukung AGP 9.3), pasang Android SDK 37.
2. `gradle-wrapper.jar` tidak disertakan: Studio akan membuatnya, atau jalankan `gradle wrapper --gradle-version 9.6.1`.
3. Run konfigurasi `app` di perangkat fisik (emulator tidak punya kontrol manual/RAW/ekstensi yang berarti). minSdk 30.

## Profil perangkat: Galaxy S9+ (star2lte, Exynos 9810) + Pixel Experience 13
Target uji pertama. Yang perlu diketahui sebelum mencoba:
- **Semua fitur dikunci oleh kemampuan yang dilaporkan kamera**, bukan asumsi model. Buka *Pengaturan → Info kamera → Salin laporan* dan
  tempel hasilnya ke sini; dari situ fitur yang benar-benar tersedia (RAW, kontrol manual, lensa tele, stabilisasi) bisa dituning tepat.
- **Kemungkinan tersedia:** foto/video, zoom, fokus/AE sentuh, timer, burst, grid, level, QR, histogram/zebra/peaking, ISO/rana/fokus manual
  jika level hardware mendukung MANUAL_SENSOR.
- **Kemungkinan tidak tersedia:** ekstensi vendor HDR/Malam/Potret (perlu pustaka OEM yang tidak ada di ROM AOSP), Ultra HDR (baru ada di Android 14),
  video HDR10/HLG, dan RAW kamera depan. RAW kamera belakang belum terverifikasi di ROM ini.
- Lensa tele (2×) hanya muncul jika ROM mengeksposnya ke Camera2; kalau tidak, zoom 2× adalah zoom digital.
- Uji dulu dengan foto/video biasa, baru mode PRO; jika pratinjau hitam atau aplikasi berhenti, salin log `adb logcat -s CameraX Camera2` untuk diagnosis.

## Membuat APK
Lingkungan pembuat tidak punya Android SDK dan tidak bisa mengunduh dependensi Google, jadi APK belum ada. Dua cara:
1. **GitHub Actions (tanpa install apa pun):** buat repo GitHub baru, unggah isi folder ini, buka tab *Actions* → *Build APK* →
   *Run workflow*. Setelah selesai, unduh artefak `omnicam-debug-apk`. Jika gagal, salin log langkah *Build debug APK*.
2. **Android Studio:** buka folder ini, tunggu sync, lalu *Build → Build APK(s)*; hasil di `app/build/outputs/apk/debug/`.
APK debug ditandatangani kunci debug: cukup untuk pemakaian pribadi, bukan untuk rilis. Untuk rilis buat keystore sendiri.

## Struktur
```
app/src/main/java/app/omnicam/
  MainActivity.kt, CameraViewModel.kt
  camera/CameraEngine.kt      # binding CameraX, foto/video, manual (Camera2Interop), ekstensi
  camera/Analyzers.kt         # histogram/zebra/peaking + pemindai QR
  camera/CameraInspector.kt   # layar Info kamera
  ExternalCapture.kt          # IMAGE_CAPTURE / VIDEO_CAPTURE dari app lain
  ui/Level.kt                 # indikator level horizon
  storage/Storage.kt          # Prefs, MediaStore, scrub EXIF, geotag
  model/Models.kt             # state UI + helper
  ui/                         # CameraScreen, Panels, Components, SettingsAndInfo
```

## Lisensi
GPL-3.0-or-later (lihat `LICENSE` dan `NOTICE`). Kode asli; GrapheneOS Camera (MIT) hanya dipakai sebagai acuan.

## Keamanan & privasi (by design)
- **Tanpa izin INTERNET** di manifest: aplikasi secara teknis tidak bisa mengirim data keluar.
- Tanpa Play Services, iklan, analitik, atau tracker; `allowBackup=false`.
- Lokasi mati secara default dan izinnya baru diminta saat Anda menyalakannya; mikrofon hanya diminta saat merekam dengan audio.
- EXIF JPEG dibersihkan (GPS + identitas perangkat/lensa) secara default; kode QR tidak pernah membuka tautan otomatis.
- Yang belum dilakukan: audit keamanan independen, build yang bisa direproduksi, dan pengujian di perangkat. Jangan menyebutnya
  "seaman GrapheneOS Camera" sebelum itu ada; klaim yang jujur saat ini: minim permukaan serang dan tanpa akses jaringan.
