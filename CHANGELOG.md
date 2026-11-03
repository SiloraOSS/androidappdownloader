# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
