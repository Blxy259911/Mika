package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.brain.ExternalBrainSessions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 外部大脑 HTTP 适配器（新增文件，打到 fork 的 core 模块）。
 *
 * 挂在 RemoteTaskApi.ROOT（/api/v1）之下，走 RemoteHost / RemoteAccess
 * 已有的认证与私网限制，不新开端口、不新设鉴权。
 * 由 RemoteEndpoints.handle 在 /api/v1/brain 前缀上分发到这里；
 * 任务路由（/tasks…）保持原样，mika_run_task 不动。
 *
 * 契约（错误风格与 RemoteTaskApi 一致）：
 *  POST /api/v1/brain/sessions            {goal, conversation?} -> 201 {session_id, goal, conversation, tools}
 *  GET  /api/v1/brain/sessions/{id}/tools -> {session_id, tools:[name,...]}
 *  POST /api/v1/brain/sessions/{id}/step  {tool, arguments?}   -> McpToolSession.call 的原样结果
 *  POST /api/v1/brain/sessions/{id}/close -> {session_id, closed:true}
 *  未知字段拒绝；白名单外 tool 为 400；会话不存在为 404；已关闭后调用为 409。
 *  根 GET /api/v1 的 capabilities 必须同时加上 "external_brain"，
 *  客户端以此为准判断补丁版是否在跑，缺失时不得回落到 mika_run_task。
 */
class ExternalBrainApi(
    private val sessions: ExternalBrainSessions,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val ROOT = "/api/v1/brain"
        private val ID = Regex("[A-Za-z0-9_-]+")
    }

    fun handle(req: HttpRequest): HttpResponse = try {
        route(req)
    } catch (e: BrainError) {
        HttpResponse.json(e.status, buildJsonObject {
            putJsonObject("error") { put("code", e.code); put("message", e.message) }
        }.toString())
    }

    private fun route(req: HttpRequest): HttpResponse {
        val segments = req.path.removePrefix(ROOT).trim('/').split('/').filter { it.isNotEmpty() }
        // POST /sessions
        if (segments == listOf("sessions")) {
            if (req.method != "POST") throw BrainError(405, "method_not_allowed", "Method not allowed")
            val body = body(req, setOf("goal", "conversation"))
            val goal = string(body, "goal").orEmpty()
            if (goal.isBlank()) throw BrainError(400, "invalid_argument", "goal must not be blank")
            val session = sessions.open(goal, string(body, "conversation"), clock)
            val tools = sessions.tools(session.id).orEmpty()
            return ok(buildJsonObject {
                put("session_id", session.id)
                put("goal", session.goal)
                put("conversation", session.conversation)
                putJsonArray("tools") { tools.forEach { add(it) } }
            }, 201)
        }
        // /sessions/{id}/tools | /step | /close
        if (segments.size == 3 && segments[0] == "sessions" && ID.matches(segments[1])) {
            val id = segments[1]
            if (!sessions.exists(id)) throw BrainError(404, "not_found", "No brain session with id $id")
            return when (segments[2]) {
                "tools" -> {
                    if (req.method != "GET") throw BrainError(405, "method_not_allowed", "Method not allowed")
                    val tools = sessions.tools(id).orEmpty()
                    ok(buildJsonObject {
                        put("session_id", id)
                        putJsonArray("tools") { tools.forEach { add(it) } }
                    })
                }
                "step" -> {
                    if (req.method != "POST") throw BrainError(405, "method_not_allowed", "Method not allowed")
                    val body = body(req, setOf("tool", "arguments"))
                    val tool = string(body, "tool").orEmpty()
                    if (tool.isBlank()) throw BrainError(400, "invalid_argument", "tool must not be blank")
                    val arguments = (body["arguments"] as? JsonObject)?.toString() ?: "{}"
                    val result = runBlocking { sessions.step(id, tool, arguments) }
                    if (result["ok"]?.let { (it as? JsonPrimitive)?.content == "false" } == true) {
                        val code = (result["error"] as? JsonPrimitive)?.content.orEmpty()
                        when (code) {
                            "tool_not_allowed" -> throw BrainError(400, "invalid_argument", "tool_not_allowed")
                            "session_not_found" -> throw BrainError(404, "not_found", "session_not_found")
                            else -> throw BrainError(400, "invalid_argument", code)
                        }
                    }
                    ok(result)
                }
                "close" -> {
                    if (req.method != "POST") throw BrainError(405, "method_not_allowed", "Method not allowed")
                    body(req, emptySet())
                    val closed = runBlocking { sessions.close(id) }
                    if (!closed) throw BrainError(409, "conflict", "session already closed")
                    ok(buildJsonObject { put("session_id", id); put("closed", true) })
                }
                else -> throw BrainError(404, "not_found", "No such brain endpoint")
            }
        }
        throw BrainError(404, "not_found", "No such brain endpoint")
    }

    private fun body(req: HttpRequest, allowed: Set<String>): JsonObject {
        if (req.header("content-type")?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            throw BrainError(415, "unsupported_media_type", "Content-Type must be application/json")
        }
        val parsed = runCatching { Json.parseToJsonElement(req.body.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: throw BrainError(400, "invalid_argument", "Body must be a JSON object")
        if (parsed.keys.any { it !in allowed }) throw BrainError(400, "invalid_argument", "Unsupported request fields")
        return parsed
    }

    private fun string(body: JsonObject, key: String): String? {
        val value = body[key] ?: return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BrainError(400, "invalid_argument", "$key must be a string")
    }

    private fun ok(body: JsonObject, status: Int = 200) = HttpResponse.json(status, body.toString())

    private class BrainError(val status: Int, val code: String, message: String) : Exception(message)
}
