package com.vysion.xr.voice

import com.vysion.xr.core.Command
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.Speaker
import java.text.Normalizer
import java.util.Locale

/**
 * Turns one recognized utterance into a [Command], or `null` when it is not one.
 *
 * Pure Kotlin, no Android classes: this is the unit-tested half of `CommandBus` (FR-G2 "fuzzy matching so
 * 'read it' and 'read that' both work"; P1 FR-T3 / FR-T4 / FR-D2 / FR-D3; P2 FR-T6 / FR-N7). Owner R4,
 * SRS §8 "CommandBus ... grammar for read, describe, guide me, find the door, stop, repeat, slower, in
 * Spanish; fuzzy matching", due Sat 14:00. Consumed by [SpeechCommandBus] and, through
 * `CommandBus.commands`, by R1's `ModeController`. Nothing here needs the device; the recognizer facts it
 * depends on are in `docs/stack.md` §3-R4 (results arrive as `ArrayList<String>`, most likely first).
 *
 * How it matches (the full table is in `docs/modules/r4-voice-accessibility.md`, "Command grammar"):
 * 1. normalize: lower-case, strip accents and punctuation, drop a leading wake word ("vysion" / "vision")
 *    and filler ("hey", "please", "oye");
 * 2. every pattern is a set of key tokens; all keys must be found among the utterance tokens, in any order.
 *    A key matches a token exactly or, for keys of [MIN_FUZZY_KEY_LENGTH]+ letters, within a Levenshtein
 *    distance of 1 (4–5 letters) or 2 (6+ letters). Some short keys are exact-only and some patterns only
 *    apply to short utterances (so "door" alone does not eat "read the door sign"). English "stop" has its
 *    own safety rule (FR-G4, INV-6): fuzzy only in a bare command of at most [SAFETY_FUZZY_MAX_TOKENS] tokens,
 *    where distance 2 is allowed for a token that keeps the "st" onset ("stahp"); exact in anything longer
 *    ("is there a step?" stays a question);
 * 3. the pattern with the most matched keys wins, then the smallest edit distance, then the language of
 *    [parse]'s `locale`, then table order (Stop is first so it is never shadowed, INV-6);
 * 4. no pattern, but the utterance ends in "?" or opens with a question word → [Command.FollowUp] (FR-D2).
 *
 * Both languages are always understood (the user switches with "in Spanish" at any time, FR-G6); `locale`
 * only breaks ties. Assumption A-7 (on-device recognition) is the bus's concern, not the grammar's.
 */
object CommandGrammar {

    /**
     * Phrases the recognizer should favor (`RecognizerIntent.EXTRA_BIASING_STRINGS`): the canonical commands in the
     * session's language, so a short command in a noisy room is heard as the command, not a similar word. Biasing
     * only shifts the recognizer's guesses; [parse] still decides what is a command.
     */
    fun biasingPhrases(languageTag: String): List<String> =
        if (languageTag.lowercase().startsWith("es")) BIASING_ES else BIASING_EN

    private val BIASING_EN = listOf(
        "read this", "read that", "describe", "what's here", "what's in front of me", "find the door", "guide me",
        "stop", "repeat", "say that again", "slower", "in Spanish", "more detail", "next",
    )

    private val BIASING_ES = listOf(
        "lee esto", "lee eso", "describe", "qué hay aquí", "qué hay enfrente", "busca la puerta", "guíame",
        "alto", "para", "repite", "más despacio", "más detalle", "siguiente",
    )

    /** Leading tokens dropped before matching (FR-G2: "Vysion, read this"). Matched within distance 1. */
    val WAKE_WORDS: Set<String> = setOf("vysion", "vision")

    /** Leading filler dropped before and after the wake word. */
    val FILLER_WORDS: Set<String> = setOf("hey", "ok", "okay", "please", "oye", "hola", "por", "favor")

    /** Keys shorter than this must match exactly; "lee" must not match "leg". */
    const val MIN_FUZZY_KEY_LENGTH: Int = 4

    /** Distance budget per key: 1 for 4–5 letter keys, 2 for 6+ (SRS "fuzzy matching"). */
    const val MAX_EDIT_DISTANCE: Int = 2

