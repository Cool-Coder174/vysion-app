package com.vysion.xr.inference

import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.PromptSet
import java.util.Locale

/**
 * The only place prompt text may appear (SRS §7, AGENTS.md §5, CONTRIBUTING "prompts only in
 * `inference/PromptLibrary.kt`"). Consumed by R1 `ModeController` through `InferenceEngine.describe` (core);
 * quoted in the README and in `docs/model-plan.md`.
 *
 * - Contract provided: `core.PromptSet` through [LibraryPromptSet] (`docs/contracts.md` row "`PromptSet` (seam in
 *   `core`)", gate 4). `PromptLibrary` itself is package-internal reading: no other package may import it.
 * - FR ids: FR-T1 ([READ]), FR-D1 ([DESCRIBE]), FR-N3 ([DOOR]), FR-N6 ([ARRIVAL]),
 *   FR-T4 ([TRANSLATE_ES]), FR-D2 ([followUp]), FR-D3 ([DESCRIBE_BRIEF], [DESCRIBE_DETAILED]), FR-G6 ([forLocale]).
 * - SRS §8 R2 due: "PromptLibrary.kt with the four prompts in section 7; tune wording against 10 real photos
 *   from the venue. Sat 19:00".
 * - Owner: R2 ML / ExecuTorch.
 * - API: none (pure Kotlin). The prompts are passed verbatim to `LlmModule.generate(prompt, seqLen, cb, echo=false)`
 *   (`docs/stack.md` §3-R2); the chat template, if the chosen VLM needs one, is applied by the engine, not here.
 * - Assumption: A-13 (the chosen VLM answers an image prompt on the headset). Wording is tuned only by commits to
 *   this file (ADR-0001: prompt wording is product behavior).
 *
 * Nothing here is spoken: every spoken string lives in `voice/Cues.kt` (R4). The VLM's answer text is spoken by
 * R4's `Speaker` as-is (INV-4).
 */
object PromptLibrary {

    // ── SRS §7, verbatim (P0) ────────────────────────────────────────────────────────────────────

    /** FR-T1 Text Reader: read verbatim. The "No text found." sentinel is [NO_TEXT_SENTINEL]. */
    const val READ: String =
        "Read all text in this image exactly as written, top to bottom, left to right. Output only the text. " +
            "If there is no text, say: No text found."

    /** FR-D1 Audio Description: 3 sentences max, hazards first, left to right (SRS §3 rule 3). */
    const val DESCRIBE: String =
        "Describe this scene for someone who cannot see it, in at most three sentences. Hazards first, then what " +
            "is on the left, center, and right. Do not guess."

    /**
     * FR-N3 door one-shot. The answer is parsed by `platform.DoorVote` (`ModeController`): exactly one of
     * left/center/right/none and no negation, otherwise no door (RK-6). It is never spoken (R4 speaks `Cues`).
     * Tune this prompt against `DoorVote.parse`, not against [parseDoorAnswer].
     */
    const val DOOR: String = "Is there a door in this image? Answer with one word: left, center, right, or none."

    /** FR-N6 arrival check: handle side and open/closed, one sentence. */
    const val ARRIVAL: String = "Is this door open or closed, and which side is the handle on? One short sentence."

    // ── P1 variants (docs/model-plan.md "P1 variants") ──────────────────────────────────────────

    /** FR-D3 "brief": one sentence, hazards still first. */
    const val DESCRIBE_BRIEF: String =
        "Describe this scene for someone who cannot see it in one short sentence. Hazards first. Do not guess."

    /** FR-D3 "detailed": up to six sentences, same order, plus any visible text. */
    const val DESCRIBE_DETAILED: String =
        "Describe this scene for someone who cannot see it, in at most six sentences. Hazards first, then what " +
            "is on the left, center, and right, then any visible text or signs. Do not guess."

    /** FR-T4 "in Spanish": re-prompt on the same frame (the engine keeps the prefill, FR-D2 / RK-2 "prefill once"). */
    const val TRANSLATE_ES: String = "Translate the text you read into Spanish; output only the translation."

    /**
     * The exact first sentence [READ] asks the model to produce when the frame has no text. Today the controller
     * speaks the model's answer as is, so "No text found." is heard in the model's own words (INV-4 allows VLM
     * text). Not reachable from `platform`: `PromptSet` has no sentinel and `ModePhrasebook` has no
     * "no text found" line (there is no `Cues.noTextFound`). The additive change that would add both is an open
     * P1 proposal in `docs/contracts.md` "Open contract proposals"; until R5 merges it, use this only in tests
     * and benchmarks.
     */
    const val NO_TEXT_SENTINEL: String = "No text found"

