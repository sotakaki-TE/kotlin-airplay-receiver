# TCL 65C646 Test Installation

This project targets a TCL 65C646 running Android TV OS 11.

## First Compatibility Test

Use `dist/Receiver-upstream-0.4.12.apk` for the first test. This is the
unmodified upstream release APK. It confirms whether AirPlay discovery,
H.264 screen mirroring, and AAC audio work on the TV before building the
TCL-specific variant.

1. Copy `dist/Receiver-upstream-0.4.12.apk` to a USB drive.
2. On the TV, install a file manager from Google Play if one is not already
   available.
3. Open the APK from the USB drive.
4. When prompted, allow the file manager to install unknown apps.
5. Launch `Receiver`.
6. Keep the TV and iPhone on the same Wi-Fi network.
7. On the iPhone, open Control Center, select `Screen Mirroring`, and choose
   the receiver name shown by the app.

Start with `720p` if the connection is unstable. Use `1080p` for photos,
documents, and slides once the basic test works.

## Expected Limitations

- DRM-protected video services may display a black screen.
- The upstream APK was designed for a touchscreen device. Use it only for the
  compatibility test.
- The TCL-specific source changes in this repository add Android TV launcher
  support, remote-friendly focus navigation, a default `1080p` mode, and a
  persistent waiting screen after a mirrored video stops. Build that variant
  after the Android SDK is installed on the development PC.