    /**
     * A fuzzy "stop" only counts in an utterance of at most this many tokens (after the wake word and
     * filler): a bare command, where a near-"stop" word is most likely a misheard "stop". In a longer
     * sentence "stop" must be heard exactly ("stop reading", "please stop it now"), so a question that
     * merely contains a similar short word ("is there a step?", "what's on the top shelf?") is not taken
     * for Stop. A false Stop only silences (the user repeats the command); a missed Stop is the INV-6
     * failure, which is why the bare command gets the wider budget below.
     */
    const val SAFETY_FUZZY_MAX_TOKENS: Int = 2

    /**
     * Distance budget for English "stop" in a bare command (FR-G4: Stop is safety-relevant, so a recognizer
     * slip such as "stahp" must still stop). The length rule gives a 4-letter key 1 edit; Stop gets 2 when
     * the token keeps the key's first [SAFETY_ONSET_LETTERS] letters, so short words that share only
     * letters, not the onset ("so", "to", "slow", Spanish "esto"), are not Stop.
     */
    const val SAFETY_MAX_EDIT_DISTANCE: Int = 2

    /** Letters a token at [SAFETY_MAX_EDIT_DISTANCE] must share with the start of "stop": the "st" onset. */
    const val SAFETY_ONSET_LETTERS: Int = 2

    /**
     * @param utterance raw recognizer text, e.g. "Vysion, read this" or "¿qué hay en la mesa?".
     * @param locale the active speech locale (`Speaker.locale`); decides which language wins a tie.
     */
    fun parse(utterance: String, locale: Locale = Speaker.EN_US): Command? {
        val raw = utterance.trim()
        if (raw.isEmpty()) return null
        val tokens = tokens(raw)
        if (tokens.isEmpty()) return null
        matchCommand(tokens, locale)?.let { return it }
        val isQuestion = raw.endsWith("?") || tokens.first() in QUESTION_WORDS
        // FR-D2: a question that is not a command runs against the same frame; the VLM gets the natural text.
        return if (isQuestion && tokens.size >= 2) Command.FollowUp(stripPrefix(raw)) else null
    }

    /** Normalized tokens with the wake word and leading filler removed. Exposed for tests. */
    internal fun tokens(utterance: String): List<String> {
        val all = TextFold.fold(utterance).split(' ').filter { it.isNotEmpty() }
        return all.drop(prefixLength(all))
    }

    /** Levenshtein edit distance; the fuzzy-match primitive. Exposed for tests. */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    // ── matching ─────────────────────────────────────────────────────────────────────────────────────

    private enum class Lang { EN, ES }

    private class Pattern(
        val command: Command,
        val lang: Lang,
        val keys: List<String>,
        /** Exact token match only: short words with other meanings (es "para" is also "for"). */
        val exact: Boolean = false,
        /** Only utterances with at most this many tokens qualify. */
        val maxTokens: Int = Int.MAX_VALUE,
        /**
         * Safety rule (English "stop" only) instead of the length rule: in an utterance of at most
         * [SAFETY_FUZZY_MAX_TOKENS] tokens a token within 1 edit, or within [SAFETY_MAX_EDIT_DISTANCE] when
         * it keeps the first [SAFETY_ONSET_LETTERS] letters; in a longer utterance an exact token only.
         */
        val safety: Boolean = false,
    )

    private class Match(val pattern: Pattern, val index: Int, val distance: Int)

    private fun pattern(
        command: Command,
        lang: Lang,
        vararg keys: String,
        exact: Boolean = false,
        maxTokens: Int = Int.MAX_VALUE,
        safety: Boolean = false,
    ) = Pattern(command, lang, keys.toList(), exact, maxTokens, safety)

    private val LEFT = Command.SelectDoor(HorizontalBearing.LEFT)
    private val CENTER = Command.SelectDoor(HorizontalBearing.CENTER)
    private val RIGHT = Command.SelectDoor(HorizontalBearing.RIGHT)
    private val CLOUD_ON = Command.CloudDescribe(on = true)
    private val CLOUD_OFF = Command.CloudDescribe(on = false)

