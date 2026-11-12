# Automatic install playback protection

With **Wait until playback stops** enabled (the default), automatic replacement of `org.silora.app`
queries its exported Media3 service directly:
`org.siloserver.silo.common.player.SiloPlaybackService`. This uses the existing package visibility
declaration, not notification-listener access or shell privileges. The player needs no changes.

The query happens after the APK is prepared and installer readiness has been checked, just
before installation. Both background workers and both installer methods use the same gate.
An explicit manual install bypasses it; replacing the updater itself also bypasses it.
Turning the setting off skips the query, including the conservative connection-failure fallback.
The device-local choice persists across restarts and disabling/re-enabling automatic installation;
the control is disabled while automatic installation is off.

`playWhenReady` with `STATE_READY` or `STATE_BUFFERING` defers replacement, including buffering
or playback temporarily suppressed by audio focus. Paused, idle and ended states allow it.
Failure to connect, a disconnect or a five-second timeout defers rather than assuming idle.
The temporary controller is released on success, error, timeout and worker cancellation.

Deferral keeps the staged APK and reports it as waiting, without creating an install session.
The reason is saved with the staged APK so the dashboard can distinguish active playback from
an unreadable session and ordinary installer/confirmation waits. The reason clears when an install
proceeds or the staged APK is removed/replaced. Playback messages are hidden while playback
protection or automatic installation is off.
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

## Google TV Streamer / live playback detection — 2026-11-11

Installed the signed playback-only instrumentation helper and queried the production Media3
connector while the user operated Silora on the Streamer. Silora was version code `854241005`,
`1.2.0 (31) (silora:11102026)`, with the Standard installer selected.

| User-confirmed state | Connector result | Automatic install allowed | Query duration |
| --- | --- | --- | --- |
| Playing | `PLAYING` | No | 73 ms |
| Paused | `IDLE` | Yes | 66 ms |
| Resumed playing | `PLAYING` | No | 69 ms |
| Stopped / browsing | `IDLE` | Yes | 42 ms |

All four queries passed and exercised the shared automatic-install policy using the real
connector reading. Only `-e playbackOnly true` was used: no download or package installation
was requested by these checks. Silora's `lastUpdateTime` remained `2026-11-11 13:56:30`.
Raw output is retained under ignored
`dist/playback-device-test/google-tv-streamer/live-playback/`.

These results verify real playing/paused/resumed/stopped detection. The user deferred real-device
buffering validation for a later session. A real buffering state,
a scheduled download of a newer release, and a full deferred-install/retry cycle on the device
have not been exercised by these playback-only checks.

## Configurable playback protection — 2026-11-11

`./build.sh --device-check` passed all 155 release host tests and lint, built both signed APKs,
and verified the production signing certificate. Host coverage includes the default-on/saved-off
preference, bypassing the playback query when protection is off, retaining the staged APK and
persisting the exact deferral reason for both installer methods, clearing the reason on an idle
retry/manual install/stale download, and dashboard messaging on wide TV and stacked layouts.
Unavailable playback is displayed separately from active playback; disabling protection or
automatic installation hides the playback-specific waiting message.

Installed the production APK on the Streamer with `adb install -r`. Its SHA-256 was
`94127361375fe3198c913721984448df6039d23ac5031d1036b110f00f659bb9`.
Real-device setting checks and the updated playback-only helper are recorded under ignored
`dist/playback-device-test/google-tv-streamer/settings/`. These configuration checks do not
exercise an actual newer-version Silora installation or a real buffering state.

Using D-pad navigation, confirmed the switch started on, turned it off, force-stopped the
updater, and verified the new process still read `waitUntilPlaybackStops=false`. Reopened
Settings and confirmed the switch remained off, then restored it to on. Disabling automatic
installation greyed out the playback switch while keeping it checked; re-enabling automatic
installation made it usable again. A final playback-only query read the saved value as true.
Both settings were left on and the existing daily 3:00 AM schedule was preserved. The temporary
test package was removed and the updater dashboard reopened. Silora remained on the same build
with `lastUpdateTime=2026-11-11 13:56:30`; these checks did not reinstall Silora.

## Full automatic-update cycle device check

The signed helper also supports a real production `UpdateCheckWorker` invocation using a separate
one-time WorkManager request. This exercises GitHub discovery, download, the playback gate and
the selected background installer without changing saved preferences or advancing the recurring
work's minimum interval. For daily checks, the worker reapplies the normal saved check hour.
This accelerates execution for testing; it does not verify delivery at the exact scheduled hour.

Only run this with an actual newer Silora release available and the Standard installer selected.
Automatic installation and Wait until playback stops must both be enabled. The helper verifies
the expected playback state before enqueueing any work. Keep the video playing until the first
check completes:

```sh
adb -s DEVICE shell am instrument -w -e automaticUpdateCheck true -e expectedPlayback PLAYING \
  org.silora.updater.test/io.github.edrock200.appdownloader.ShizukuDeviceCheck
```

The first check requires the installed version to stay unchanged, the downloaded APK to remain,
the saved reason to be active playback, and no Silora install session to exist. Then stop playback
and retry through the same production worker:

```sh
adb -s DEVICE shell am instrument -w -e automaticUpdateCheck true -e expectedPlayback IDLE \
  org.silora.updater.test/io.github.edrock200.appdownloader.ShizukuDeviceCheck
```

The retry requires the previously deferred APK, a successful newer-version install, an automatic
history record, cleared waiting state, and unchanged preferences. The helper reports the APK's
SHA-256 before retry and after installation for comparison with the published release digest.
Neither mode uninstalls Silora or clears its data. Unfinished test work is cancelled on timeout.

## Google TV Streamer / full automatic-update cycle — 2026-11-11

Passed using the Standard Android installer, with automatic installation and playback protection
enabled. The real production worker upgraded Silora from `854241005`,
`1.2.0 (31) (silora:11102026)`, to `854422891`, `1.2.0 (31) (silora:11112026)`, from release
`build-2026-11-11.144319.43114054189`.

While the user kept a video playing, a one-time production `UpdateCheckWorker` discovered and
downloaded the newer TV APK. WorkManager reported success, but Silora's installed version and
installation time remained unchanged. The APK stayed ready with `playbackDeferral=ACTIVE`,
and no Silora install session was created. Its SHA-256 matched the published TV asset:
`07b47b649222242e238c8879669bd79ab5dbd137dc511a2e0c9d93793b245685`.

After the user stopped playback and returned to browsing, a second one-time request ran the
same production worker. It installed the newer APK automatically without a confirmation action,
uninstall, app-data clear or Shizuku. The retained APK hash before retry and installed APK hash
both matched the first download and the published digest. Android reported installation at
`2026-11-11 23:25:06` Pacific and retained `org.silora.updater` as the installer. The result receiver
recorded an automatic history entry for `854422891`; the ready file and playback deferral cleared.
Both workers logged completion and WorkManager `SUCCESS`.

Saved preferences remained unchanged, including daily checks at 3:00 AM, automatic installation
on and playback protection on. The same single periodic work ID
`b165fb68-1168-4c41-92ba-9e7de3b19800` and next scheduled time remained in place. The updater
dashboard then showed the new installed/latest version, You're up to date, and
`Last Updated: Nov 11, 2026 at 11:25 PM`. The temporary instrumentation package was removed.

Raw worker output, release metadata, checksum/state comparisons, filtered logs and the final
dashboard screenshot are retained under ignored
`dist/playback-device-test/google-tv-streamer/automatic-cycle/`. These were accelerated production
background checks, not an exact 3:00 AM scheduling test. Real buffering validation remains deferred.
