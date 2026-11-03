#!/usr/bin/env bash
# Prints the CHANGELOG.md section for a version, in plain language for release
# pages and the Discord announcement.
# Usage: release-notes.sh <version> [changelog]
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
