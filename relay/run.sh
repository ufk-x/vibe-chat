#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/relay
javac -encoding UTF-8 -d build/relay app/src/main/java/chat/vibe/app/core/*.java relay/RelayMain.java
exec java -cp build/relay RelayMain "$@"
