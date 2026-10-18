package com.vysion.xr.inference

import com.vysion.xr.core.InferenceException
import java.net.URL
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * A cloud vision model that streams its answer about one JPEG (ADR-0010 row "Cloud Describe"). The only
 * implementation is [GeminiCloudDescriber]; tests use a fake. [stream] throws [InferenceException] on any failure,
 * and cancelling its collector closes the connection at once.
 */
interface CloudDescriber {
    /** The model id, for the `vysion.latency` evidence lines. */
    val model: String

    fun stream(jpeg: ByteArray, prompt: String): Flow<String>
}

/**
 * Describe through the Gemini Developer API (ADR-0010; Isaac's choice 2026-10-18: REST with the platform HTTP
 * client, no new dependency, key from `local.properties`). One `streamGenerateContent?alt=sse` request per Describe:
 * the prompt from `PromptLibrary`, the still as an in-memory JPEG, and [PromptLibrary.CLOUD_SPOKEN_STYLE] as the
 * system instruction. The key travels in the `x-goog-api-key` header, never in the URL, so no exception or log line
 * can carry it. Nothing is written to storage; the JPEG and its base64 live only for this request (INV-3).
 *
 * Built only when a key was compiled in ([orNull]); `CloudDescribeEngine` falls back to the headset on any failure.
 *
 * The key is restricted in Google Cloud to this Android app (package + signing-certificate SHA-1, ADR-0010), so every
 * request names the app in [androidPackage] / [androidCert] (`X-Android-Package`, `X-Android-Cert`); without them the
 * API answers 403 "Requests from this Android client application <empty> are blocked" (verified 2026-10-18).
 */
class GeminiCloudDescriber(
    private val apiKey: String,
    override val model: String = MODEL,
    private val androidPackage: String? = null,
    private val androidCert: String? = null,
) : CloudDescriber {

    override fun stream(jpeg: ByteArray, prompt: String): Flow<String> = callbackFlow {
        val body = GeminiWire.requestBody(
            prompt = prompt,
            system = PromptLibrary.CLOUD_SPOKEN_STYLE,
            jpegBase64 = Base64.getEncoder().encodeToString(jpeg),
            maxOutputTokens = MAX_OUTPUT_TOKENS,
            thinkingLevel = THINKING_LEVEL,
        ).toByteArray(Charsets.UTF_8)
        val conn = URL(GeminiWire.streamUrl(model)).openConnection() as java.net.HttpURLConnection // network-fallback: on-device LFM2.5-VL Describe (CloudDescribeEngine)
        launch(Dispatchers.IO) {
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                androidPackage?.let { conn.setRequestProperty("X-Android-Package", it) }
                androidCert?.let { conn.setRequestProperty("X-Android-Cert", it) }
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                if (code != HTTP_OK) {
                    val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw InferenceException("cloud HTTP $code ${GeminiWire.errorStatus(detail)}".trim())
                }
                val events = SseEvents()
                conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val data = events.push(line) ?: continue
                        GeminiWire.textOf(data)?.takeIf { it.isNotEmpty() }?.let { send(it) }
                    }
                }
                events.finish()?.let { data -> GeminiWire.textOf(data)?.takeIf { it.isNotEmpty() }?.let { send(it) } }
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InferenceException) {
                close(e)
            } catch (t: Throwable) {
                // Closing the socket from awaitClose lands here too, after the collector is gone; close() is then a no-op.
                close(InferenceException("cloud describe failed: ${t.javaClass.simpleName}: ${t.message}", t))
            }
        }
        awaitClose { conn.disconnect() } // the collector left (Stop, the headset won, the watchdog): drop the socket
    }

    companion object {
        /**
         * Pinned, never an alias (ai.google.dev/gemini-api/docs/models, 2026-10-18: stable). Chosen over
         * gemini-3.8-flash because it accepts thinking level "minimal" (3.8's lowest is "low"): Describe needs its
         * first words fast more than it needs reasoning.
         */
        const val MODEL: String = "gemini-3.6-flash"

        /** ai.google.dev/gemini-api/docs/thinking: allowed for gemini-3.6-flash and gemini-3.5-flash-lite. */
        const val THINKING_LEVEL: String = "minimal"

        /**
         * Thought tokens count against this cap (docs/thinking). Three sentences are well under it; the prompt, not
         * the cap, keeps the answer short, so the cap never cuts a sentence in half.
         */
        const val MAX_OUTPUT_TOKENS: Int = 400

        const val CONNECT_TIMEOUT_MS: Int = 3_000

        /** Longest silence between two bytes of the stream; the whole answer is bounded by `VLM_TIMEOUT_MS`. */
        const val READ_TIMEOUT_MS: Int = 10_000

        private const val HTTP_OK: Int = 200

        /** `null` when no key was built in (`GEMINI_API_KEY` in `local.properties`): cloud Describe is "not set up". */
        fun orNull(apiKey: String, androidPackage: String? = null, androidCert: String? = null): GeminiCloudDescriber? =
            apiKey.trim().takeIf { it.isNotEmpty() }?.let {
                GeminiCloudDescriber(it, androidPackage = androidPackage, androidCert = androidCert)
            }
    }
}

