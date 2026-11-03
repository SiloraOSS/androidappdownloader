# App Downloader

An Android app (phones, tablets, **Fire TV** and **Android TV**) for saving links to APKs and
installing them in one tap. It understands three kinds of sources:

| You enter | Example | What happens |
|---|---|---|
| A direct link | `https://example.com/app.apk` | Downloads and installs the APK. If the link is a web page, the page is scanned for an `.apk` link. |
| An **aftv.news** code | `12345` or `aftv.news/12345` | Resolves the AFTVnews short code (the same codes used by the Downloader app) and downloads its target. |
| A **GitHub** repository | `https://github.com/owner/repo` | Finds the newest release with an APK built for your device, installs it, and **keeps checking for new releases**. |

## Features

- Saved list of apps with installed version, latest GitHub release and an **Update** button when a newer release exists.
- Background checks for new GitHub releases (every 3/6/12/24 hours, or manual only) with a notification listing available updates.
- Picks the right release asset for the device's CPU (arm64-v8a, armeabi-v7a, x86, x86_64, universal). An optional per-repo filter (text or regex, e.g. `universal` or `fdroid.*\.apk`) handles repos with several flavours; pre-releases can be included per repo.
- Installs plain `.apk` files and split bundles (`.xapk`, `.apks`) through Android's PackageInstaller.
- Open, uninstall, re-download or edit any saved app; share a link from another app to add it.
- Optional GitHub personal access token (Settings) to raise the API rate limit from 60 to 5,000 requests/hour and to reach private repositories.
- Works with a D-pad remote on Fire TV / Android TV (appears in the TV launcher).

## Install

Download `AppDownloader.apk` from this repository's Releases (or the latest *Android build* workflow
run's artifacts) and sideload it. On first install you will be asked to allow it to install unknown
apps — on Fire TV that is **Settings › My Fire TV › Developer options › Install unknown apps**.

Tip: add `https://github.com/edrock200/androidappdownloader` inside the app and it will keep itself
up to date.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Release signing

Android only accepts an update if it is signed with the same key as the installed copy, so published
builds should use one stable key. Generate one once:

```sh
keytool -genkeypair -v -keystore release.jks -alias appdownloader -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # value for SIGNING_KEYSTORE_BASE64
```

Then add the repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Without them, CI signs release builds with a
throw-away debug key. Pushing a tag such as `v1.0.0` builds the APK and attaches it to a GitHub release.

Locally, set the same variables (with `SIGNING_KEYSTORE_FILE` pointing at the `.jks`) before running
`./gradlew assembleRelease`.

## How it works

- **aftv.news codes** are resolved by loading `https://go.aftvnews.com/<code>` and reading the
  destination from the redirect page.
- **GitHub** monitoring uses the public REST API (`/repos/{owner}/{repo}/releases`). An update is
  reported when the newest matching release tag differs from the one the app installed, or — for apps
  installed some other way — when its version number is higher than the installed `versionName`.
  The installed app is matched by package name, learned from the first APK the app downloads (or set
  manually under *Edit*).
- Entries are stored as JSON in the app's private storage; background checks use WorkManager.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Downloading APKs and checking releases |
| `REQUEST_INSTALL_PACKAGES` | Installing downloaded APKs |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | Android 12+: updating apps it installed without an extra prompt where the system allows it |
| `REQUEST_DELETE_PACKAGES` | The *Uninstall* menu action |
| `QUERY_ALL_PACKAGES` | Reading the installed version of the apps you add |
| `POST_NOTIFICATIONS` | Update notifications |
