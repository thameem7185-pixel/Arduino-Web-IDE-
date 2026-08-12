# Arduino Web IDE

A native Android app that wraps a browser-based Arduino IDE, letting you write, verify, and upload Arduino sketches straight from your phone — no PC required. Built for makers whose only computer *is* their phone.

## Why this exists

Most Arduino development assumes you have a laptop or desktop. This project removes that assumption entirely: it's a phone-first Arduino IDE that talks to your board over USB OTG, so you can code, compile, and flash sketches using nothing but an Android device and a USB cable.
<img width="1365" height="662" alt="image" src="https://github.com/user-attachments/assets/fb6d89b7-c2cf-4366-86ed-d0ec5983c663" />
<img width="410" height="735" alt="image" src="https://github.com/user-attachments/assets/d6c4eae5-8ffb-47ef-b58d-323771d4f6ca" />


## Features

- **Full sketch editing** — write and edit Arduino sketches directly on your phone
- **VS Code–style syntax highlighting** — token-based highlighting with distinct dark and light theme palettes, rendered via a transparent textarea overlaid on a `<pre><code>` element for accurate, performant highlighting
- **Verify & Upload workflow** — verify your sketch first; a successful verify unlocks the Upload button. Attempting to upload without a connected board shows a clear, immediate error instead of failing silently
- **USB OTG board detection** — connect an Arduino board via a USB OTG cable and the app detects it automatically through a runtime `BroadcastReceiver`, with a `pageReady` / `pendingAttachedDevice` queue to handle devices that connect before the WebView has finished loading
- **Adaptive board selector** — a compact chip with an SVG board icon and shortened board name on phones, backed by a fully functional native `<select>` as the actual tap target
- **Serial Monitor** — launch a live serial monitor to read output from your board, with a dedicated close button for the console view
- **Smooth performance** — UI updates (like the syntax highlighter overlay) are throttled with `requestAnimationFrame` to stay smooth on mobile hardware

## How it works

The app is a native Android wrapper (Java/Kotlin, `WebView`-based) around a single-page, browser-based Arduino IDE (HTML/CSS/JS). The native layer's main job is bridging things a browser can't do on its own:

1. **USB OTG communication** — the native side owns the USB device lifecycle. When a board is attached, a `BroadcastReceiver` picks up the USB intent and hands the device off to the web layer once it signals it's ready (`pageReady`), queuing any device that attaches before that point (`pendingAttachedDevice`).
2. **Compilation & upload** — sketches are verified/compiled and uploaded to the connected board over the OTG connection.
3. **UI/UX layer** — everything the user sees (editor, syntax highlighting, board selector, serial monitor) lives in the web frontend for fast iteration, styled to feel native rather than like an embedded browser.

This split (native USB/OTG plumbing + web-based editor UI) is what makes phone-only Arduino development possible.

## Project structure

```
├── app/                    # Android application module
│   ├── src/                # Native (Java/Kotlin) + web (HTML/CSS/JS) source
│   └── ...
├── build.gradle             # Project-level Gradle build config
├── gradle.properties         # Gradle properties
├── settings.gradle           # Gradle project settings
└── .github/workflows/        # CI configuration
```

## Getting started

### Requirements

- Android device with USB OTG support
- A USB OTG adapter/cable
- An Arduino board (Uno, Nano, Mega, or similar)
- Android Studio (for building from source)

### Build from source

```bash
git clone https://github.com/thameem7185-pixel/Arduino-Web-IDE-.git
cd Arduino-Web-IDE-
```

Open the project in Android Studio and let Gradle sync, or build from the command line:

```bash
./gradlew assembleDebug
```

The generated APK will be in `app/build/outputs/apk/debug/`.

### Usage

1. Launch the app and open or create a sketch
2. Connect your Arduino board via a USB OTG cable
3. Tap **Verify** to compile the sketch
4. Once verification succeeds, tap **Upload** to flash it to the board
5. Open the **Serial Monitor** to view output from the board in real time

## Roadmap

- [ ] Library manager for installing third-party Arduino libraries
- [ ] Multi-file sketch support
- [ ] Offline board definitions for more devices
- [ ] Packaged APK releases via GitHub Releases

## Contributing

Issues and pull requests are welcome. If you run into a bug — especially around USB OTG detection on specific devices — please open an issue with your board model and Android version.

## License

*(Add your chosen license here — e.g. MIT, Apache 2.0 — and include a `LICENSE` file in the repo root.)*

## Author

Built by [Muhammad Thameem KT](https://github.com/thameem7185-pixel) — aspiring cybersecurity professional, electronics hobbyist, and Arduino enthusiast.
