#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 9.6.0+ が必要です。Android Studioから開くか、Gradleをインストールしてください。" >&2
  exit 1
fi
gradle wrapper --gradle-version 9.6.0
./gradlew --version