/** The Gemini REST wire format, pure Kotlin so it is unit-tested without a network or an Android runtime. */
internal object GeminiWire {
    private const val BASE: String = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun streamUrl(model: String): String = "$BASE$model:streamGenerateContent?alt=sse"

    /** One user turn: the image first, then the prompt (the order the Gemini docs use for image prompts). */
    fun requestBody(prompt: String, system: String, jpegBase64: String, maxOutputTokens: Int, thinkingLevel: String): String =
        """{"systemInstruction":{"parts":[{"text":${quote(system)}}]},""" +
            """"contents":[{"role":"user","parts":[{"inlineData":{"mimeType":"image/jpeg","data":${quote(jpegBase64)}}},""" +
            """{"text":${quote(prompt)}}]}],""" +
            """"generationConfig":{"maxOutputTokens":$maxOutputTokens,"thinkingConfig":{"thinkingLevel":${quote(thinkingLevel)}}}}"""

    /**
     * The answer text in one streamed `GenerateContentResponse`: the first candidate's parts, thought summaries
     * skipped. `null` when the chunk has no candidate (usage-only chunk, or a blocked prompt). An `error` object in
     * the stream throws, so the engine falls back to the headset.
     */
    fun textOf(json: String): String? {
        val root = runCatching { LockJson.parse(json) }.getOrNull() as? Map<*, *>
            ?: throw InferenceException("cloud sent a malformed chunk")
        (root["error"] as? Map<*, *>)?.let { throw InferenceException("cloud error ${it["status"] ?: it["code"]}") }
        val candidate = (root["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
        val parts = (candidate["content"] as? Map<*, *>)?.get("parts") as? List<*> ?: return null
        return parts.mapNotNull { part ->
            (part as? Map<*, *>)?.takeIf { it["thought"] != true }?.get("text") as? String
        }.joinToString("")
    }

    /** `status` of an error body (`{"error":{"code":403,"status":"PERMISSION_DENIED",…}}`); never the full message. */
    fun errorStatus(body: String): String =
        ((runCatching { LockJson.parse(body) }.getOrNull() as? Map<*, *>)?.get("error") as? Map<*, *>)
            ?.get("status") as? String ?: ""

    /** JSON string literal with RFC 8259 escapes. */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(Locale.ROOT, c.code)) else append(c)
            }
        }
        append('"')
    }
}

/**
 * Server-sent events, line by line (html.spec.whatwg.org "event stream"): `data:` lines are joined with `\n` and
 * an empty line ends the event. Other fields and comments are ignored.
 */
internal class SseEvents {
    private val data = StringBuilder()
    private var hasData = false

    /** Feed one line; returns the event's data when [line] ends an event. */
    fun push(line: String): String? {
        if (line.isEmpty()) return finish()
        if (line.startsWith("data:")) {
            if (hasData) data.append('\n')
            data.append(line.removePrefix("data:").removePrefix(" "))
            hasData = true
        }
        return null
    }

    /** The pending event at end of stream, if any. */
    fun finish(): String? {
        if (!hasData) return null
        val event = data.toString()
        data.setLength(0)
        hasData = false
        return event
    }
}
