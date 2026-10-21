# Harmony Remote (Android WebView wrapper)

Minimal Android app that hosts the `harmony-hub-control` web UI (loaded
live from the hub, unmodified) and adds exactly one native capability:
foreground hardware volume-key capture. Full design rationale is in the
main `PLAN.md`, under "Spec: minimal WebView wrapper with volume-key
control".

**Status: scaffolded, not yet built or run.** This was written on a
machine with no Android SDK/JDK/Gradle installed, so nothing here has
been compiled or verified — treat it as a starting point to open in
Android Studio, not a tested artifact.

## Prerequisites

- Android Studio (recommended — handles the Gradle wrapper, SDK, and JDK
  for you), or: JDK 17 + Android SDK command-line tools + Gradle 8.7.
- The companion `app.js` hook (`window.harmonyHardwareVolume`) must exist
  in the `harmony-hub-control` build this points at — see
  `../control/payload/www/app.js`, added as part of this same change.

## Opening the project

1. Open this `android-app/` folder in Android Studio.
2. Let it sync — it will generate the Gradle wrapper jar/scripts (not
   committed here) and may prompt to upgrade AGP/Kotlin/Gradle from the
   versions pinned in `build.gradle.kts`/`gradle/wrapper/`; accepting
   that is fine.
3. Build and run on a device over USB, or build a release APK
   (Build > Generate Signed Bundle / APK) and sideload it — this app
   isn't going through the Play Store.

## First run

On first launch it asks for the hub's address (`<ip>:8080` or a full
`http://...` URL) and stores it in `SharedPreferences`. Change it later
via the overflow menu → "Change hub address". There's no discovery
mechanism by design — set a DHCP reservation for the hub's MAC in your
router so the address stays stable (see `PLAN.md`).

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

## Icon

`res/drawable/ic_launcher_*.xml` is a placeholder vector glyph (plain
remote silhouette, hub's accent green), not real design work — swap it
for real art whenever that's worth doing.
