#!/usr/bin/env bash
# Prints the CHANGELOG.md section for a version. Set WITH_INSTALL_STEPS=1 for GitHub release notes.
# Usage: release-notes.sh <version> [changelog]
# Env: WITH_INSTALL_STEPS, GITHUB_REPOSITORY (defaults to the Silora Updater repo), AFTV_CODE (optional)
set -euo pipefail
version="$1"
changelog="${2:-CHANGELOG.md}"

notes=$(awk -v v="$version" '
  index($0, "## [" v "]") == 1 { found = 1; next }
  found && /^## \[/ { exit }
  found { print }
' "$changelog" | sed \
  -e 's/^### Added$/**New**/' \
  -e 's/^### Changed$/**Improved**/' \
  -e 's/^### Fixed$/**Fixed**/' \
  -e 's/^### Removed$/**Removed**/' \
  -e 's/^### Security$/**Security**/' \
  | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')

if [ -z "$notes" ]; then
  notes="Bug fixes and improvements."
fi
printf '%s\n' "$notes"

if [ "${WITH_INSTALL_STEPS:-}" != "1" ]; then
  exit 0
fi

repo="${GITHUB_REPOSITORY:-SiloraOSS/androidappdownloader}"
repo_url="https://github.com/$repo"
apk_url="$repo_url/releases/download/v$version/SiloraUpdater-tv.apk"
aftv_code="${AFTV_CODE:-}"
if [ -z "$aftv_code" ] && [ "$repo" = "SiloraOSS/androidappdownloader" ]; then
  aftv_code=7394471
fi
cat <<EOF

## How to install

The same \`SiloraUpdater-tv.apk\` works on Android phones and tablets, Android TV, Google TV, and Fire TV.

- **Phone or tablet** — [download the APK]($apk_url) in your browser and open it to install.
EOF

if [ -n "$aftv_code" ]; then
  echo "- **TV** — enter **$aftv_code** in Downloader for the latest version, or [download this release's APK]($apk_url)."
else
  echo "- **TV** — [download the APK]($apk_url) in Downloader or your TV browser and open it to install."
fi
cat <<EOF
- Allow installs from the download source if Android prompts you. Open **Silora Updater** and choose **Install Silora** or **Update Silora**.

[Get the latest release]($repo_url/releases/latest)
EOF
