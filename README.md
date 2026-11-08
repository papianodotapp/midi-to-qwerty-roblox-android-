# Papiano - MIDI to QWERTY for Roblox Android

[![Build APK](https://github.com/Lisztomaniaaa/MIDI-TO-QWERTY-ROBLOX-ANDROID-/actions/workflows/build.yml/badge.svg)](https://github.com/Lisztomaniaaa/MIDI-TO-QWERTY-ROBLOX-ANDROID-/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Lisztomaniaaa/MIDI-TO-QWERTY-ROBLOX-ANDROID-?label=Download%20APK)](https://github.com/Lisztomaniaaa/MIDI-TO-QWERTY-ROBLOX-ANDROID-/releases/latest)

Aplikasi Android ringan yang mengubah input MIDI keyboard menjadi virtual QWERTY keypresses, sehingga kamu bisa main piano game di Roblox menggunakan MIDI keyboard fisik atau MIDI file.

---

## Download & Install

### Cara Cepat (Download APK)

1. **[Download APK Terbaru di sini](https://github.com/Lisztomaniaaa/MIDI-TO-QWERTY-ROBLOX-ANDROID-/releases/latest)**
2. Install APK di HP Android kamu
3. Aktifkan "Install from unknown sources" jika diminta

### Build dari Source

```bash
git clone https://github.com/Lisztomaniaaa/MIDI-TO-QWERTY-ROBLOX-ANDROID-.git
cd MIDI-TO-QWERTY-ROBLOX-ANDROID-
chmod +x gradlew
./gradlew assembleDebug
```

APK akan muncul di `app/build/outputs/apk/debug/app-debug.apk`

---

## Cara Kerja

1. Hubungkan USB MIDI keyboard ke Android (via USB OTG)
2. Aplikasi menangkap MIDI Note On/Off messages
3. Dikonversi menjadi virtual HID keypresses yang dikenali Roblox
4. Main piano game (Roblox Piano, Piano Rooms, dll.) pakai keyboard asli!

---

## Fitur

- Full 88-key support (MIDI notes 21-107)
- Sustain/hold support
- MIDI file playback dengan kontrol speed
- Floating overlay panel untuk status live
- Auto-reconnect saat MIDI device dicabut-colok
- Background service (tetap jalan saat Roblox di foreground)
- Aktivasi via Shizuku / Root / ADB

---

## Requirements

| Kebutuhan | Keterangan |
|-----------|------------|
| Android | 7.0+ (API 24) |
| USB OTG | Wajib untuk MIDI keyboard fisik |
| Aktivasi | Shizuku (recommended), Root, atau ADB |
| Hardware | USB MIDI keyboard + OTG adapter |

---

## Setup Guide

1. Install APK
2. Buka Papiano
3. Tap **Activate** dan pilih metode:
   - **Shizuku** (recommended) - Install Shizuku app, start, lalu activate
   - **Root** - Jika device sudah di-root
   - **ADB** - Jalankan command yang di-copy via `adb shell` dari PC
4. Tap **Start Playing**
5. Grant overlay permission
6. Hubungkan MIDI keyboard via USB OTG
7. Buka Roblox, join piano game
8. Main!

---

## Technical Details

- Android AccessibilityService untuk background operation
- Virtual HID device via `/dev/uhid` (butuh elevated permissions)
- MIDI processing di dedicated HandlerThread (anti ANR)
- Floating panel via SYSTEM_ALERT_WINDOW overlay
- Native C++ layer untuk low-latency HID injection

---

## Credits

Inspired by **[RobloxAndroidMidi](https://github.com/EDLLT/RobloxAndroidMidi)** by EDLLT

---

## License

This project is provided as-is for educational purposes. Use at your own risk.

---

*Discord: @jvkowi*
