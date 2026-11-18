# Inkwell Reader

A modern Android e-book reading application built with Kotlin and Material 3.

## Features

- **Format Support**: Reflowable EPUB documents, PDF files, and plain text notes.
- **Display Customization**:
  - Six color palettes (Light, Sepia, Sand, Charcoal, OLED Black, Mint).
  - Configurable font sizes, font families (Serif, Sans-Serif, Monospace), and line spacing.
  - Software screen dimmer for low-light environments.
  - Screen wake-lock toggle to prevent display timeouts while reading.
- **Document Management**:
  - Import documents using the Android Storage Access Framework.
  - Automatically parses document structure and table of contents.
  - Local offline storage for bookmarks and reading progress.

## Building from Source

```bash
git clone https://github.com/<YOUR_USERNAME>/inkwell-reader.git
cd inkwell-reader
./gradlew assembleDebug
```

The resulting package will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
