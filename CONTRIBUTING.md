# Contributing

Thanks for helping improve Silora Updater!

## Getting set up

1. Install JDK 17+ and the Android SDK (platform 35). Android Studio provides both.
2. Clone the repo and open it in Android Studio, or create `local.properties` with
   `sdk.dir=/path/to/android-sdk` to build from the command line.
3. Build and test:

   ```sh
   ./gradlew testDebugUnitTest assembleDebug
   ```

See [CLAUDE.md](CLAUDE.md) for an overview of the code layout and the main flows.

## Making changes

- Create a branch from the default branch and keep each pull request focused on one change.
- Add or update unit tests for parsing, selection and version logic. Tests must run on the JVM
  without network access — use recorded fixtures for HTTP responses.
- Keep every action reachable by touch on phones and tablets and by D-pad on TVs.
- Remember `minSdk` is 22; guard newer APIs with `Build.VERSION.SDK_INT` checks and keep
  `./gradlew lintDebug` free of errors.
- Add a line to `CHANGELOG.md` under *Unreleased* for user-visible changes.

## Testing on a device

Use the signed release build to test installation, notifications, or UI changes on a device.
Install the same APK on phones, tablets, and TVs:

```sh
./build.sh
adb -s DEVICE install -r dist/SiloraUpdater.apk
```

Check first-run setup, installation and update of Silora, cancellation of the install prompt,
and manual update checks. See [self-update testing](docs/self-update-testing.md) for updater upgrades.

## Reporting bugs

Open an issue with your device model, Android or Fire OS version, Silora Updater version, and the
exact error shown on screen.

## Releasing

1. Move the *Unreleased* changelog entries under a `## [1.2.3]` heading. Write them for the
   users reading the GitHub release notes.
2. Optionally update the local-build default version in `app/build.gradle.kts`.
3. On GitHub, open **Actions › Android build › Run workflow**, enter `1.2.3` and run it (or push the
   tag `v1.2.3`). CI builds and signs one APK for all supported devices and publishes it as
   `SiloraUpdater-tv.apk`. See the [local release instructions](README.md#publish-a-release)
   for the signed local workflow.
