# Shizuku verification

Build with the established release key:

```sh
./build.sh --version 1.0.2 --device-check
```

This runs release unit tests and lint, builds the production APK, verifies its application ID,
certificate and non-debuggable status, and also produces a test APK with the same certificate.
The device check lives only in the separate test APK; it is absent from the production manifest.

## Opt-in device check

Use an idle device with Shizuku running, Silora already installed, and Silora Updater authorized
in Shizuku. The check streams and reinstalls the exact single APK already installed on that device.
It preserves Silora's data and version, but Android may stop Silora while replacing the APK.
The updater's normal UI continues to reject same-version installs; only this explicit test bypasses
that pre-check to exercise the installer without needing a newer public release.

```sh
adb -s DEVICE install -r dist/SiloraUpdater.apk
adb -s DEVICE install -r dist/SiloraUpdater-device-check.apk
adb -s DEVICE shell am instrument -w -e reinstallCurrent true org.silora.updater.test/io.github.edrock200.appdownloader.ShizukuDeviceCheck
```

Require `shizuku=READY`, `result=PASS`, and instrumentation code `-1`. This verifies UserService
binding under shell/root, APK descriptor transport, session creation/write/commit, the production
result receiver, installed version, and recorded history. No account login or playback occurs.
Only the updater's temporary copy is eligible for cleanup; installed APK files are never passed
as a deletion target.

Afterward, remove the test APK (this leaves the production updater and Silora installed):

```sh
adb -s DEVICE uninstall org.silora.updater.test
```

## Manual checks

- Settings → App installation method: Standard is the default; Shizuku persists after reopening.
- Select Shizuku and authorize it; status changes to Ready. Denial offers a recovery action.
- Shizuku installation works with the updater's unknown-apps permission disabled.
- With Shizuku stopped or unavailable, a downloaded update remains ready and no background
  permission screen opens. Restart Shizuku and retry the same downloaded file.
- An automatic update on Android 11 uses Shizuku when selected and ready. Standard continues
  to leave the download waiting on that Android version.
- Navigate installation method, authorization, setup help/QR, and Back to settings with a TV remote.
- On Android 12+, Standard continues to use Android's normal confirmation/silent-update rules.

Unit tests cover installer policy, persistence, missing/stopped service states, the unsupported-SDK
guard, callback metadata, split APK streaming, and session abandonment on failed writes/commits.
Robolectric 4.16 does not run a real API 22 runtime; its guard test overrides the reported SDK in
an API 23 runtime. The merged production manifest retains minSdk 22 and disables the Shizuku
provider with a version-qualified resource below API 23.

## Local validation — 2026-11-08

- Signed release 1.0.2: 86 unit tests passed; release lint completed with zero errors.
- Production certificate, app ID, and non-debuggable checks passed. minSdk remains 22.
- Release and matching test APKs installed on the connected Android 11 Shield.
- Device check reached Shizuku's `PERMISSION_REQUIRED` state. Matt chose to finish without
  the device test on 2026-11-08. Full privileged installation, completion delivery, and TV
  remote navigation remain unverified; this is not an outstanding authorization request.
- Removed the temporary `org.silora.updater.test` app and revoked the legacy Android runtime
  permission granted solely for test setup. The production updater and Silora remain installed.
  The documented opt-in check can be run later if requested.
