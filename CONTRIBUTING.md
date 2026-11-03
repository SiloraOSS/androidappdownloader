# Contributing

Thanks for helping improve App Downloader!

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
- Keep the app usable with a TV remote: every action must be reachable with the D-pad.
- Remember `minSdk` is 22; guard newer APIs with `Build.VERSION.SDK_INT` checks and keep
  `./gradlew lintDebug` free of errors.
- Add a line to `CHANGELOG.md` under *Unreleased* for user-visible changes.

## Testing on a device

Unit tests don't cover installing apps, notifications or the UI. Before opening a pull request
that touches those areas, try it on a real device or emulator:

```sh
./gradlew installDebug
```

Useful checks: add each source type (URL, aftv.news code, GitHub repo), install, update, cancel
the install prompt, and run a manual update check.

## Reporting bugs

Open an issue with your device model, Android / Fire OS version, the source you added (URL, code
or repo) and the exact error shown on the card.

## Releasing

1. Move the *Unreleased* changelog entries under a `## [1.2.3]` heading. Write them for
   non-technical users: they are posted to Discord as-is.
2. Optionally update the local-build default version in `app/build.gradle.kts`.
3. Tag the commit (`git tag v1.2.3 && git push origin v1.2.3`). CI derives the version from the
   tag, builds and signs the APK, publishes the GitHub release and announces it on Discord.
