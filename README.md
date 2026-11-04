# Personal Workout

A personal Android app for short daily home workouts (10 to 15 minutes), aimed at steady muscle
build-up over months. Started from the English Practice app and keeps its structure: spaced repetition,
streak widget, progress tracking, export, automatic upload of the history to a private GitHub
repository, and an in-app Update button.

Work in progress: the practice content is still the English Practice curriculum and will be replaced
with workout exercises.

## Installing

Every push to `main` builds the app on GitHub Actions and publishes it as a release.
On the phone, open the repository's **Releases** page, download `personal-workout.apk` from the
latest release and open it. After that, the in-app Update button installs new builds.

## Structure

- `core/`: plain Kotlin practice logic, with tests. Runs without Android: `./gradlew :core:test`,
  or `gradle -p core test` without the Android SDK.
- `app/`: the Android app (Jetpack Compose), widget and reminder.
- History syncs to `progress/personal-workout.json` in the private data repository set in Settings.
