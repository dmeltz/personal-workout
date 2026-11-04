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

/**
 * How a one-sided movement is counted. The target is always per side: 8 means 8 on the left and 8 on
 * the right. [EACH]: all reps on one side, then the other. [ALTERNATE]: switch sides every rep.
 */
enum class Sides { NONE, EACH, ALTERNATE }

/**
 * One step on an exercise's ladder, from easiest to hardest. [min]..[max] is the target range per set
 * (per side for one-sided moves). [sides] overrides the exercise's when this variation differs.
 */
data class Variation(
    val name: String,
    val min: Int,
    val max: Int,
    val how: String,
    val cues: List<String>,
    val sides: Sides? = null,
    /** This variation's own stick figure in [Figures], when it differs from the exercise's. */
    val figure: String? = null,
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
    /** Whether, and how, sets are done on each side (split squats, side planks, dead bugs). */
    val sides: Sides,
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

    fun sidesAt(variation: Int): Sides = variation(variation).sides ?: sides

    /** The stick figure for a variation: its own, or the exercise's. */
    fun figureAt(variation: Int): String = variation(variation).figure ?: figure
    fun perSide(variation: Int): Boolean = sidesAt(variation) != Sides.NONE

    /** "8 reps" or "8 reps per side". */
    fun amount(variation: Int, n: Int): String = "$n $unit" + if (perSide(variation)) " per side" else ""

    /** How to do the sides for a target, in plain words, or null for a two-sided move. */
    fun sidesHow(variation: Int, n: Int): String? = when (sidesAt(variation)) {
        Sides.NONE -> null
        Sides.EACH -> if (measure == Measure.REPS) {
            "Do all $n reps on one side, then $n on the other ($n per side, ${n * 2} in total)."
        } else {
            "Hold $n s on one side, then $n s on the other."
        }
        Sides.ALTERNATE -> "Switch sides every rep: left, right, left… Count each side: $n per side is ${n * 2} reps in total."
    }
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

/**
 * A stick figure pose, projected from the 3D model in tools/gen_figures.py: x, y and depth for each
 * joint in [Pose.JOINTS] order. x and y are in a 100 x 104 box (y down); a smaller depth is nearer.
 */
class Pose(val points: FloatArray) {
    fun x(joint: Int) = points[joint * 3]
    fun y(joint: Int) = points[joint * 3 + 1]
    fun depth(joint: Int) = points[joint * 3 + 2]

    companion object {
        const val HEAD = 0; const val EYE_L = 1; const val EYE_R = 2; const val NECK = 3
        const val SHOULDER_L = 4; const val ELBOW_L = 5; const val HAND_L = 6
        const val SHOULDER_R = 7; const val ELBOW_R = 8; const val HAND_R = 9
        const val PELVIS = 10
        const val HIP_L = 11; const val KNEE_L = 12; const val FOOT_L = 13; const val TOE_L = 14
        const val HIP_R = 15; const val KNEE_R = 16; const val FOOT_R = 17; const val TOE_R = 18
        const val JOINTS = 19

        /** The body's lines, as joint pairs. */
        val SEGMENTS = intArrayOf(
            NECK, HEAD, NECK, PELVIS, SHOULDER_L, SHOULDER_R, HIP_L, HIP_R,
            SHOULDER_L, ELBOW_L, ELBOW_L, HAND_L, SHOULDER_R, ELBOW_R, ELBOW_R, HAND_R,
            HIP_L, KNEE_L, KNEE_L, FOOT_L, FOOT_L, TOE_L, HIP_R, KNEE_R, KNEE_R, FOOT_R, FOOT_R, TOE_R,
        )
    }
}

/** Equipment and floor marks, already projected into the figure's box. */
sealed interface Prop {
    /** A bar seen along its length, a box edge, a door edge, or a faint mat outline. */
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val faint: Boolean) : Prop
    /** A bar seen end-on. */
    data class Dot(val x: Float, val y: Float, val r: Float) : Prop
    data class Bag(val x: Float, val y: Float, val w: Float, val h: Float) : Prop
    /** A towel from a joint (a hand) to a fixed point. */
    data class Tether(val joint: Int, val x: Float, val y: Float) : Prop
}

/**
 * One view of an exercise, as [frames] the figure moves through: back and forth, or round in a
 * [loop]. [scale] is how much the body was shrunk to fit (1 = life size in the box); [floor] draws
 * the floor line at y = 90.
 */
class Figure(
    val frames: List<Pose>,
    val props: List<Prop>,
    val floor: Boolean,
    val scale: Float,
    val label: String,
    val loop: Boolean,
    val durationMs: Int,
)

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
 * Optional lines: "sides: each" or "sides: alternate" (the target is then per side), "equipment: pull-up bar".
 * A variation can end with "| each", "| alternate" or "| none" when it differs from its exercise, and
 * a "figure:" line after a variation gives that variation its own stick figure. Lines starting with "//" and blank lines are ignored.
 */
object LibraryParser {
    private fun muscles(id: String, text: String): List<Muscle> =
        text.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            Muscle.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: error("Exercise $id: unknown muscle $name")
        }

    private fun sides(id: String, text: String?): Sides? = when (text?.lowercase()) {
        null -> null
        "each" -> Sides.EACH
        "alternate" -> Sides.ALTERNATE
        "none" -> Sides.NONE
        else -> error("Exercise $id: sides must be each, alternate or none")
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
        var varName = ""; var varMin = 0; var varMax = 0; var varHow = ""; var varSides: Sides? = null; var varFigure: String? = null
        val varCues = mutableListOf<String>()
        var inVariation = false

        fun flushVariation() {
            if (!inVariation) return
            variations += Variation(varName, varMin, varMax, varHow, varCues.toList(), varSides, varFigure)
            varCues.clear(); varFigure = null; inVariation = false
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
                sides = sides(exId, fields["sides"]) ?: Sides.NONE,
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
                    if (parts.size !in 3..4) fail("variation needs 'name | min-max | how[ | sides]'")
                    val range = parts[1].split("-").map { it.trim().toIntOrNull() ?: fail("bad range") }
                    if (range.size != 2 || range[0] < 1 || range[1] <= range[0]) fail("bad range")
                    varName = parts[0]; varMin = range[0]; varMax = range[1]; varHow = parts[2]
                    varSides = parts.getOrNull(3)?.let { sides(id ?: "", it) ?: fail("sides must be each, alternate or none") }
                    inVariation = true
                }
                inVariation && line.startsWith("figure:") -> varFigure = line.substringAfter(":").trim()
                line.startsWith("cue:") -> (if (inVariation) varCues else cues) += line.substringAfter(":").trim()
                line.contains(":") -> {
                    val key = line.substringBefore(":").trim()
                    if (key !in setOf("title", "group", "measure", "sides", "sets", "rest", "start", "intro", "equipment", "figure", "muscles")) {
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

/** The views of an exercise's figure, main view first (for example the side, then the front). */
fun Figures.views(name: String): List<Figure> = listOfNotNull(all[name], all[name + "_2"])
