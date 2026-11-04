package com.dmeltz.personalworkout.core

/** Muscle groups the week is balanced across. */
enum class Group(val label: String, val short: String) {
    PUSH("Push: chest, shoulders, triceps", "Push"),
    PULL("Pull: back, biceps, grip", "Pull"),
    LEGS("Legs and glutes", "Legs"),
    CORE("Core", "Core"),
    CONDITIONING("Conditioning", "Conditioning"),
}

enum class Measure { REPS, SECONDS }

/** Muscles shown on the body map. */
enum class Muscle(val label: String) {
    CHEST("Chest"),
    SHOULDERS("Shoulders"),
    BICEPS("Biceps"),
    TRICEPS("Triceps"),
    FOREARMS("Forearms and grip"),
    ABS("Abs"),
    OBLIQUES("Obliques"),
    UPPER_BACK("Upper back"),
    LATS("Lats"),
    LOWER_BACK("Lower back"),
    GLUTES("Glutes"),
    QUADS("Quads"),
    HAMSTRINGS("Hamstrings"),
    CALVES("Calves"),
}

/** One step on an exercise's ladder, from easiest to hardest. [min]..[max] is the target range per set. */
data class Variation(
    val name: String,
    val min: Int,
    val max: Int,
    val how: String,
    val cues: List<String>,
)

/**
 * An exercise family, such as push-ups, with variations from easy to hard. Progress moves the target
 * up through each variation's range, then on to the next variation.
 */
data class Exercise(
    val id: String,
    val title: String,
    val group: Group,
    val measure: Measure,
    /** Each set is done once per side (split squats, side planks). */
    val perSide: Boolean,
    val sets: Int,
    /** Rest between sets, in seconds. */
    val rest: Int,
    /** The variation someone in reasonable shape starts on (0-based). */
    val start: Int,
    /** Lower comes into the plan sooner. Exercises with the same number start together. */
    val intro: Int,
    val equipment: String?,
    /** Name of the stick figure in [Figures]. */
    val figure: String,
    val cues: List<String>,
    val variations: List<Variation>,
    /** Muscles the exercise mainly works. */
    val primary: List<Muscle> = emptyList(),
    /** Muscles that help or stabilize. */
    val secondary: List<Muscle> = emptyList(),
) {
    fun variation(index: Int): Variation = variations[index.coerceIn(0, variations.lastIndex)]
    val unit: String get() = if (measure == Measure.REPS) "reps" else "s"
}

/** A warm-up move; [figure] names its stick figure in [Figures], if it has one. */
data class WarmupMove(val name: String, val seconds: Int, val figure: String? = null)

data class Library(
    val exercises: List<Exercise>,
    val warmup: List<WarmupMove>,
    val safety: List<String>,
) {
    private val byId = exercises.associateBy { it.id }
    fun exercise(id: String): Exercise? = byId[id]
    fun inGroup(group: Group): List<Exercise> = exercises.filter { it.group == group }
}

/** A stick figure pose: x,y pairs in a 100x100 box (y down, floor at 90), in [Pose.JOINTS] order. */
class Pose(val points: FloatArray) {
    fun x(joint: Int) = points[joint * 2]
    fun y(joint: Int) = points[joint * 2 + 1]

    companion object {
        const val HEAD = 0; const val NECK = 1; const val HIP = 2
        const val ELBOW = 3; const val HAND = 4; const val ELBOW2 = 5; const val HAND2 = 6
        const val KNEE = 7; const val FOOT = 8; const val KNEE2 = 9; const val FOOT2 = 10
        const val JOINTS = 11
        const val SHOULDER_HALF = 7f
        const val HIP_HALF = 4.5f
    }
}

sealed interface Prop {
    data class Bar(val y: Float) : Prop
    data class Wall(val x: Float) : Prop
    /** A door frame at [x], with a towel from the hands to a handle at [handleY]. */
    data class Door(val x: Float, val handleY: Float) : Prop
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) : Prop
    data class Bag(val x: Float, val y: Float, val w: Float, val h: Float) : Prop
    /** Hanging figures: no floor line, and the feet may go below 90. */
    data object NoFloor : Prop
    /**
     * A front or rear view: arms hang from shoulders [Pose.SHOULDER_HALF] either side of the neck,
     * legs from hips [Pose.HIP_HALF] either side of the hip point, and both sides are drawn alike.
     */
    data object Front : Prop
}

/** The figure moves between [start] and [end]. */
class Figure(val start: Pose, val end: Pose, val props: List<Prop>) {
    val front: Boolean get() = Prop.Front in props
}

/**
 * Parses the exercise library:
 *
 * ```
 * @exercise PUSHUP
 * title: Push-up
 * group: push
 * measure: reps
 * sets: 2
 * rest: 60
 * start: 1
 * intro: 1
 * figure: PUSHUP
 * muscles: chest triceps shoulders | abs
 * cue: A cue for every variation.
 * variation: Incline push-up | 8-15 | Hands on a table or counter.
 * cue: A cue for this variation only.
 *
 * @warmup
 * move: Arm circles | 30 | WU_ARMS
 *
 * @safety
 * line: Stop if something hurts.
 * ```
 *
 * Optional lines: "per side: yes", "equipment: pull-up bar". Lines starting with "//" and blank lines are ignored.
 */