    /** Table order is the last tie-breaker; Stop is first on purpose (INV-6). */
    private val PATTERNS: List<Pattern> = listOf(
        // FR-G2 stop
        pattern(Command.Stop, Lang.EN, "stop", safety = true),
        pattern(Command.Stop, Lang.ES, "detente"),
        pattern(Command.Stop, Lang.ES, "basta"),
        pattern(Command.Stop, Lang.ES, "silencio"),
        pattern(Command.Stop, Lang.ES, "para", exact = true, maxTokens = 2),
        pattern(Command.Stop, Lang.ES, "alto", exact = true, maxTokens = 2),
        // FR-N3 find the door
        pattern(Command.FindDoor, Lang.EN, "find", "door"),
        pattern(Command.FindDoor, Lang.EN, "where", "door"),
        pattern(Command.FindDoor, Lang.EN, "find", "exit"),
        pattern(Command.FindDoor, Lang.EN, "where", "exit"),
        pattern(Command.FindDoor, Lang.EN, "door", maxTokens = 3),
        pattern(Command.FindDoor, Lang.EN, "exit", maxTokens = 3),
        pattern(Command.FindDoor, Lang.ES, "busca", "puerta"),
        pattern(Command.FindDoor, Lang.ES, "donde", "puerta"),
        pattern(Command.FindDoor, Lang.ES, "encuentra", "puerta"),
        pattern(Command.FindDoor, Lang.ES, "busca", "salida"),
        pattern(Command.FindDoor, Lang.ES, "puerta", maxTokens = 3),
        pattern(Command.FindDoor, Lang.ES, "salida", maxTokens = 3),
        // FR-N7 select among door candidates (two keys beat FindDoor's single "door"/"puerta")
        pattern(LEFT, Lang.EN, "one", "left"),
        pattern(LEFT, Lang.EN, "door", "left"),
        pattern(CENTER, Lang.EN, "one", "middle"),
        pattern(CENTER, Lang.EN, "one", "center"),
        pattern(CENTER, Lang.EN, "door", "middle"),
        pattern(CENTER, Lang.EN, "door", "center"),
        pattern(RIGHT, Lang.EN, "one", "right"),
        pattern(RIGHT, Lang.EN, "door", "right"),
        pattern(LEFT, Lang.ES, "puerta", "izquierda"),
        pattern(CENTER, Lang.ES, "puerta", "centro"),
        pattern(CENTER, Lang.ES, "puerta", "medio"),
        pattern(RIGHT, Lang.ES, "puerta", "derecha"),
        // FR-T1 read
        pattern(Command.Read, Lang.EN, "read"),
        pattern(Command.Read, Lang.ES, "lee", exact = true),
        pattern(Command.Read, Lang.ES, "leer"),
        pattern(Command.Read, Lang.ES, "leelo"),
        pattern(Command.Read, Lang.ES, "leeme"),
        // FR-D1 describe
        pattern(Command.Describe, Lang.EN, "describe"),
        pattern(Command.Describe, Lang.EN, "around"),
        pattern(Command.Describe, Lang.EN, "surroundings"),
        pattern(Command.Describe, Lang.ES, "describe"),
        pattern(Command.Describe, Lang.ES, "describeme"),
        pattern(Command.Describe, Lang.ES, "alrededor"),
        pattern(Command.Describe, Lang.ES, "que", "hay", exact = true),
        // FR-G2 guide me (Navigation mode on)
        pattern(Command.GuideMe, Lang.EN, "guide"),
        pattern(Command.GuideMe, Lang.EN, "navigate"),
        pattern(Command.GuideMe, Lang.EN, "navigation"),
        pattern(Command.GuideMe, Lang.ES, "guiame"),
        pattern(Command.GuideMe, Lang.ES, "guia", exact = true),
        pattern(Command.GuideMe, Lang.ES, "navega"),
        pattern(Command.GuideMe, Lang.ES, "navegacion"),
        // FR-T3 repeat / slower
        pattern(Command.Repeat, Lang.EN, "repeat"),
        pattern(Command.Repeat, Lang.EN, "again"),
        pattern(Command.Repeat, Lang.ES, "repite"),
        pattern(Command.Repeat, Lang.ES, "repitelo"),
        pattern(Command.Repeat, Lang.ES, "otra", "vez", exact = true),
        pattern(Command.Repeat, Lang.ES, "de", "nuevo", exact = true),
        pattern(Command.Slower, Lang.EN, "slower"),
        pattern(Command.Slower, Lang.EN, "slowly"),
        pattern(Command.Slower, Lang.EN, "slow"),
        pattern(Command.Slower, Lang.ES, "despacio"),
        pattern(Command.Slower, Lang.ES, "lento"),
        // FR-T4 / FR-G6 in Spanish
        pattern(Command.InSpanish, Lang.EN, "spanish"),
        pattern(Command.InSpanish, Lang.EN, "translate"),
        pattern(Command.InSpanish, Lang.ES, "espanol"),
        pattern(Command.InSpanish, Lang.ES, "castellano"),
        pattern(Command.InSpanish, Lang.ES, "traduce"),
        pattern(Command.InSpanish, Lang.ES, "traducelo"),
        // FR-T6 next
        pattern(Command.Next, Lang.EN, "next"),
        pattern(Command.Next, Lang.ES, "siguiente"),
        pattern(Command.Next, Lang.ES, "proxima"),
        pattern(Command.Next, Lang.ES, "proximo"),
        // FR-D3 brief / detailed
        pattern(Command.Brief, Lang.EN, "brief"),
        pattern(Command.Brief, Lang.EN, "briefly"),
        pattern(Command.Brief, Lang.EN, "shorter"),
        pattern(Command.Brief, Lang.EN, "short"),
        pattern(Command.Brief, Lang.ES, "breve"),
        pattern(Command.Brief, Lang.ES, "corto"),
        pattern(Command.Brief, Lang.ES, "resumen"),
        pattern(Command.Detailed, Lang.EN, "detailed"),
        pattern(Command.Detailed, Lang.EN, "detail"),
        pattern(Command.Detailed, Lang.EN, "details"),
        pattern(Command.Detailed, Lang.EN, "longer"),
        pattern(Command.Detailed, Lang.ES, "detallado"),
        pattern(Command.Detailed, Lang.ES, "detalle"),
        pattern(Command.Detailed, Lang.ES, "detalles"),
        // ADR-0010 cloud Describe on/off; short utterances only, so "is it cloudy on the left?" stays a question
        pattern(CLOUD_ON, Lang.EN, "cloud", "on", maxTokens = 4),
        pattern(CLOUD_OFF, Lang.EN, "cloud", "off", maxTokens = 4),
        pattern(CLOUD_ON, Lang.ES, "activa", "nube", maxTokens = 4),
        pattern(CLOUD_ON, Lang.ES, "enciende", "nube", maxTokens = 4),
        pattern(CLOUD_ON, Lang.ES, "nube", "activada", maxTokens = 4),
        pattern(CLOUD_OFF, Lang.ES, "desactiva", "nube", maxTokens = 4),
        pattern(CLOUD_OFF, Lang.ES, "apaga", "nube", maxTokens = 4),
        pattern(CLOUD_OFF, Lang.ES, "nube", "desactivada", maxTokens = 4),
        // FR-N7 single-word side, short utterances only ("the left", "on the left", "la izquierda")
        pattern(LEFT, Lang.EN, "left", maxTokens = 3),
        pattern(CENTER, Lang.EN, "middle", maxTokens = 3),
        pattern(CENTER, Lang.EN, "center", maxTokens = 3),
        pattern(RIGHT, Lang.EN, "right", maxTokens = 3),
        pattern(LEFT, Lang.ES, "izquierda", maxTokens = 4),
        pattern(CENTER, Lang.ES, "centro", maxTokens = 4),
        pattern(CENTER, Lang.ES, "medio", maxTokens = 4),
        pattern(RIGHT, Lang.ES, "derecha", maxTokens = 4),
    )

