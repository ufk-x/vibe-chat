#!/usr/bin/env bash
set -euo pipefail
umask 077
VIBE_STAMP=$(date -u +%Y%m%dT%H%M%SZ)
VIBE_TARGET="/var/backups/vibe/vibe-$VIBE_STAMP.sqlite"
sqlite3 /var/lib/vibe/vibe.sqlite ".backup '$VIBE_TARGET'"
test "$(sqlite3 "$VIBE_TARGET" 'PRAGMA integrity_check;')" = ok
gzip "$VIBE_TARGET"
find /var/backups/vibe -maxdepth 1 -name 'vibe-*.sqlite.gz' -mtime +7 -delete