object LibraryParser {
    private fun muscles(id: String, text: String): List<Muscle> =
        text.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            Muscle.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: error("Exercise $id: unknown muscle $name")
        }

    fun parse(text: String): Library {
        val exercises = mutableListOf<Exercise>()
        val warmup = mutableListOf<WarmupMove>()
        val safety = mutableListOf<String>()

        var section = ""
        var id: String? = null
        val fields = mutableMapOf<String, String>()
        val cues = mutableListOf<String>()
        val variations = mutableListOf<Variation>()
        var varName = ""; var varMin = 0; var varMax = 0; var varHow = ""
        val varCues = mutableListOf<String>()
        var inVariation = false

        fun flushVariation() {
            if (!inVariation) return
            variations += Variation(varName, varMin, varMax, varHow, varCues.toList())
            varCues.clear(); inVariation = false
        }

        fun flushExercise() {
            val exId = id ?: return
            flushVariation()
            fun need(key: String) = fields[key] ?: error("Exercise $exId has no $key")
            val ex = Exercise(
                id = exId,
                title = need("title"),
                group = Group.entries.firstOrNull { it.name.equals(need("group"), ignoreCase = true) }
                    ?: error("Exercise $exId: unknown group ${fields["group"]}"),
                measure = when (need("measure")) {
                    "reps" -> Measure.REPS
                    "seconds" -> Measure.SECONDS
                    else -> error("Exercise $exId: measure must be reps or seconds")
                },
                perSide = fields["per side"] == "yes",
                sets = need("sets").toInt(),
                rest = need("rest").toInt(),
                start = (fields["start"] ?: "1").toInt() - 1,
                intro = (fields["intro"] ?: "9").toInt(),
                equipment = fields["equipment"],
                figure = need("figure"),
                cues = cues.toList(),
                variations = variations.toList(),
                primary = muscles(exId, need("muscles").substringBefore("|")),
                secondary = muscles(exId, need("muscles").substringAfter("|", "")),
            )
            require(ex.variations.isNotEmpty()) { "Exercise $exId has no variations" }
            require(ex.start in ex.variations.indices) { "Exercise $exId: start is past the last variation" }
            exercises += ex
            id = null; fields.clear(); cues.clear(); variations.clear()
        }

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) return@forEachIndexed
            fun fail(msg: String): Nothing = error("Exercises line ${index + 1}: $msg: $line")
            when {
                line.startsWith("@exercise ") -> { flushExercise(); section = "exercise"; id = line.substringAfter(" ").trim() }
                line == "@warmup" -> { flushExercise(); section = "warmup" }
                line == "@safety" -> { flushExercise(); section = "safety" }
                section == "warmup" && line.startsWith("move:") -> {
                    val parts = line.substringAfter(":").split("|").map { it.trim() }
                    if (parts.size !in 2..3) fail("move needs 'name | seconds | figure'")
                    warmup += WarmupMove(parts[0], parts[1].toIntOrNull() ?: fail("bad seconds"), parts.getOrNull(2))
                }
                section == "safety" && line.startsWith("line:") -> safety += line.substringAfter(":").trim()
                section != "exercise" -> fail("unrecognized line")
                line.startsWith("variation:") -> {
                    flushVariation()
                    val parts = line.substringAfter(":").split("|").map { it.trim() }
                    if (parts.size != 3) fail("variation needs 'name | min-max | how'")
                    val range = parts[1].split("-").map { it.trim().toIntOrNull() ?: fail("bad range") }
                    if (range.size != 2 || range[0] < 1 || range[1] <= range[0]) fail("bad range")
                    varName = parts[0]; varMin = range[0]; varMax = range[1]; varHow = parts[2]
                    inVariation = true
                }
                line.startsWith("cue:") -> (if (inVariation) varCues else cues) += line.substringAfter(":").trim()
                line.contains(":") -> {
                    val key = line.substringBefore(":").trim()
                    if (key !in setOf("title", "group", "measure", "per side", "sets", "rest", "start", "intro", "equipment", "figure", "muscles")) {
                        fail("unknown field")
                    }
                    fields[key] = line.substringAfter(":").trim()
                }
                else -> fail("unrecognized line")
            }
        }
        flushExercise()
        val ids = exercises.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { error("Duplicate exercise $it") }
        return Library(exercises, warmup, safety)
    }
}

/**
 * The views of an exercise's figure: the side view, plus a front or rear view when there is one.
 * Hanging exercises read better from the front, so that view comes first for them.
 */
fun Figures.views(name: String): List<Figure> {
    val side = all[name] ?: return emptyList()
    val front = all[name + "_FRONT"] ?: return listOf(side)
    return if (Prop.NoFloor in side.props) listOf(front, side) else listOf(side, front)
}
