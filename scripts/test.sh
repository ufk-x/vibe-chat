#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/tests
javac -encoding UTF-8 -d build/tests app/src/main/java/chat/vibe/app/core/*.java tests/CoreTest.java tests/VoiceDeliveryTest.java
java -cp build/tests CoreTest
java -cp build/tests VoiceDeliveryTest
