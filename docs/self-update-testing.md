# Self-update and monitoring recovery

Self-updating replaces the running updater process. The app window does not need to reopen:
Android's `MY_PACKAGE_REPLACED` receiver starts the new package, initializes WorkManager and
reapplies the saved check schedule. WorkManager recovers interrupted work from its database.
Manual-only checking remains disabled. Android may delay scheduled work for power/network reasons.

## Automated release checks

`./build.sh --device-check` runs release unit tests and lint, verifies release signing and builds
an opt-in instrumentation APK. Tests cover fixed GitHub asset selection, stable-only releases,
version-code ordering, durable offline pending updates, missing callbacks after replacement,
confirmation/cancellation, manifest receiver registration, and schedule recreation from saved settings.

## Real-device upgrade check

Use a test TV with Shizuku running and already authorized for the updater. This check installs
an actual newer **signed release** through the production controller and Shizuku installer;
Android enforces package/signature compatibility. It does not uninstall Silora or clear either
app's data. The test selects Shizuku in Settings. Use two local versions, with a higher version
for the second build, and keep the older app and its device-check APK before building the newer one.
Do not publish throwaway test versions to GitHub.

Example (substitute versions above the TV's current version as needed):

```sh
./build.sh --version 1.0.3 --device-check
mkdir -p /tmp/updater-check
cp dist/SiloraUpdater.apk /tmp/updater-check/older.apk
cp dist/SiloraUpdater-device-check.apk /tmp/updater-check/check.apk
./build.sh --version 1.0.4 --device-check
adb -s DEVICE install -r /tmp/updater-check/older.apk
adb -s DEVICE install -r /tmp/updater-check/check.apk
adb -s DEVICE push dist/SiloraUpdater.apk /data/local/tmp/SiloraUpdater-next.apk
# Capture this in another terminal BEFORE the self-install. Do not reopen the app afterwards.
adb -s DEVICE logcat -v threadtime -T 1 -s SiloraUpdater:I '*:S'
adb -s DEVICE shell am instrument -w -e selfUpdateApk /data/local/tmp/SiloraUpdater-next.apk \
  org.silora.updater.test/io.github.edrock200.appdownloader.ShizukuDeviceCheck
```

The instrumentation process should die as Android installs the newer updater. **That alone is
not a pass.** Without launching the app, verify all of:

1. `adb -s DEVICE shell dumpsys package org.silora.updater` reports the new version.
2. Logcat reports `Restored update checks after package replacement` from the new process.
3. `adb -s DEVICE shell dumpsys jobscheduler` contains the updater's
   `androidx.work.impl.background.systemjob.SystemJobService` job.
4. Verify the resumed activity is still the launcher (or the activity that preceded the test),
   rather than an automatically launched updater window. Capture the receiver and schedule
   evidence now, before running any further instrumentation.
5. To test execution immediately, use the helper below to advance **one occurrence** of the existing
   periodic work. ADB's JobScheduler `-f` flag alone does not bypass WorkManager's own next-run guard.
   The helper preserves saved preferences; a custom daily worker restores its next normal hour.
   Wake a sleeping TV before forcing execution, then wait for `Background update check completed`
   and WorkManager's `SUCCESS` result. This does not prove execution at an exact clock time.

After observing recovery, install the **newer build's** device-check APK (the test code uses
that build's version constants), advance the existing schedule once, and verify its saved results:

```sh
adb -s DEVICE install -r dist/SiloraUpdater-device-check.apk
adb -s DEVICE shell am instrument -w -e runMonitoringNow true \
  org.silora.updater.test/io.github.edrock200.appdownloader.ShizukuDeviceCheck
# Inspect jobscheduler again for the current JOB_ID, then run it if necessary:
adb -s DEVICE shell cmd jobscheduler run -f org.silora.updater JOB_ID
# Wait for worker completion, then:
adb -s DEVICE shell am instrument -w -e verifySelfRecovery true \
  org.silora.updater.test/io.github.edrock200.appdownloader.ShizukuDeviceCheck
```

The verifier requires successful post-upgrade GitHub checks for **both** packages, removal of the
pending self-install, the expected new version, and exactly one active periodic check. Capture
receiver/worker logs before this verifier; instrumentation itself starts a new target process.

A failed test reports `FAIL` instead of the expected process death. Inspect `ShizukuInstall`,
`SiloraUpdater`, and `WM-WorkerWrapper` logs. The local staged APK avoids publishing a release;
release discovery/filtering are covered separately by the JVM tests and the background GitHub check.

## Device evidence — November 9, 2026

Verified on the connected NVIDIA SHIELD (`192.168.50.154`, Android 11), using release-signed
local versions **1.0.3 → 1.0.4** through the production controller and Shizuku installer:

- Android terminated the old updater during `installPackageLI`; package inspection then reported
  `versionCode=10004`, `versionName=1.0.4`.
- The new process logged `Restored update checks after package replacement`. WorkManager recovered
  the original periodic work ID `e3e172cd-0438-449a-bf18-8257206be22f` and scheduled its OS job.
- The launcher (`com.spocky.projengmenu`) remained the resumed activity. No updater activity was launched.
- After waking the sleeping TV and advancing one scheduled occurrence using the test helper,
  `UpdateCheckWorker` logged `Background update check completed` and WorkManager reported `SUCCESS`
  for that same persistent work ID.
- The post-upgrade verifier returned **PASS**: the new version was installed, the pending self-update
  was cleared, both player and updater had successful GitHub checks after the upgrade began, and
  exactly one periodic monitoring job remained active.
- The custom daily schedule was restored after the accelerated test occurrence; the next OS job
  again had its normal future timing delay. Saved scheduling preferences were not changed.
- 126 release unit tests passed; release lint reported zero errors. APK certificate, package and
  non-debuggable release checks passed. The signed 1.0.4 APK SHA-256 is
  `b207640ab4bb427bd8463c15518c82c6b37e14e217c4565fcdef4769011aabfe`.

This verifies replacement recovery and background execution on this SHIELD with Shizuku.
It does not claim a full-interval wall-clock test, modern Android's Standard installer device
coverage, or downloading an unpublished newer release from GitHub. The newer test APK was staged
locally; release discovery and the post-upgrade GitHub checks were verified separately.
