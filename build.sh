#!/bin/bash
# Build script for 忘打卡 project
# Uses pre-extracted Gradle 8.9 to avoid repeated downloads
#
# Usage:
#   ./build.sh              # Debug build
#   ./build.sh release      # Release build
#   ./build.sh clean        # Clean build
#   ./build.sh clean release # Clean + Release build

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_ZIP="$SCRIPT_DIR/gradle-8.9-bin.zip"
GRADLE_HOME="$SCRIPT_DIR/gradle-8.9"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.9-bin.zip"

# --- Auto-download Gradle if missing ---
if [ ! -d "$GRADLE_HOME/bin" ]; then
    if [ ! -f "$GRADLE_ZIP" ]; then
        echo "⬇️  Gradle 8.9 not found. Downloading..."
        wget -q --show-progress -O "$GRADLE_ZIP" "$GRADLE_URL"
        echo "✅ Download complete."
    fi
    echo "📦 Extracting Gradle 8.9..."
    unzip -qo "$GRADLE_ZIP" -d "$SCRIPT_DIR"
    echo "✅ Extraction done."
fi

TASK="${1:-assembleDebug}"

if [[ "$TASK" == clean* ]]; then
    FULL_TASK="${TASK#clean }${TASK#clean }"
    CLEAN="clean"
    if [[ "$FULL_TASK" == release* ]] || [[ "$1" == "clean" ]]; then
        FULL_TASK="assembleRelease"
    else
        FULL_TASK="assembleDebug"
    fi
    GRADLE_TASK="$CLEAN $FULL_TASK"
elif [[ "$TASK" == release* ]]; then
    GRADLE_TASK="assembleRelease"
else
    GRADLE_TASK="$TASK"
fi

echo "🔨 Building: $GRADLE_TASK"
docker run --rm \
    -v "$SCRIPT_DIR":/project \
    -v "$GRADLE_HOME":/opt/gradle-8.9 \
    mingc/android-build-box:latest \
    /bin/bash -c "
        export GRADLE_HOME=/opt/gradle-8.9
        export PATH=\$GRADLE_HOME/bin:\$PATH
        cd /project && gradle $GRADLE_TASK
    "
