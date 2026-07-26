# 📷 SonyTransfer

**Lightweight Android app to transfer photos from Sony cameras via WiFi.**

Built as a drop-in replacement for Sony's Imaging Edge Mobile (IEM), which has critical background crash bugs that make it unusable on modern Android devices.

## ✨ Features

- 📂 **Browse** all photos and videos on SD card with thumbnails
- ☑️ **Multi-select** batch download
- ⬇️ **Per-file progress** during download
- 💾 **Saves to** `DCIM/SonyTransfer` (visible in gallery)
- 🎬 **Streaming download** — supports 2GB+ videos without OOM
- 📶 **WiFi gateway auto-connect** — reads camera IP from DHCP
- 📡 **WiFi network binding** — works correctly even with mobile data enabled
- 🔓 **No internet required** — runs entirely over camera's local WiFi

## 📱 Supported Cameras

Any Sony camera that supports **"Send to Smartphone" mode** (PTP/IP over WiFi, port 15740), including:

- **ZV-E10** (original, firmware v2.02) — primary target & test platform
- ZV-1, ZV-1F
- α6000 series, α6100, α6300, α6400, α6500, α6600
- α7 series (I/II/III), α7R series, α7S series
- α9 series
- RX100 series, RX10 series
- And many more Sony cameras with "Send to Smartphone" feature

> **Note:** ZV-E10 II and newer cameras that use Creators' App may not need this tool.

> **Note:** ZV-E10 II and newer cameras that use Creators' App may not need this tool.

## 📚 Third-Party Libraries

This project incorporates source code from the following library:

- **[libptp](https://github.com/Fimagena/libptp)** — Java implementation of PTP/IP protocol (ISO 15740).  
  Copyright (C) 2017 Fimagena (fimagena at gmail dot com).  
  Licensed under the **GNU Lesser General Public License v2.1** (see [`libptp-LGPL-2.1.txt`](libptp-LGPL-2.1.txt)).  
  libptp source files are located in `app/src/main/java/com/fimagena/libptp/`.

The library has been modified to support streaming file transfers (see AGENTS.md for details). All modifications are released under LGPL 2.1 as required by the license.

## 🚀 Usage

1. **On camera:** Menu → Network → Send to Smartphone (or "Ctrl w/ Smartphone")
2. **On phone:** Connect to the camera's WiFi network (`DIRECT-xxxx-ZV-E10`)
3. **Open SonyTransfer** → tap "Connect to Camera"
4. **Browse & select** photos → tap "Download"
5. Photos saved to `DCIM/SonyTransfer` 🎉

## 📦 Download

Go to [Actions](../../actions) → click the latest successful build → download **SonyTransfer-APK** from Artifacts.

## 🛠️ Technical Details

### Protocol Stack
```
Phone ←WiFi→ Camera (AP mode)
         ↓
    DHCP Gateway IP (192.168.122.1)
         ↓
    PTP/IP Protocol (ISO 15740, port 15740)
    ├── OpenSession
    ├── GetStorageIDs
    ├── GetObjectHandles (no format filter)
    ├── GetObjectInfo  → filename / date / size / format
    ├── GetThumb(handle)
    └── GetObject(handle)  → streaming to MediaStore
```

### Architecture
- **Kotlin** + **Jetpack Compose** + **Material 3**
- **OkHttp** for HTTP (WiFi-network-bound socket factory)
- **Coil** for thumbnail loading
- **MVVM** with `StateFlow`

### Why not just fix IEM?
Sony's Imaging Edge Mobile has a bug where the app process dies within ~100ms of going to background, even before connecting to any camera. This is an app-level defect (not OS kill), and Sony hasn't fixed it despite years of complaints. This app bypasses IEM entirely by speaking the camera's HTTP API directly.

## 📄 License

MIT — do whatever you want with it.
