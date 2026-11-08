# NoteForge

Android music composition app: compose on a real music staff with
procedural synth instruments — no samples.

## What it does

- **Score** — scrollable staff. Tap to place a note (pitch from where you
  tap vertically, beat position from where you tap horizontally, snapped
  to the selected note duration). Tap a note again to delete it. Add tracks,
  each bound to one instrument; mute tracks; press play to hear the score.
- **Instruments** — synth-only instruments. Each song's track configs are
  restored on reload. Synth editor with Re-roll + play, 25 randomizable
  params, per-osc editors and dice-bias controls. Mimic generates a patch
  from a WAV.
- **Sequencer** — live-renders each synth note, including a 1.8s release
  tail. One red playhead spans all tracks.
- **File** — project name is set before note insert so a project can't be
  left 'Untitled'.
- Beams are thin and stem-consistent, break at barlines and rests, and the
  first note after a barline never touches it. Quantize / IOI / Gap sliders
  apply live without resetting zoom.

## Stack

Kotlin / Jetpack Compose / Hilt / Room / MVVM + StateFlow.
Sync server merges by updatedAt (Tailscale-only).

## Build

    ./gradlew assembleDebug

APK lands in `app/build/outputs/apk/debug/`. Needs Android SDK
(`local.properties`: `sdk.dir=/home/bobby/Android/Sdk`) and Java 17.
