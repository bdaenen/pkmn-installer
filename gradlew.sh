#!/usr/bin/env bash
# Runs a Gradle task inside the Android build container.
#
# Usage:
#   ./gradlew.sh testDebugUnitTest
#   ./gradlew.sh assembleDebug
#
# The project is mounted at /workspace and Gradle caches are kept in a named
# volume so repeated builds are fast.
set -euo pipefail

IMAGE="pkmn-installer-android"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! podman image exists "$IMAGE"; then
    echo "Image '$IMAGE' not found. Building it first..."
    podman build --platform linux/amd64 -t "$IMAGE" "$PROJECT_DIR"
fi

exec podman run --rm \
    --platform linux/amd64 \
    -v "$PROJECT_DIR:/workspace" \
    -v pkmn-installer-gradle-cache:/gradle-cache \
    -w /workspace \
    "$IMAGE" \
    gradle "$@"
