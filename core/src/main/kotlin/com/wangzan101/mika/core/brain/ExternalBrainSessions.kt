package com.wangzan101.mika.core.brain

import com.wangzan101.mika.core.mcp.McpServer
import com.wangzan101.mika.core.mcp.McpToolSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * 外部大脑会话注册表（新增文件，打到 fork 的 core 模块）。
 *
 * 一个会话 = 一次 McpServer.openToolSession(threadId)。
 * McpToolSession 本身已经是无模型的：它不经过 AgentLoop / ModelCaller，
 * 工具目录在开局冻结，所有调用都走 ActionExecutor。
 * 这里只做四件它原来没有的事：
 *  1. 给会话一个外部可见的 id、goal、步数，并集中登记/关闭；
 *  2. 把工具表过滤到手机/网页屏工具（phone_*/web_*），不放行
 *     模型循环专用工具（ask_user、hand_to_user、end_not_done、
 *     memory_save、skill_save、settings_set 等）；
 *  3. 让“会话不存在 / 已关闭”成为可区分的错误，而不是静默重开。
 *  4. 让接口与助手无关：决策层可以是 Muse、Grok、其他 Bot 或任何
 *     能发 HTTP JSON 的个人智能助手。调用方用可选的 client 字段
 *     自报身份（如 "muse"、"grok"、"my-bot"），仅用于区分会话归属，
 *     不参与鉴权、不限制助手种类。多个助手的会话并发存在、互不
 *     干扰：每个会话有独立 UUID、独立冻结工具表、独立步数，关一
 *     个不影响另一个。
 *
 * 注意：McpServer 的 assemble 工厂由 App 侧传入（见 APP_WIRING.md），
 * 本文件不假设 TaskRun 的具体装配签名。
 */
class ExternalBrainSessions(private val server: McpServer) {

    data class Session(
        val id: String,
        val goal: String,
        val conversation: String,
        /** 调用方自报的助手标识，可为空；仅用于区分，不做鉴权。 */
        val client: String?,
        val createdAtMs: Long,
        val session: McpToolSession,
    ) {
        var steps: Int = 0
            private set

        fun noteStep() {
            steps++
        }
    }

    sealed interface OpenResult {
        data class Opened(val session: Session) : OpenResult
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()

    fun open(goal: String, conversation: String?, client: String?, clock: () -> Long): Session {
        require(goal.isNotBlank()) { "goal must not be blank" }
        val thread = conversation?.takeIf { it.isNotBlank() } ?: "brain-${UUID.randomUUID()}"
        val inner = server.openToolSession(thread)
        val session = Session(
            id = UUID.randomUUID().toString(),
            goal = goal,
            conversation = thread,
            client = client?.takeIf { it.isNotBlank() },
            createdAtMs = clock(),
            session = inner,
        )
        synchronized(lock) { sessions[session.id] = session }
        return session
    }

    /** 本会话冻结后、且在白名单里的工具名。调用方必须以这张表为准，不要凭记忆编工具名。 */
    fun tools(id: String): List<String>? {
        val session = synchronized(lock) { sessions[id] } ?: return null
        return session.session.tools()
            .map { it.name }
            .filter { it.startsWith("phone_") || it.startsWith("web_") }
    }

    /** 执行一步。返回 McpToolSession.call 的原样 JsonObject；白名单外直接返回 ok=false，不执行。 */
    suspend fun step(id: String, tool: String, argumentsJson: String): JsonObject {
        val session = synchronized(lock) { sessions[id] }
            ?: return buildJsonObject { put("ok", false); put("error", "session_not_found") }
        if (tools(id)?.contains(tool) != true) {
            return buildJsonObject { put("ok", false); put("error", "tool_not_allowed") }
        }
        val result = session.session.call(tool, argumentsJson)
        session.noteStep()
        return result
    }

    suspend fun close(id: String): Boolean {
        val session = synchronized(lock) { sessions.remove(id) } ?: return false
        session.session.close()
        return true
    }

    fun exists(id: String): Boolean = synchronized(lock) { sessions.containsKey(id) }

    /** 当前所有未关闭会话的快照（不暴露内部 McpToolSession）。多助手并发时用它区分归属。 */
    fun list(): List<Session> = synchronized(lock) { sessions.values.toList() }
}
