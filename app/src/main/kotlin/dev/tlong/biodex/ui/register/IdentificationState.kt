package dev.tlong.biodex.ui.register

import dev.tlong.biodex.data.identify.ResolvedCandidate
import dev.tlong.biodex.data.identify.ScoreKind
import dev.tlong.biodex.data.identify.capReachedReason
import dev.tlong.biodex.domain.Kingdom

/**
 * The candidate panel's whole state, and the sentences under the Identify button (M31, M34,
 * M38). Pure, like the rest of `RegisterState.kt`, so the JVM suite pins every row of §5.1
 * with no device.
 */
sealed interface IdentificationState {

    /** Nothing has been asked. The panel is absent, not empty. */
    data object Idle : IdentificationState

    data class Running(val provider: String) : IdentificationState

    data class Done(
        val provider: String,
        val scoreKind: ScoreKind,
        val candidates: List<ResolvedCandidate>,
        /** Names GBIF would not stand behind. The heading says how many (M32). */
        val dropped: Int,
    ) : IdentificationState

    /** The service answered and recognised nothing. An ordinary outcome, not an error (M38). */
    data class NoCandidates(val provider: String) : IdentificationState

    /** The only outcome styled as an error: the app could not ask. */
    data class Failed(val reason: String) : IdentificationState
}

/**
 * The heading above the panel. It names the provider every time, because the claim the app is
 * making is "Pl@ntNet suggested these" and never "BioDex thinks" (M34, D2). The dropped count
 * is said out loud rather than hidden, so a photo that produced five guesses and two survivors
 * does not read as a service that only had two ideas.
 */
fun candidatePanelHeading(provider: String, shown: Int, dropped: Int): String {
    val head = "$provider suggests — $shown " + if (shown == 1) "candidate" else "candidates"
    if (dropped == 0) return head
    return "$head, $dropped " +
        (if (dropped == 1) "unrecognised name" else "unrecognised names") + " dropped"
}

/**
 * The line under the panel that says whose number the percentage is (M34, D22).
 *
 * [ScoreKind.SELF_REPORTED] renders no number at all — a language model's "confidence" is a
 * token it emitted, not a probability, and showing it as a percentage would launder one into
 * the other. Nothing renders that branch in v3; it is here so a future provider cannot be
 * added without meeting it.
 */
fun scoreCaption(provider: String, kind: ScoreKind): String = when (kind) {
    ScoreKind.CALIBRATED -> "Scores are $provider's classifier confidence."
    ScoreKind.SELF_REPORTED -> "$provider ranked these; it reports no confidence."
}

/** The percentage on a candidate row, or null when the provider reports no probability. */
fun scoreLabel(score: Double?, kind: ScoreKind): String? {
    if (kind != ScoreKind.CALIBRATED || score == null) return null
    return "${Math.round(score * 100)}%"
}

/**
 * §5.1's table, as one function. Null means the action is live.
 *
 * The order is the order the user can act on: a missing key and a spent cap are things they can
 * do something about right now, and being offline is not, so the two actionable ones are not
 * hidden behind it.
 */
fun identifyDisabledReason(
    provider: String,
    online: Boolean,
    hasKey: Boolean,
    identificationsUsed: Int,
    cap: Int,
    running: Boolean,
): String? = when {
    running -> "Identifying…"
    !hasKey -> "Add a $provider key in Settings to identify plants"
    identificationsUsed >= cap -> capReachedReason(identificationsUsed, cap)
    !online -> "Identify needs a connection"
    else -> null
}

/**
 * Which kingdom the Identify button is being offered *for* (§5.1, D19).
 *
 * With no kingdom chip (D19 cut it), the context is the selected species' kingdom — and when
 * nothing is selected it is `PLANT`, deliberately. Identification is *how* the user finds out
 * what they are looking at, so requiring them to select a plant before they may identify one
 * would make the feature useless on exactly the walk it exists for. Selecting an animal or a
 * fungus hides the button again, because the registry has no provider for them.
 */
fun identifyContextKingdom(selectedKingdom: Kingdom?): Kingdom = selectedKingdom ?: Kingdom.PLANT
