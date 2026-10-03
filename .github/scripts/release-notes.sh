#!/usr/bin/env bash
# Prints the CHANGELOG.md section for one version, without its heading. That text becomes the release's notes, which
# the app shows under "What changed" before the user installs. Fails when there is no such section or it is empty,
# so a release can never go out with nothing to say.
set -euo pipefail

version="${1:?usage: release-notes.sh X.Y.Z}"
version="${version#v}"

notes=$(awk -v v="$version" '
  /^## / {
    if (found) exit
    heading = $2
    sub(/^v/, "", heading)
    if (heading == v) { found = 1; next }
  }
  found { print }
' CHANGELOG.md)

# Drop the blank lines at the start and the end.
notes=$(printf '%s\n' "$notes" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')

if [ -z "$(printf '%s' "$notes" | tr -d '[:space:]')" ]; then
  echo "CHANGELOG.md has no notes under a heading for version $version" >&2
  exit 1
fi

printf '%s\n' "$notes"
