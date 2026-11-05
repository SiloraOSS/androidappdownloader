# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.4.0] - 2026-11-05

### Added
- Scheduled updates can now also repeat throughout the day: every 1, 2, 4, 8 or 12 hours,
  starting from the date and time you pick.

## [1.3.0] - 2026-11-05

### Added
- Plain links and aftv.news codes now show whether the file on the website has changed since
  you installed it ("Changed on the website" / "No change"), checked without downloading it.
  The ⟳ button and "Check for update" now cover these apps too, and you get notified about changes.
- New setting "Only update links whose file has changed" (on by default) for scheduled updates and
  "Update all now". If a website doesn't say whether its file changed, it is still downloaded and
  compared, so no update is missed.

## [1.2.0] - 2026-11-05

### Added
- Scheduled updates: pick a date and time in Settings (once, every day or every week) and
  App Downloader updates all your apps at that time.
- Each app now has an "Auto-update" tick box, so you choose which apps are included.
- "Update all now" in Settings updates every ticked app straight away.
- If Android needs you to confirm an install, the update is downloaded in advance and waits
  for you. Next time you open App Downloader it offers to install it with one click.

## [1.1.0] - 2026-11-03

### Added
- App Downloader now checks for its own updates when you open it and offers to install them.
  You can turn this off in Settings, or check right away with "Check for app update now".
- Settings shows which version of App Downloader you have.

### Changed
- Much easier to use with a TV remote: the selected button is outlined and slightly enlarged,
  and the app you're on is outlined too.
- Extra space around the edges on TVs, so nothing gets cut off.
- Clearer help for allowing installs on Android TV and Google TV, not just Fire TV.
- Options that need a web browser are hidden on TVs that don't have one.

## [1.0.0] - 2026-11-03

### Added
- Save and install APKs from direct URLs, aftv.news codes and GitHub repositories.
- Web-page links are scanned for an `.apk` link when they don't point at an APK directly.
- GitHub release monitoring with ABI-aware asset selection, optional asset filter and pre-release
  opt-in per repository.
- Background update checks (3/6/12/24 h or manual) with notifications.
- Installation of plain APKs and split bundles (`.xapk`, `.apks`) via PackageInstaller.
- Open, uninstall, re-download, edit and remove actions per app; share-to-add from other apps.
- Settings for GitHub token, check interval and deleting APKs after install.
- Fire TV / Android TV support (leanback launcher, D-pad navigation).
- CI workflow that tests, builds and publishes APKs on version tags.