    /** Suffix appended by [forLocale] for Spanish output (FR-G6). English prompts get no suffix. */
    const val SPANISH_SUFFIX: String = " Respond in Spanish."

    /** Every fixed prompt, keyed by a stable name; used by the fake's canned answers and by `scripts/benchmark.sh`. */
    val ALL: Map<String, String> = linkedMapOf(
        "READ" to READ,
        "DESCRIBE" to DESCRIBE,
        "DOOR" to DOOR,
        "ARRIVAL" to ARRIVAL,
        "DESCRIBE_BRIEF" to DESCRIBE_BRIEF,
        "DESCRIBE_DETAILED" to DESCRIBE_DETAILED,
        "TRANSLATE_ES" to TRANSLATE_ES,
    )

    /**
     * FR-D2 follow-up question ("what's on the table?") against the same frame. The question is the user's words
     * from `Command.FollowUp(question)` (R4); whitespace is collapsed, nothing else is rewritten. The model is
     * told to answer from the image only so it does not invent (SRS §3 rule 5 "no guessing").
     */
    fun followUp(question: String): String {
        val q = question.trim().replace(WHITESPACE, " ")
        return "Answer this question about the image in at most two sentences, using only what is visible. " +
            "Do not guess. Question: $q"
    }

    /**
     * FR-G6 Spanish output in all three modes. For a Spanish [locale] the prompt gets [SPANISH_SUFFIX]; any other
     * language returns [prompt] unchanged. Two prompts are never localized: [DOOR], whose one-word answer is
     * parsed by `platform.DoorVote` (the allowlist is English), and [TRANSLATE_ES], which already targets Spanish.
     */
    fun forLocale(prompt: String, locale: Locale): String {
        if (locale.language != SPANISH) return prompt
        if (prompt == DOOR || prompt == TRANSLATE_ES) return prompt
        if (prompt.endsWith(SPANISH_SUFFIX)) return prompt
        return prompt + SPANISH_SUFFIX
    }

    /**
     * REFERENCE ONLY, not used by the app: the controller's parser is `platform.DoorVote.parse` (`ModeController`),
     * which is stricter (exactly one distinct side word, no negation, else no door; RK-6). Kept for offline prompt
     * experiments and benchmarks; do not tune [DOOR] against it ("Left, slightly toward the center" is LEFT here
     * and no door in `DoorVote`).
     *
     * The text is split into words (letters only, case-insensitive) and the FIRST word that is one of `left`,
     * `center`, `right`, `none` decides. Anything else, including an empty answer or a sentence that mentions no
     * side, is [HorizontalBearing.NONE].
     */
    fun parseDoorAnswer(text: String): HorizontalBearing {
        for (word in WORD.findAll(text)) {
            val bearing = DOOR_WORDS[word.value.lowercase(Locale.ROOT)]
            if (bearing != null) return bearing
        }
        return HorizontalBearing.NONE
    }

    private const val SPANISH: String = "es"
    private val WHITESPACE: Regex = Regex("\\s+")
    private val WORD: Regex = Regex("[A-Za-z]+")
    private val DOOR_WORDS: Map<String, HorizontalBearing> = mapOf(
        "left" to HorizontalBearing.LEFT,
        "center" to HorizontalBearing.CENTER,
        "right" to HorizontalBearing.RIGHT,
        "none" to HorizontalBearing.NONE,
    )
}

/**
 * [PromptSet] backed by [PromptLibrary]: what `AppGraph` passes to `ModeController(prompts = …)`, so the
 * controller never imports `inference` (ADR-0005) and prompt text still lives only in this file (SRS §7).
 */
object LibraryPromptSet : PromptSet {
    override val read: String get() = PromptLibrary.READ
    override val describe: String get() = PromptLibrary.DESCRIBE
    override val door: String get() = PromptLibrary.DOOR
    override val arrival: String get() = PromptLibrary.ARRIVAL
    override val translateEs: String get() = PromptLibrary.TRANSLATE_ES
    override val describeBrief: String get() = PromptLibrary.DESCRIBE_BRIEF
    override val describeDetailed: String get() = PromptLibrary.DESCRIBE_DETAILED
    override fun followUp(question: String): String = PromptLibrary.followUp(question)
    override fun forLocale(prompt: String, locale: Locale): String = PromptLibrary.forLocale(prompt, locale)
    override val noTextSentinel: String get() = PromptLibrary.NO_TEXT_SENTINEL
}
