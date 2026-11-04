#!/usr/bin/env bash
# Prints the CHANGELOG.md section for a version, e.g.  ./scripts/release-notes.sh v1.0.0
# The section starts at a heading "## [1.0.0]" and ends at the next "## " heading.
set -euo pipefail
version="${1#v}"
notes="$(awk -v v="$version" '
  /^## / { if (found) exit; if (index($0, "[" v "]")) { found = 1; next } }
  found { print }
' "$(dirname "$0")/../CHANGELOG.md")"
[ -n "$notes" ] || { echo "No section for [$version] in CHANGELOG.md" >&2; exit 1; }
printf '%s\n' "$notes"
