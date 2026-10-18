package com.vysion.xr.inference

import com.vysion.xr.core.InferenceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The Gemini REST wire format for cloud Describe (ADR-0010), checked without a network. */
class GeminiWireTest {

    @Test
    fun `the request is valid JSON with the image, the prompt, the spoken style and minimal thinking`() {
        val body = GeminiWire.requestBody(
            prompt = "Describe \"this\"\nscene. Respond in Spanish.",
            system = PromptLibrary.CLOUD_SPOKEN_STYLE,
            jpegBase64 = "AAEC",
            maxOutputTokens = 400,
            thinkingLevel = "minimal",
        )
        val root = LockJson.parse(body) as Map<*, *>
        val system = ((root["systemInstruction"] as Map<*, *>)["parts"] as List<*>).single() as Map<*, *>
        assertEquals(PromptLibrary.CLOUD_SPOKEN_STYLE, system["text"])
        val turn = (root["contents"] as List<*>).single() as Map<*, *>
        assertEquals("user", turn["role"])
        val parts = turn["parts"] as List<*>
        val image = (parts[0] as Map<*, *>)["inlineData"] as Map<*, *>
        assertEquals("image/jpeg", image["mimeType"])
        assertEquals("AAEC", image["data"])
        assertEquals("quotes and newlines survive the escaping", "Describe \"this\"\nscene. Respond in Spanish.", (parts[1] as Map<*, *>)["text"])
        val config = root["generationConfig"] as Map<*, *>
        assertEquals(400, (config["maxOutputTokens"] as Number).toInt())
        assertEquals("minimal", (config["thinkingConfig"] as Map<*, *>)["thinkingLevel"])
    }

    @Test
    fun `the key never goes in the URL`() {
        val url = GeminiWire.streamUrl(GeminiCloudDescriber.MODEL)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:streamGenerateContent?alt=sse",
            url,
        )
        assertFalse(url.contains("key="))
    }

    @Test
    fun `quote escapes control characters`() {
        assertEquals("\"a\\\\b\\t\\u0001\"", GeminiWire.quote("a\\b\t\u0001"))
        assertEquals("\"señal ¿qué?\"", GeminiWire.quote("señal ¿qué?"))
    }

    @Test
    fun `a streamed chunk gives the first candidate's text and skips thought parts`() {
        val chunk = """{"candidates":[{"content":{"role":"model","parts":[{"text":"plan","thought":true},""" +
            """{"text":"A hallway. "},{"text":"A chair."}]},"index":0}],"modelVersion":"gemini-3.6-flash"}"""
        assertEquals("A hallway. A chair.", GeminiWire.textOf(chunk))
    }

    @Test
    fun `a chunk without candidates has no text`() {
        assertNull(GeminiWire.textOf("""{"usageMetadata":{"promptTokenCount":300}}"""))
        assertNull(GeminiWire.textOf("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
    }

    @Test
    fun `an error in the stream or a malformed chunk throws, so the headset answers`() {
        for (bad in listOf("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}""", "{not json")) {
            try {
                GeminiWire.textOf(bad)
                fail("expected InferenceException for $bad")
            } catch (e: InferenceException) {
                // expected
            }
        }
    }

    @Test
    fun `an error body gives its status only`() {
        assertEquals(
            "PERMISSION_DENIED",
            GeminiWire.errorStatus("""{"error":{"code":403,"message":"API key not valid","status":"PERMISSION_DENIED"}}"""),
        )
        assertEquals("", GeminiWire.errorStatus("<html>bad gateway</html>"))
    }

    @Test
    fun `server-sent events join data lines and end at a blank line`() {
        val events = SseEvents()
        assertNull(events.push(": keep-alive comment"))
        assertNull(events.push("data: {\"a\":"))
        assertNull(events.push("data: 1}"))
        assertEquals("{\"a\":\n1}", events.push(""))
        assertNull("a second blank line is not an event", events.push(""))
        assertNull(events.push("data:{\"b\":2}"))
        assertEquals("the last event without a trailing blank line", "{\"b\":2}", events.finish())
        assertNull(events.finish())
    }

    @Test
    fun `no key means no cloud describer`() {
        assertNull(GeminiCloudDescriber.orNull(""))
        assertNull(GeminiCloudDescriber.orNull("   "))
        assertEquals(GeminiCloudDescriber.MODEL, GeminiCloudDescriber.orNull("k")?.model)
    }

    @Test
    fun `a text-only request has the prompt and no image part`() {
        val body = GeminiWire.requestBody("Where am I?", PromptLibrary.WHERE_AM_I_STYLE, jpegBase64 = null, maxOutputTokens = 400, thinkingLevel = "minimal")
        val root = LockJson.parse(body) as Map<*, *>
        val parts = ((root["contents"] as List<*>).single() as Map<*, *>)["parts"] as List<*>
        assertEquals(listOf(mapOf("text" to "Where am I?")), parts)
        val system = ((root["systemInstruction"] as Map<*, *>)["parts"] as List<*>).single() as Map<*, *>
        assertEquals(PromptLibrary.WHERE_AM_I_STYLE, system["text"])
    }
}
