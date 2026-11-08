#!/bin/bash
# build-noteforge.sh — runs ON the Arch box inside tmux 'noteforge'.
LOG=~/AndroidStudioProjects/NoteForge/build.log
exec > >(tee -a "$LOG") 2>&1
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
set -x
echo "=== NoteForge build started $(date) ==="
cd ~/AndroidStudioProjects/NoteForge
./gradlew assembleDebug --console=plain
echo "=== build exit: $? ==="
ls -la app/build/outputs/apk/debug/app-debug.apk 2>/dev/null
md5sum app/build/outputs/apk/debug/app-debug.apk 2>/dev/null
