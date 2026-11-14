# OmniFile

**100% Local, Offline Universal File Converter, Hardware Compressor & File Toolkit for Android.**

Built with Jetpack Compose, Material 3 (Cozy Amber & Material You), Media3 Transformer, and Room. Zero cloud uploads, zero tracking, zero ads—every byte is processed directly on your device.

---

## Features

### 1. Context-Aware Universal Converter
Automatically filters output options to strictly compatible, non-corrupting formats (and computes the intersection when converting mixed batches):
* **Images:** Convert between `JPG`, `PNG`, `WebP`, `BMP`, `ICO`, `ASCII Art`, and `PDF` using OOM-safe two-pass decoding and scanline buffering.
* **Video & Audio:** Extract video frames (`JPG`), demux audio (`M4A`), or decode streams to 16-bit PCM `WAV` using hardware `MediaCodec`.
* **Documents & Data:** Formatted multi-page `Text / Markdown / Code → PDF` via `StaticLayout`, `PDF → Images / Text`, `CSV ↔ JSON`, `JSON → XML`, and styled `CSV / Markdown → HTML`.
* **Archives & Raw Binary:** `ZIP`, `GZIP`, `TAR` packaging/extraction, `Base64` encode/decode, `Hex` dumps, and `C` byte arrays.
* **Privacy & Metadata:** Choose to preserve EXIF/timestamps or strip all metadata for privacy.

### 2. Perceptual Compressor & 3-Tab Video Studio
* **Smart Image Compression:** Resolution-first pixel budgeting paired with binary quality search keeps images sharp without JPEG block artifacts, preserving transparency via WebP.
* **3-Tab Video Studio (Hardware Accelerated via Media3):**
  * **Presets:** Quality profiles (*High, Medium 1080p/30fps, Low 720p/30fps*) and customizable platform targets (*GitHub 10MB, Discord 20MB, Email 25MB, Bluesky/Messenger 100MB, X, Telegram, etc.*).
  * **Advanced Video:** Target size slider, **H.264 (AVC) / H.265 (HEVC)** codec switching, resolution scaling, and FPS capping.
  * **Advanced Audio:** Audio track removal, bitrate control (`48–192 kbps`), and volume gain (`25%–200%`).
  * **Impossible-Size Guard:** Automatically warns when a target bitrate is too tight for the chosen resolution and offers one-tap fixes.
* **Lossless Text & PDF Compression:** Minifies structured text (`JSON`, `CSV`, `XML`, `HTML`) without corrupting syntax, and downsamples PDFs page-by-page.

### 3. Batch & File Utilities Toolbox
* **Bundle ZIP:** Package multiple files into a single archive with selectable Deflate compression levels.
* **File Splitter & Chunk Joiner:** Split large files into `.part001`, `.part002` chunks and reassemble them byte-for-byte with natural index sorting.
* **Data Matrix:** Instant Base64 and Hex text/payload inspector with clipboard copy.

### 4. Scoped Storage & History
* Saves directly to public `Downloads/OmniFile` using Android `MediaStore` (API 29+).
* Indexed local Room database tracks disk space saved with instant filename search, file previews, and sharing.

---

## Tech Stack
* **Language:** Kotlin
* **UI:** Jetpack Compose, Material 3 (Dynamic Color + Cozy Amber theme)
* **Media Engine:** AndroidX Media3 Transformer (`1.5.1`), `MediaCodec`, `MediaExtractor`, `MediaMuxer`, `PdfRenderer` / `PdfDocument`
* **Persistence:** Room Database (indexed schemas, atomic batch transactions)
* **Testing & CI:** JUnit 4, Robolectric (Native Graphics), Kotlin Coroutines Test, GitHub Actions

---

## Building From Source
1. Clone the repository:
   ```bash
   git clone https://github.com/SkyPlay-Code/Omnifile2.git
   ```
2. Run the automated unit test suite:
   ```bash
   ./gradlew testDebugUnitTest
   ```
3. Build a debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
