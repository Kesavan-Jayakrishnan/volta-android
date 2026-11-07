package dev.irshadm.volta.service.smart

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini Nano, on the phone (ML Kit GenAI Prompt API via AICore): free, offline, private. It only
 * rewords facts the app already decided on; every caller has a plain-text fallback, and Android
 * may refuse background generation, so this is best-effort by design.
 */
object Narrator {
    enum class State { Ready, Downloadable, Downloading, Unavailable }

    private val model by lazy { runCatching { Generation.getClient() }.getOrNull() }

    suspend fun state(): State = runCatching {
        when (model?.checkStatus()) {
            FeatureStatus.AVAILABLE -> State.Ready
            FeatureStatus.DOWNLOADABLE -> State.Downloadable
            FeatureStatus.DOWNLOADING -> State.Downloading
            else -> State.Unavailable
        }
    }.getOrDefault(State.Unavailable)

    /** Starts the one-time model download (foreground only); completes when done or failed. */
    suspend fun download() {
        runCatching { model?.download()?.collect { } }
    }

    private suspend fun ask(prompt: String, timeoutMs: Long = 6_000): String? {
        if (state() != State.Ready) return null
        return withTimeoutOrNull(timeoutMs) {
            runCatching { model?.generateContent(prompt)?.candidates?.firstOrNull()?.text?.trim() }.getOrNull()
        }?.takeIf { it.isNotBlank() }
    }

    /** One friendly line for the pre-cool sheet, e.g. "Cabin's 41° — you usually head home for lunch about now." */
    suspend fun precoolLine(inside: Int, outside: Int, place: String?, usual: String?): String? = ask(
        """
        Write ONE short, friendly sentence (max 22 words) suggesting the driver pre-cool their parked electric car before walking to it.
        Use only these facts, do not invent anything, no emojis, no quotes:
        - cabin temperature: $inside °C
        - outside temperature: $outside °C
        ${place?.let { "- they are leaving: $it" } ?: ""}
        ${usual?.let { "- this matches their usual routine: $it" } ?: ""}
        """.trimIndent(),
    )?.lineSequence()?.firstOrNull()?.removeSurrounding("\"")

    /** A 2–3 sentence weekly note about what smart pre-cool learned. */
    suspend fun weeklyNote(facts: List<String>): String? = if (facts.isEmpty()) {
        null
    } else {
        ask(
            "Summarise these facts about a driver's smart pre-cool assistant in 2-3 short, plain sentences, second person, no emojis, no invented numbers:\n" +
                facts.joinToString("\n") { "- $it" },
            timeoutMs = 10_000,
        )
    }

    /**
     * Why the app's suggested route is the better pick, in one sentence. The app chooses the route; the model only
     * explains it from the facts given.
     */
    suspend fun routeReason(pick: String, facts: List<String>): String? = ask(
        """
        An electric-car trip planner suggests the route "$pick". In ONE short sentence (max 25 words), tell the driver why it is the better choice.
        Compare it with the other routes using only these facts. No emojis, no quotes, do not invent numbers or road conditions:
        ${facts.joinToString("\n") { "- $it" }}
        """.trimIndent(),
    )?.lineSequence()?.firstOrNull()?.removeSurrounding("\"")
}
