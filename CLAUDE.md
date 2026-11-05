# CLAUDE.md

Guidance for Claude Code (and other contributors) working in this repository.

## What this is

**App Downloader** — a single-module Android app (Kotlin, Jetpack Compose, Material 3) that saves
APK sources and installs them. Sources are direct URLs, aftv.news short codes, or GitHub
repositories (whose releases are monitored for updates). Runs on phones, tablets, Fire TV and
Android TV.

## Commands

```sh
./gradlew testDebugUnitTest      # JVM unit tests (fast, no device needed)
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug              # Android lint -> app/build/reports/lint-results-debug.html
./gradlew testDebugUnitTest --tests '*ParsingTest*'   # a single test class
```

Requires JDK 17+ and an Android SDK with platform 35 (`local.properties` → `sdk.dir=...`, git-ignored).
Run `testDebugUnitTest assembleDebug` before every commit; CI runs the same plus `assembleRelease`.

## Layout

All code lives in `app/src/main/java/io/github/edrock200/appdownloader/`:

| Path | Responsibility |
|---|---|
| `App.kt` | `Application`; builds the singletons (`repository`, `settings`, `controller`). Use `context.app()` to reach them. |
| `AppController.kt` | Process-wide coordinator: download → inspect → install pipeline, GitHub checks, per-entry `OpState`, snackbar `messages`. |
| `data/AppEntry.kt` | The persisted model (`@Serializable`). One class for all source types; GitHub-only fields are nullable. |
| `data/EntryRepository.kt` | JSON file in `filesDir`, exposed as `StateFlow`, writes serialized by a `Mutex`. |
| `data/Settings.kt` | SharedPreferences-backed settings as a `StateFlow`. |
| `data/SourceParser.kt` | Detects whether user input is an aftv code, a GitHub repo or a URL. |
| `data/VersionUtils.kt` | Version comparison and the `hasUpdate` rule. |
| `net/Http.kt` | Shared OkHttp client + coroutine `execute`. |
| `net/AftvResolver.kt` | Resolves codes via `https://go.aftvnews.com/<code>` by parsing the redirect page HTML. |
| `net/GitHubClient.kt` | Releases API, repo URL parsing, `pickRelease` (pure). |
| `net/AssetSelector.kt` | Chooses the release asset for the device ABI / user filter (pure). |
| `net/RemoteProbe.kt` | Header-only "has this file changed?" check: `fingerprint(url)` (HEAD, falls back to a 1-byte ranged GET), pure `fromHeaders`/`compare`. |
| `install/ApkDownloader.kt` | Streaming download with progress; scans HTML pages for `.apk` links; validates zip magic. |
| `install/ApkInspector.kt` | Reads package name/version/label; picks splits from `.xapk`/`.apks`; `InstalledApps` lookup. |
| `install/ApkInstaller.kt` | `PackageInstaller` session install; result goes to `InstallResultReceiver`. |
| `work/` | WorkManager jobs: periodic GitHub check (`UpdateCheckWorker`), the user's scheduled "update all" run (`AutoUpdateWorker` + `AutoUpdateScheduler`), notifications. |
| `data/Schedule.kt` | Pure date/time math for scheduled runs (`nextRun`, `atTime`), DST-safe via `Calendar`. |
| `ui/ScheduleDialog.kt` | Date/time/repeat picker built from step buttons (works with a TV remote). |
| `ui/` | `MainActivity`, `MainViewModel`, Compose screens and dialogs, `SystemActions` (intents to the system). |
| `ui/Tv.kt` | `Modifier.focusHighlight()` (remote-friendly focus ring), `Device.isTv/isFireTv`, per-device install-permission help. |
| `ui/SelfUpdateDialog.kt` | Prompt shown when a newer App Downloader release exists. |
| `.github/workflows/android.yml` | CI: tests, builds, signs, publishes tagged releases, announces on Discord. |
| `.github/scripts/` | `release-notes.sh` (CHANGELOG section → plain-language notes), `discord-notify.sh`. |

Unit tests are in `app/src/test/...` and cover the pure logic only.

## How the main flows work

- **Install:** `AppController.downloadAndInstall(id)` resolves the URL (aftv / GitHub check), downloads
  to `cacheDir/apks/<entryId>.<ext>`, inspects it (stores `packageName`, replaces an auto-generated
  name with the app label), then commits a `PackageInstaller` session. The session result arrives in
  `InstallResultReceiver`, which launches the system confirm screen (`STATUS_PENDING_USER_ACTION`) or
  calls `controller.onInstallSucceeded/onInstallFailed`. Data needed after install (entry id, release
  tag, version code, file path) travels in the PendingIntent extras so it survives process death.
- **Update detection:** `VersionUtils.hasUpdate`. If the installed version code equals the one we
  installed, compare release tags; otherwise compare the tag's numbers with the installed
  `versionName`. Not installed → "Install", never "Update".
- **Background checks:** `UpdateCheckWorker` → `checkAll()` → notify for GitHub entries whose
  `notifiedTag != latestTag` and links whose `notifiedChangeKey != remoteFingerprint.key`.
