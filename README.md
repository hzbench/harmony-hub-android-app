# Harmony Remote (Android WebView wrapper)

Minimal Android app that hosts the `harmony-hub-control` web UI (loaded
live from the hub, unmodified) and adds exactly one native capability:
foreground hardware volume-key capture. Full design rationale is in the
main `PLAN.md`, under "Spec: minimal WebView wrapper with volume-key
control".

**Status: builds and runs.** It has been built with Gradle and run on a
real phone (Pixel 6 Pro) against the hub's web UI, including the
volume-key hook, the `confirm()`/`alert()` dialogs, keep-screen-on and the
"Change hub address" menu.

## Prerequisites

- Android Studio (recommended — handles the Gradle wrapper, SDK, and JDK
  for you), or: JDK 17 + Android SDK command-line tools + Gradle 8.7.
- The hub must serve the daily-use UI at `/go/` and its
  `window.harmonyHardwareVolume` hook (`../control/payload/www/go.js`; the
  original pages at the root keep their own copy in `app.js`).

## Building and running

From the command line:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open this `android-app/` folder in Android Studio, let it sync, and
run it on a device over USB. For a release APK use Build > Generate
Signed Bundle / APK and sideload it — this app isn't going through the
Play Store.

## Tests and CI

```
./gradlew testDebugUnitTest   # JVM unit tests (hub address handling)
./gradlew lintDebug           # Android lint; errors fail, warnings don't
```

GitHub Actions (`.github/workflows/build.yml`) runs the unit tests, builds
the debug APK and runs lint on every pull request and push to `main`, and
keeps the APK and lint report as artifacts. It can't run the app: volume
keys, the error and loading screens and the dialogs still need a phone.

## First run

On first launch it asks for the hub's address (`<ip>:8080` or a full
`http://...` URL), stores it in `SharedPreferences`, and loads the hub's
`/go/` UI (a stored address that already ends in `/go/` works too). Change it later
via the overflow menu → "Change hub address". There's no discovery
mechanism by design — set a DHCP reservation for the hub's MAC in your
router so the address stays stable (see `PLAN.md`).

## Error screen

If the hub's page can't be loaded, a native screen replaces Android's
default error page: "Can't reach the hub" with the saved address, **Try
again** and **Change hub address**. It appears for a main-page failure
only (hub off, wrong address, different network, or an HTTP error such
as a hub without the `/go/` UI, which gets its own "update the hub"
message); failed sub-resources are ignored. It clears when a page loads
successfully.

## Cleartext HTTP

The hub serves plain HTTP by design (no TLS — see the main repo's
`docs/SECURITY.md`). Android blocks cleartext WebView traffic by default
on API 28+, so this app currently sets `android:usesCleartextTraffic=
"true"` as a blanket allowance. Once the hub's real IP is known, tighten
this to just that address — see the `TODO` comments in
`app/src/main/AndroidManifest.xml` and
`app/src/main/res/xml/network_security_config.xml`.

## Volume keys

`MainActivity.onKeyDown()` intercepts `KEYCODE_VOLUME_UP`/`_DOWN` and
calls `window.harmonyHardwareVolume('up' | 'down')` in the page via
`evaluateJavascript`. This only fires while the app is in the foreground
— background/locked-screen capture was deliberately not pursued (would
need a foreground service and/or an Accessibility Service permission).

## Lock-screen buttons

While an activity is on, the app keeps a notification with **Volume down**, **Play** and
**Volume up** buttons, public on the lock screen. The hub's page works out which command
each button sends (the same choice the hardware volume keys make) and tells the app; a
button press goes to the hub from a small receiver, so it works with the app closed.

- Android 13+ asks for permission to show notifications the first time something is on.
- Some phones show only icons on the lock screen until you tap them (a system setting);
  one tap then shows the buttons.
- **The buttons work while the phone is locked.** That is the point, but it also means
  anyone holding the locked phone can press them. Stop the activity (or turn off the app's
  notifications) to remove them.
- The notification goes away when the activity is stopped from the app. If it is stopped
  from another phone, it stays until the app is next opened. Swiping it away hides it
  until the app next opens with an activity running.
- It is removed when the hub address is changed.

## Icon

`res/drawable/ic_launcher_*.xml` is a placeholder vector glyph (plain
remote silhouette, hub's accent green), not real design work — swap it
for real art whenever that's worth doing.
