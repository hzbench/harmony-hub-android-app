# Hub Remote (Android WebView wrapper)

Minimal Android app that hosts the `harmony-hub-control` web UI (loaded
live from the hub, unmodified) and adds exactly one native capability:
foreground hardware volume-key capture. Full design rationale is in the
main `PLAN.md`, under "Spec: minimal WebView wrapper with volume-key
control".

**Status: builds and runs.** It has been built with Gradle and run on a
real phone (Pixel 6 Pro) against the hub's web UI, including the
volume-key hook, the `confirm()`/`alert()` dialogs, keep-screen-on and the
"Change hub address" action.

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
run it on a device over USB. This build installs as `io.github.mpdeglau.hubremote.debug`
("Hub Remote (debug)"), so for adb use that id:
`adb shell am start -n io.github.mpdeglau.hubremote.debug/com.harmonyhub.remote.MainActivity`
and `adb uninstall io.github.mpdeglau.hubremote.debug`. For the APK you give to friends
see *Release APK* below; don't use Android Studio's Generate Signed APK, which would sign
with a different key than the one every update must be signed with. This app isn't going
through the Play Store.

## Release APK (for you and friends)

The app's id is `io.github.mpdeglau.hubremote` (the code's package is still
`com.harmonyhub.remote`). The id is the app's identity on a phone, so don't change it
once friends have it. Debug builds use `...hubremote.debug` and are called "Hub Remote
(debug)", so both can be installed together.

1. **Signing key.** It lives outside the repo, in `~/.hub-remote-signing/`
   (`hub-remote-release.jks` and `signing.properties`, owner-only). **Back both up
   somewhere safe.** Android only installs an update over an existing copy when it is
   signed with the same key, so if the key is lost every friend has to uninstall and
   reinstall (losing their saved hub address). To use a different place, set
   `HUBREMOTE_SIGNING` to the path of a properties file with `storeFile`, `storePassword`,
   `keyAlias` and `keyPassword`. Without any of this, `assembleRelease` makes an
   *unsigned* APK, which is how CI builds.
2. **Version.** There is one source of truth: the `VERSION` file (see *Releases on GitHub*
   below), which sets `versionName` 1.2.3 and `versionCode` 10203 (major×10000 +
   minor×100 + patch). The defaults in `app/build.gradle.kts` (code 1) are for local builds
   only and can't update anyone, so never hand out a build made with them.
3. **Build by hand** (normally the release workflow does this): `./gradlew assembleRelease
   -PversionName=1.2.3 -PversionCode=10203`, then copy
   `app/build/outputs/apk/release/app-release.apk` to `dist/hub-remote-<version>.apk`
   (`dist/` is not committed). If `signing.properties` exists but is incomplete, or its
   keystore is missing, the release build stops with a message saying so (debug builds
   only warn); with no file at all it makes an unsigned APK.
4. **Check.** `apksigner verify --print-certs app-release.apk` and
   `aapt dump badging app-release.apk` (package, version, label).
5. **Install.** Send the APK to a friend; on their phone they allow "Install unknown
   apps" for whatever app they opened it from. It needs Android 8 or newer.

### Releases on GitHub

`.github/workflows/release.yml` builds and publishes the signed APK for you.

- **Publish:** change the `VERSION` file (for example `0.3.0`) in the pull request that should
  ship, and merge it. When a push to main changes `VERSION` and the *Build* workflow has passed
  on that commit (the release waits up to 15 minutes for it), the workflow builds, checks the
  APK, tags the commit `v0.3.0` and creates a GitHub Release named "Hub Remote 0.3.0" with
  `hub-remote-0.3.0.apk` and its `.sha256` attached, notes generated from the merged pull
  requests. Merging without touching `VERSION` releases nothing; a `VERSION` that already has a
  tag does nothing (a notice in the run says so); a `VERSION` lower than the newest release
  fails, since versions only go up. `versionName` is the version and `versionCode` is
  major×10000 + minor×100 + patch (`20000` would be 2.0.0), so it always rises with it. Minor and
  patch must be 99 or less. Nothing is released from any other branch, and tags pushed by hand
  do nothing.
- **Retry:** if a release run fails, fix the cause and re-run the failed run (Actions > the run >
  Re-run jobs) for a problem outside the repository, or use Actions > Release > Run workflow on
  main with *publish* ticked once the fix is merged.
- **Try the pipeline without publishing:** Actions > Release > Run workflow (it only runs
  from `main`, with *publish* off) builds a signed APK (version code 1, so it can only be installed fresh) and
  keeps it as a download for a week.
- **Where the key is:** in the repository's Actions secrets `HUBREMOTE_KEYSTORE_BASE64`,
  `HUBREMOTE_STORE_PASSWORD`, `HUBREMOTE_KEY_ALIAS` and `HUBREMOTE_KEY_PASSWORD`, plus the
  variable `HUBREMOTE_CERT_SHA256` (the certificate's SHA-256 fingerprint, as plain hex or
  with colons, any case, e.g. from `keytool -list -v` or `apksigner verify --print-certs`).
  Each secret is handed only to the step that needs it, after the tests have run; the
  workflow writes the key to the runner's temporary folder, builds, and deletes it. **A secret can't be read
  back out of GitHub, so keep the copy in `~/.hub-remote-signing/` backed up.** If the key
  ever changes, update all four secrets and the variable together; the build refuses to
  produce an APK signed with a key whose fingerprint doesn't match.
- Only people with write access to this repository can run the workflow or read the
  secrets, and the secrets are not available to pull-request builds from forks. Anyone who
  can change workflows in this repository could use the key, so keep it private.
- **Downloading:** the release page of this (private) repository. Friends need access to the
  repository to see it.

This is a sideloaded build, not a Play Store one: the Play Store would need the app
bundle (`bundleRelease`), a developer account and a newer target SDK (API 36).

## Which hub software it needs

The app loads the hub's own `/go/` page, so most features arrive with hub software, not with
an app update. The app needs a hub release that serves `/go/`; **Setup > Software update**
(choosing the update file) needs app 0.2.0 or newer and a hub release that has
`GET /api/v1/updates/status`. Hub releases are made in the `harmony-hub-control` repository.

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
in the app under Setup → Hub address (or with "Change hub address" on the error screen). The toolbar shows the hub's name and a connection icon; it is hidden on remotes, where the page's own header carries the icon. There's no discovery
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

## File picker

The hub's page can ask for a file (Setup > Software update picks a `hub-release-*.json`).
`MainActivity` answers with Android's system file picker, set to show every file because
many pickers can't match `.json` by type; the page checks what it was given. Cancelling the
picker leaves nothing hanging; a second request while one picker is open is answered "no file".

## Icon

`res/drawable/ic_launcher_*.xml` is a placeholder vector glyph (plain
remote silhouette, hub's accent green), not real design work — swap it
for real art whenever that's worth doing.