    private val QUESTION_WORDS: Set<String> = setOf(
        "what", "whats", "which", "where", "wheres", "who", "whos", "how", "why", "when", "is", "are", "does", "do",
        "can", "que", "cual", "cuales", "cuanto", "cuantos", "cuantas", "donde", "quien", "como", "hay", "es", "esta",
        "estan", "por",
    )

    private fun matchCommand(tokens: List<String>, locale: Locale): Command? {
        val preferred = if (locale.language == "es") Lang.ES else Lang.EN
        var best: Match? = null
        PATTERNS.forEachIndexed { index, pattern ->
            if (tokens.size > pattern.maxTokens) return@forEachIndexed
            val distance = matchKeys(pattern, tokens) ?: return@forEachIndexed
            val candidate = Match(pattern, index, distance)
            val incumbent = best
            if (incumbent == null || candidate.beats(incumbent, preferred)) best = candidate
        }
        return best?.pattern?.command
    }

    private fun Match.beats(other: Match, preferred: Lang): Boolean = when {
        pattern.keys.size != other.pattern.keys.size -> pattern.keys.size > other.pattern.keys.size
        distance != other.distance -> distance < other.distance
        (pattern.lang == preferred) != (other.pattern.lang == preferred) -> pattern.lang == preferred
        else -> index < other.index
    }

