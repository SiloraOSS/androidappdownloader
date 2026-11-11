# Automatic install playback protection

Automatic replacement of `org.silora.app` queries its exported Media3 service directly:
`org.siloserver.silo.common.player.SiloPlaybackService`. This uses the existing package visibility
declaration, not notification-listener access or shell privileges. The player needs no changes.

The query happens after the APK is prepared and installer readiness has been checked, just
before installation. Both background workers and both installer methods use the same gate.
An explicit manual install bypasses it; replacing the updater itself also bypasses it.

`playWhenReady` with `STATE_READY` or `STATE_BUFFERING` defers replacement, including buffering
or playback temporarily suppressed by audio focus. Paused, idle and ended states allow it.
Failure to connect, a disconnect or a five-second timeout defers rather than assuming idle.
The temporary controller is released on success, error, timeout and worker cancellation.

Deferral keeps the staged APK and reports it as waiting, without creating an install session.
The worker completes normally, so the next saved schedule attempts installation again; there
is no extra polling or immediate playback-ended installation. With interval checks that means
the next interval; daily checks keep their chosen hour. A one-off scheduled update has no next
occurrence unless normal background checks are also enabled or the user installs manually.

## Validation

`./build.sh --device-check` runs release unit tests/lint and creates signed production and
instrumentation APKs. The playback-only device check reads the production Media3 connector,
without downloading or installing Silora or changing settings:

```sh
adb -s DEVICE install -r dist/SiloraUpdater.apk
adb -s DEVICE install -r dist/SiloraUpdater-device-check.apk
adb -s DEVICE shell am instrument -w -e playbackOnly true \
  org.silora.updater.test/io.github.edrock200.appdownloader.ShizukuDeviceCheck
```

Repeat with Silora on its home screen, actively playing, paused, buffering and after playback
ends. Expect `IDLE`, `PLAYING`, `IDLE`, `PLAYING`, `IDLE` respectively. Also check a cold player
and screen-off/background playback. A missing/incompatible service should report `UNKNOWN`.
Use the dedicated test account for authenticated playback.

The gate is a last-moment observation, not an atomic agreement with the player: playback could
start between the reading and the package-manager commit. Test on target TVs before claiming
device reliability. Casting via a different media service and external players are not covered
by this local Silora player session.

## SHIELD cold-start results — 2026-11-11

Built with `./build.sh --device-check` and installed the signed release on the connected
NVIDIA SHIELD (Android 11). The installed player was Silora `1.2.0 (31) (silora:11102026)`.
Release unit tests (148) and lint passed. The production APK SHA-256 was
`319b82ec085904369dd3a170f2f83fb7b38fba921ca22a6379bdd381c2804b8f`.

Before each of three queries, force-stopped `org.silora.app` and confirmed `pidof` returned
no process. All three production Media3 queries returned `IDLE`, with the automatic-install
policy returning `true`, in 756 ms, 513 ms and 460 ms. The latter two checks verified the SHIELD
was asleep (`mWakefulness=Asleep`) and that the foreground window did not change. Connecting
started the player process without starting playback or opening the app window.

The cold-start test therefore passed with the original conservative fallback: an unreadable
session or timeout still defers installation. No change to allow installs on connection failure
was necessary. The default interval remains 12 hours; the earlier daily-3-a.m. change is stashed.

The playback-only instrumentation queried the real production connector and install policy;
it did not download or reinstall Silora. Active/paused/buffering decisions and retained APKs
were covered by host tests, but real-device active-playback deferral and an actual package
replacement were not exercised in this cold-start check. Sleep is not a forced deep-Doze test.
Raw query output is retained locally under ignored `dist/playback-device-test/`.

## Google TV Streamer / Standard installer — 2026-11-11

Installed the same verified production APK on the Google TV Streamer running Android 14
(API 34). The saved installer was `STANDARD`, install access was granted, and Android listed
`org.silora.updater` as Silora's installer. No Shizuku initialization or authorization was used.
The signed device-check APK was rebuilt with an opt-in Standard reinstall mode; release host
tests (148) and lint passed. The production APK hash remained the one recorded above.

Force-stopped Silora and confirmed no process before each query. The awake query returned
`IDLE` and allowed automatic installation in 745 ms. The screen-off query returned `IDLE`
and allowed installation in 480 ms, with `mWakefulness=Asleep` and unchanged foreground window.
Both reported that the Standard background installation policy was eligible.

Then force-stopped Silora again and ran a real Standard background installation while the
Streamer was asleep. The opt-in check copied the exact installed single APK to the updater's
cache, verified its package/version, checked the real media session and shared playback policy,
and passed it to the production `ApkInstaller` with `background = true`. Android installed it
without confirmation, and the production result receiver recorded the successful installation
in history. The foreground window stayed unchanged. Silora remained version code `854241005`,
`1.2.0 (31) (silora:11102026)`, with its updater installer attribution retained and a new
`lastUpdateTime`. No uninstall or app-data clear was performed.

To repeat that opt-in same-version reinstall test (after installing both signed APKs above):

```sh
adb -s DEVICE shell am force-stop org.silora.app
adb -s DEVICE shell am instrument -w -e installer standard -e reinstallCurrent true \
  org.silora.updater.test/io.github.edrock200.appdownloader.ShizukuDeviceCheck
```

The test requires Standard already selected, Android 12+ and install permission. It refuses
to run over a pending Silora install/restore and checks playback immediately before installing.
Like the existing opt-in Shizuku reinstall test, it bypasses only the controller's newer-version
pre-check to exercise the production installer using the same installed APK; it does not test
GitHub discovery or change the normal no-downgrade/no-reinstall rules.

The temporary instrumentation package was removed and the Streamer's original awake state was
restored. The signed production updater remains installed. Connection failure/timeout still
defers; no permissive fallback was needed. Actual playing/paused/buffering behavior remains a
separate device test. Raw output is retained under ignored
`dist/playback-device-test/google-tv-streamer/`.
