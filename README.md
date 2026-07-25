# 📷 SonyTransfer

**Lightweight Android app to transfer photos from Sony cameras via WiFi.**

Built as a drop-in replacement for Sony's Imaging Edge Mobile (IEM), which has critical background crash bugs that make it unusable on modern Android devices.

## ✨ Features

- 🔍 **Auto-discover** camera via SSDP (with fallback to common IPs)
- 📂 **Browse** all photos on the SD card with thumbnails
- ☑️ **Multi-select** with Select All / Deselect All
- ⬇️ **Batch download** with per-file progress bar
- 💾 **Saves to** `DCIM/SonyTransfer` (visible in gallery)
- 🌙 **Dark theme** with Sony α-inspired orange accent
- 📡 **WiFi network binding** — works correctly even with mobile data enabled
- 🔓 **No internet required** — runs entirely over camera's local WiFi

## 📱 Supported Cameras

Any Sony camera that supports the **Camera Remote API** (JSON-RPC over WiFi), including:

- **ZV-E10** (original) — primary target
- ZV-1, ZV-1F
- α6000, α6100, α6300, α6400, α6500, α6600
- α7 series (I/II/III), α7R series, α7S series
- α9 series
- RX100 series, RX10 series
- And many more Sony cameras with "Send to Smartphone" feature

> **Note:** ZV-E10 II and newer cameras that use Creators' App may not need this tool.

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
    SSDP M-SEARCH (UDP multicast)
         ↓
    Device Description XML (HTTP GET)
         ↓
    JSON-RPC API (HTTP POST)
    ├── /sony/camera    → setCameraFunction("Contents Transfer")
    └── /sony/avContent → getContentList / getContentCount
         ↓
    Photo Download (HTTP GET on original URL)
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
