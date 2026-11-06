# Dalvik DLNA Server

A lightweight, standalone DLNA/UPnP Media Server implemented in pure Java for Android. Designed to run efficiently even on legacy hardware (down to Android 2.3 Gingerbread, API level 9), this server requires zero third-party dependencies or heavy native binaries.

---

## Features

- **Zero External Dependencies:** Built using standard Android and Java networking libraries (`ServerSocket`, `MulticastSocket`, `RandomAccessFile`).
- **SSDP Device Discovery:** Responds to UPnP `M-SEARCH` requests on `239.255.255.250:1900` advertising the `MediaServer:1` specification.
- **UPnP Services Implemented:**
  - `ContentDirectory:1`: Supports `BrowseMetadata` and `BrowseDirectChildren` with DIDL-Lite XML generation.
  - `ConnectionManager:1`: Protocol information advertisement.
- **Byte-Range Streaming:** Full HTTP `Range` request support (`206 Partial Content`) for seekable audio and video playback on modern and legacy DLNA clients.
- **Persistent Service:** Runs as a foreground-capable background service with `WifiManager.MulticastLock` integration to prevent Wi-Fi power saving dropouts.
- **Configurable Port:** Defaults to port `8200` with real-time port adjustment from the UI.

---

## Supported Media Formats

The server automatically maps files found on your external storage (`/sdcard`) to DLNA profile identifiers and MIME types:

| Format | Extensions | DLNA Profile Identifier | MIME Type |
|---|---|---|---|
| **Video** | `.mp4`, `.m4v`, `.mov` | `AVC_MP4_MP_SD_AAC_MULT5` | `video/mp4` |
| **Video** | `.mkv`, `.webm` | `MATROSKA` | `video/x-matroska` |
| **Video** | `.avi` | `AVI` | `video/x-msvideo` |
| **Audio** | `.mp3` | `MP3` | `audio/mpeg` |
| **Images** | `.jpg`, `.jpeg` | `JPEG_LRG` | `image/jpeg` |
| **Images** | `.png` | `PNG_LRG` | `image/png` |

---

## Project Structure

```text
├── .github/
│   └── workflows/
│       └── android.yml       # Automated CI build & artifact packaging
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/example/dlnaserver/
│   │       │   ├── DlnaActivity.java   # Controller UI
│   │       │   └── DlnaService.java    # SSDP & HTTP server engine
│   │       └── res/
│   │           └── layout/
│   │               └── main.xml        # Activity layout
│   └── build.gradle                    # App module build definition
├── build.gradle                        # Root project build definition
└── settings.gradle                     # Repository & project setup
```

---

## How It Works

1. **SSDP Multicast Listener:** `DlnaService` joins multicast group `239.255.255.250` on UDP port `1900`. When a control point (such as a Smart TV, VLC, or Windows Media Player) sends an `M-SEARCH` broadcast, the server responds with a direct UDP packet pointing to the HTTP descriptor XML.
2. **Device Descriptors:** Control points fetch `/rootDesc.xml`, `/cds_scpd.xml`, and `/cms_scpd.xml` via HTTP to inspect supported actions and metadata structures.
3. **SOAP Content Browsing:** Navigation queries sent to `/ctl/ContentDir` receive DIDL-Lite XML responses containing folder structures and playable media items. Unique object IDs are hex-encoded relative paths prefixed with `S0_`.
4. **Streaming Endpoint:** Media streams are served from `/media/<ObjectID>/stream.<ext>` with dynamic buffer chunking and 206 Partial Content headers.

---

## Building

### Prerequisites

- JDK 17
- Gradle 8.0.2 or higher
- Android SDK Build Tools (API 33)

### Local Build

To build the debug APK using Gradle:

```bash
gradle :app:assembleDebug
```

The resulting package will be generated at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI

Every push or pull request to `main`, `master`, or `dlna-2.3` automatically triggers `.github/workflows/android.yml`.

The workflow compiles the project, renames the output to `dlnad.apk`, and uploads it as an artifact named `dlna-server-debug-apk`. You can download the compiled APK directly from the **Actions** tab of this repository.

---

## Running on Device

1. Install `dlnad.apk` on your device or emulator.
2. Ensure the device is connected to the same Wi-Fi network as your client/player.
3. Launch the app, choose a port (default: `8200`), and tap **Start Server**.
4. Open your DLNA client (e.g., VLC > *Local Network* > *Universal Plug'n'Play*, or a Smart TV Media Player) and select **Android 2.3 DLNA Server**.