    /** Total edit distance over all keys, or `null` when a key has no token within tolerance. */
    private fun matchKeys(pattern: Pattern, tokens: List<String>): Int? {
        var total = 0
        val used = BooleanArray(tokens.size)
        val bareCommand = tokens.size <= SAFETY_FUZZY_MAX_TOKENS
        for (key in pattern.keys) {
            var bestIndex = -1
            var bestDistance = Int.MAX_VALUE
            tokens.forEachIndexed { i, token ->
                if (used[i]) return@forEachIndexed
                val d = keyDistance(key, token, pattern, bareCommand) ?: return@forEachIndexed
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = i
                }
            }
            if (bestIndex < 0) return null
            used[bestIndex] = true
            total += bestDistance
        }
        return total
    }

    private fun keyDistance(key: String, token: String, pattern: Pattern, bareCommand: Boolean): Int? {
        if (key == token) return 0
        if (pattern.exact) return null
        if (pattern.safety) {
            if (!bareCommand) return null
            val d = levenshtein(key, token)
            val keepsOnset = token.startsWith(key.take(SAFETY_ONSET_LETTERS))
            return if (d <= 1 || (d <= SAFETY_MAX_EDIT_DISTANCE && keepsOnset)) d else null
        }
        if (key.length < MIN_FUZZY_KEY_LENGTH) return null
        val tolerance = if (key.length <= 5) 1 else MAX_EDIT_DISTANCE
        val d = levenshtein(key, token)
        return if (d <= tolerance) d else null
    }

    private fun isWakeWord(token: String): Boolean =
        token in WAKE_WORDS || WAKE_WORDS.any { levenshtein(it, token) <= 1 }

    /** How many leading normalized tokens are filler or the wake word. */
    private fun prefixLength(tokens: List<String>): Int {
        var i = 0
        var wakeSeen = false
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t in FILLER_WORDS -> i++
                !wakeSeen && isWakeWord(t) -> {
                    wakeSeen = true
                    i++
                }
                else -> return i
            }
        }
        return i
    }

    /** The raw text minus the wake-word prefix, keeping the user's punctuation for the VLM (FR-D2). */
    private fun stripPrefix(raw: String): String {
        val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val folded = words.map { TextFold.fold(it).replace(" ", "") }
        val drop = prefixLength(folded)
        return words.drop(drop).joinToString(" ").trim()
    }
}

/**
 * Text folding shared by the grammar and by `Cues.violatesPhrasingRules`: lower-case, accents removed
 * ("qué" → "que", "español" → "espanol"), apostrophes dropped ("what's" → "whats"), everything else that is
 * not a letter or digit becomes a space. Pure JVM (`java.text.Normalizer`).
 */
internal object TextFold {
    private val MARKS = Regex("\\p{M}+")
    private val APOSTROPHES = Regex("['’`]")
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

    fun fold(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val stripped = MARKS.replace(decomposed, "")
        val noApostrophes = APOSTROPHES.replace(stripped, "")
        return NON_WORD.replace(noApostrophes, " ").trim()
    }
}
