# Personal Workout

A personal Android app for short daily home workouts (10 to 15 minutes), aimed at steady muscle
build-up over months. Built from the English Practice app and keeps its structure, look, streak widget,
export, automatic upload to a private GitHub repository, and in-app Update button.

- 19 exercises in `app/src/main/assets/exercises.txt`, each a ladder of variations from easy to hard,
  with form cues and an animated stick figure. Bodyweight, pull-up bar, boxing bag and a small yard.
- After each exercise you log what you did per set and how it felt (easy / OK / hard), or skip it.
  Easy raises the target, OK nudges it, hard holds it, misses and skips lower it. Past the top of a
  variation's range you move to the next variation; at the top of the ladder a third set is added.
- Sessions rotate push, pull and legs so a main group is not trained two days running, add core most
  days and conditioning about twice a week, and introduce new exercises one at a time.
- Planned rest days (Saturday by default, set in Settings) don't break the streak. Marking a capoeira
  day on the home screen counts for the streak, makes that day's session short and optional, and keeps
  legs and conditioning out of that day and the next.
- Weekly weigh-in: a card on the home screen when the last weigh-in is a week old (the evening reminder
  mentions it the day before), and a weight chart on the progress screen.
- Progress: a level per exercise (variation number plus how far through its range), per muscle group,
  and overall, with charts.
- Every log is kept; Settings > Export history shares it as JSON, and after each session it is uploaded
  to `progress/personal-workout.json` in the private data repository set in Settings.

## Installing

Every push to `main` builds the app on GitHub Actions and publishes it as a release.
On the phone, open the repository's **Releases** page, download `personal-workout.apk` from the
latest release and open it. After that, the in-app Update button installs new builds.

## Structure

- `core/`: plain Kotlin training logic (exercise library parser, progression, session builder, streak,
  levels), with tests. Runs without Android: `./gradlew :core:test`, or `gradle -p core test` without the Android SDK.
- `app/`: the Android app (Jetpack Compose), widget and reminder.
- `tools/gen_figures.py` generates the stick figures in `core/.../Figures.kt`.
