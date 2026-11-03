package com.wangzan101.mika.core.brain

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.mcp.McpServer
import com.wangzan101.mika.core.mcp.McpTaskHost
import com.wangzan101.mika.core.remote.ExternalBrainApi
import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Brain core tests, using the same FakeScreen pattern as McpServerTest.
 * These verify the session layer only: they do NOT prove the app-layer
 * phone wiring (McpRuntime -> RemoteEndpoints), which is a separate,
 * still-open step documented in docs/muse-brain.md.
 */
class ExternalBrainSessionsTest {

    private fun registry(): ExternalBrainSessions {
        val server = McpServer({ spec -> toolDispatcher(FakeScreen(), runSpec = spec) },
            McpTaskHost { error("not used") })
        return ExternalBrainSessions(server)
    }

    private fun req(method: String, path: String, body: String = "") = HttpRequest(
        method, path, mapOf("content-type" to "application/json"),
        body.toByteArray(Charsets.UTF_8), InetAddress.getLoopbackAddress())

    private fun body(respBody: ByteArray): JsonObject =
        Json.parseToJsonElement(respBody.toString(Charsets.UTF_8)).jsonObject

    @Test fun toolsArePhoneOrWebOnly() {
        val sessions = registry()
        val s = sessions.open("test goal", null, "grok") { 1L }
        assertEquals("grok", s.client)
        val tools = sessions.tools(s.id).orEmpty()
        assertTrue(tools.isNotEmpty())
        assertTrue(tools.all { it.startsWith("phone_") || it.startsWith("web_") })
        assertFalse(tools.any { it in setOf("ask_user", "hand_to_user", "end_not_done", "memory_save", "skill_save", "settings_set") })
    }

    @Test fun unknownToolIsRejectedWithoutExecution() = runBlocking {
        val sessions = registry()
        val s = sessions.open("test goal", null, "muse") { 1L }
        val result = sessions.step(s.id, "ask_user", "{}")
        assertEquals("false", result["ok"].toString())
        assertEquals("tool_not_allowed", (result["error"] as JsonPrimitive).content)
        // The session is still usable afterwards.
        val firstTool = sessions.tools(s.id)!!.first()
        val ok = sessions.step(s.id, firstTool, "{}")
        assertFalse((ok["error"] as? JsonPrimitive)?.content == "tool_not_allowed")
    }

    @Test fun concurrentAssistantSessionsAreIndependent() = runBlocking {
        val sessions = registry()
        val muse = sessions.open("muse goal", null, "muse") { 1L }
        val grok = sessions.open("grok goal", null, "grok") { 1L }
        assertEquals(2, sessions.list().size)
        assertTrue(sessions.close(muse.id))
        assertEquals(1, sessions.list().size)
        assertEquals("grok", sessions.list().first().client)
        val gone = sessions.step(muse.id, "phone_observe", "{}")
        assertEquals("session_not_found", (gone["error"] as JsonPrimitive).content)
        val firstTool = sessions.tools(grok.id)!!.first()
        val ok = sessions.step(grok.id, firstTool, "{}")
        assertFalse((ok["error"] as? JsonPrimitive)?.content == "session_not_found")
    }

    @Test fun httpApiOpenListCloseAndRejectsUnknownFields() {
        val api = ExternalBrainApi(registry()) { 1L }
        val opened = api.handle(req("POST", "/api/v1/brain/sessions",
            """{"goal":"打开设置","client":"grok"}"""))
        assertEquals(201, opened.status)
        val openBody = body(opened.body)
        assertEquals("grok", openBody["client"]!!.jsonPrimitive.content)
        val id = openBody["session_id"]!!.jsonPrimitive.content

        val listed = api.handle(req("GET", "/api/v1/brain/sessions"))
        assertEquals(200, listed.status)
        val sessions = body(listed.body)["sessions"]!!.jsonArray
        assertTrue(sessions.any { it.jsonObject["session_id"]!!.jsonPrimitive.content == id })

        val bad = api.handle(req("POST", "/api/v1/brain/sessions",
            """{"goal":"x","surprise":1}"""))
        assertEquals(400, bad.status)

        val closed = api.handle(req("POST", "/api/v1/brain/sessions/$id/close", "{}"))
        assertEquals(200, closed.status)
        assertEquals(true, body(closed.body)["closed"]!!.jsonPrimitive.boolean)

        val afterClose = api.handle(req("GET", "/api/v1/brain/sessions/$id/tools"))
        assertEquals(404, afterClose.status)
    }
}