- **Scheduled "update all":** the schedule (`scheduleAt`, `scheduleRepeat`) lives in `Settings` and is
  saved immediately by `MainViewModel.setSchedule`, which (re)enqueues a one-time WorkManager job.
  `AutoUpdateWorker` calls `controller.updateSelected(interactive = false)`, then computes the next run
  with `Schedule.nextRun` and enqueues it (`APPEND_OR_REPLACE` so it doesn't cancel itself).
  `updateSelected` handles installed entries with `autoUpdate`: `prepareUpdate` checks GitHub (or, for
  links, downloads and compares versionCode), and stores a newer APK as `readyFile`/`readyVersion*`/`readyTag`.
  Background runs commit sessions with `EXTRA_BACKGROUND`; on Android 12+ they can complete silently,
  otherwise `InstallResultReceiver` abandons the session on `STATUS_PENDING_USER_ACTION` and the update
  stays "waiting" (notification + prompt on next launch). Below Android 12 nothing is committed in
  the background. Interactive runs ("Update all now", "Install now") install one app at a time,
  waiting for each result (`awaitInstallResult`). `runInstall` always prefers a waiting `readyFile`;
  stale ones (older than installed, or not the latest GitHub tag) are discarded.
- **Link change detection:** each download records a `RemoteFingerprint` (ETag, Last-Modified, size,
  host+path without query) as `pendingFingerprint`; `onInstallSucceeded` promotes it to `fingerprint`.
  `checkLink` probes the server and stores `remoteFingerprint`/`remoteChange` (CHANGED / UNCHANGED /
  UNKNOWN). `RemoteProbe.compare` trusts ETag/date; size/address alone can only prove a change. With
  the `updateOnlyIfChanged` setting, `prepareUpdate` skips links only when the result is UNCHANGED;
  UNKNOWN still downloads and compares versionCode (and then records the download as the new baseline).
  `checkAll` covers GitHub entries and installed links; `VersionUtils.updateAvailable` drives the card.
- **Self-update:** `MainActivity.onCreate` (fresh start only) → `controller.checkSelfUpdateOnLaunch()`
  (skipped when the *checkSelfUpdates* setting is off) looks up `BuildConfig.UPDATE_REPO`'s releases
  for an `AppDownloader*.apk` asset and compares the tag with `BuildConfig.VERSION_NAME`. A newer one
  sets `controller.selfUpdate`, shown by `SelfUpdateDialog`; installing it reuses the download/install
  pipeline under the op id `AppController.SELF_UPDATE_ID` and refuses APKs with another package name.
- **Releases:** running the workflow manually with input `version: X.Y.Z` (or pushing tag `vX.Y.Z`)
  sets `APP_VERSION_NAME` (versionCode =
  X*10000 + Y*100 + Z, computed in `app/build.gradle.kts`), signs with the `SIGNING_*` secrets,
  publishes the release with the fixed asset name `AppDownloader.apk`, and posts to the
  `DISCORD_WEBHOOK` secret (aftv.news code `9697744`, overridable with the `AFTV_CODE` repository variable). Stable download link:
  `https://github.com/edrock200/androidappdownloader/releases/latest/download/AppDownloader.apk`.

## Conventions and gotchas

- **minSdk is 22** (old Fire TV devices). Guard newer APIs with `Build.VERSION.SDK_INT` checks
  (e.g. `canRequestPackageInstalls` is 26+, `FLAG_MUTABLE` / `setRequireUserAction` are 31+).
  Lint flags misses — keep lint free of errors.
- **Keep logic testable on the JVM.** Unit tests run against stubbed `android.jar`, so parsing,
  selection and version logic must not touch Android APIs. Put such logic in pure functions
  (see `GitHubClient.pickRelease`, `ApkInspector.selectSplits` with an explicit `supportedAbis`).
- **Unit tests must not hit the network.** Use recorded fixtures (see `GitHubReleaseTest`).
- **TV / D-pad:** every action must be reachable without touch — use standard focusable Material
  components (buttons, menus), no gesture-only or long-press-only actions. Give every clickable
  control `Modifier.focusHighlight(...)` (pass `FocusShapes.Row, scaleUp = false` for rows and menu
  items). Make label+switch/checkbox rows a single `toggleable` row. Hide browser-only actions when
  `SystemActions.hasBrowser()` is false, and use `Device.installPermissionHelp()` for permission text.
- **Adding a field to `AppEntry`:** give it a default value so existing `entries.json` files still
  load (`ignoreUnknownKeys` covers removed fields, defaults cover new ones).
- **Persistence changes go through `EntryRepository.update/add/delete`**, never by mutating the flow.
- UI state comes from `MainViewModel.items` (`EntryUi`); call `refreshInstalled()` when installed
  apps may have changed (done in `onResume`).
- User-facing errors are short sentences shown on the entry card (`OpState.Failed`) or as a
  snackbar via `controller.message(...)`; keep them actionable ("Add a token in Settings").
- Dependencies are declared in `gradle/libs.versions.toml`; avoid adding libraries for things the
  platform/OkHttp/kotlinx.serialization already cover. Avoid `material-icons-extended` (APK size).
- Don't commit keystores or `local.properties`. Release signing is configured via `SIGNING_*`
  environment variables (see README). The only committed APKs are the signed release builds in
  `releases/`; never put a debug-signed APK there, because a different key breaks in-place updates.
- Record user-visible changes in `CHANGELOG.md` under *Unreleased*, written in plain language for
  non-technical users — the section becomes the release notes and the Discord announcement. When
  releasing, rename it to `## [X.Y.Z]` and tag `vX.Y.Z`. Don't edit versionCode/versionName by hand
  (they come from the tag); only update the local-build default in `app/build.gradle.kts`.
- Never rename the release asset `AppDownloader.apk`: the "latest" download link, the aftv.news code
  and in-app self-update all depend on it.

## Not covered by tests

No instrumentation/UI tests exist; the install flow, notifications and Compose UI need manual
testing on a device or emulator (ideally one phone and one Fire TV / Android TV).
