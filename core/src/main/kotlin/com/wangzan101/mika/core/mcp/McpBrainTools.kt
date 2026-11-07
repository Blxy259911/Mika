package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.brain.ExternalBrainSessions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 外部大脑 MCP 工具（新增文件，打到 fork 的 core 模块）。
 *
 * 与 McpTaskTools 平行存在，不改动原有 mika_run_task 等 7 个工具。
 * 关键区别必须写进工具说明，不能含糊：
 *  - mika_run_task：整包交给手机上的 Mika 模型做规划和执行；
 *  - mika_brain_*：手机端不做规划、不调用本地模型，只按外部大脑
 *    一步步给的 tool + arguments 执行（经 McpToolSession → ActionExecutor）。
 *
 * 安全继承：付款、确认、手机接管仍不能由远程调用批准；
 * step 返回 waiting_on_phone / approval_unavailable 时，调用方必须停下
 * 交给手机上的人处理，不得换工具名重试。
 */
class McpBrainTools(private val sessions: ExternalBrainSessions) {

    companion object {
        const val OPEN = "mika_brain_open"
        const val TOOLS = "mika_brain_tools"
        const val STEP = "mika_brain_step"
        const val CLOSE = "mika_brain_close"
    }

    fun definitions(): JsonArray = buildJsonArray {
        add(tool(OPEN, "Open an external-brain session on the Android phone. " +
            "The phone does NOT plan and does NOT call its local model in this session; " +
            "the caller decides every step and executes it with mika_brain_step. " +
            "Only phone_*/web_* screen tools are available in the session. " +
            "The caller may be any personal assistant (Muse, Grok, another bot); " +
            "pass its name as optional client so concurrent sessions stay distinguishable.",
            required = listOf("goal")) {
            putJsonObject("goal") { put("type", "string") }
            putJsonObject("conversation") { put("type", "string") }
            putJsonObject("client") { put("type", "string") }
        })
        add(tool(TOOLS, "List the frozen, allow-listed screen tools for an external-brain session. " +
            "Use only names from this list in mika_brain_step.",
            required = listOf("session_id")) { sessionIdProp() })
        add(tool(STEP, "Execute exactly one screen step in an external-brain session and return " +
            "its observation. One step at a time; wait for the observation before deciding the next step. " +
            "Payments, confirmations and phone handover cannot be approved here; " +
            "if the result says waiting_on_phone, stop and hand it to the person on the phone.",
            required = listOf("session_id", "tool")) {
            sessionIdProp()
            putJsonObject("tool") { put("type", "string") }
            putJsonObject("arguments") { put("type", "object") }
        })
        add(tool(CLOSE, "Close an external-brain session. Steps after close are rejected.",
            required = listOf("session_id")) { sessionIdProp() })
    }

    fun call(name: String, args: JsonObject): JsonObject = when (name) {
        OPEN -> {
            val goal = string(args, "goal").orEmpty()
            if (goal.isBlank()) error("goal must not be blank")
            val session = sessions.open(goal, optString(args, "conversation"), optString(args, "client")) { System.currentTimeMillis() }
            buildJsonObject {
                put("session_id", session.id)
                put("goal", session.goal)
                put("conversation", session.conversation)
                if (session.client != null) put("client", session.client)
                putJsonArray("tools") { sessions.tools(session.id).orEmpty().forEach { add(it) } }
            }
        }
        TOOLS -> {
            val id = string(args, "session_id").orEmpty()
            val tools = sessions.tools(id) ?: error("No brain session with id $id")
            buildJsonObject {
                put("session_id", id)
                putJsonArray("tools") { tools.forEach { add(it) } }
            }
        }
        STEP -> runBlocking {
            val id = string(args, "session_id").orEmpty()
            val tool = string(args, "tool").orEmpty()
            val arguments = (args["arguments"] as? JsonObject)?.toString() ?: "{}"
            sessions.step(id, tool, arguments)
        }
        CLOSE -> runBlocking {
            val id = string(args, "session_id").orEmpty()
            val closed = sessions.close(id)
            buildJsonObject { put("session_id", id); put("closed", closed) }
        }
        else -> error("Unknown tool: $name")
    }

    private fun tool(name: String, description: String, required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties", props)
                putJsonArray("required") { required.forEach { add(it) } }
                put("additionalProperties", false)
            }
            putJsonObject("annotations") { put("readOnlyHint", name == TOOLS); put("openWorldHint", name == STEP) }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.sessionIdProp() =
        putJsonObject("session_id") { put("type", "string") }

    private fun string(args: JsonObject, key: String): String? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else error("$key must be a string")
        else -> error("$key must be a string")
    }

    private fun optString(args: JsonObject, key: String) = string(args, key)
}
