package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.brain.ExternalBrainSessions
import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.mcp.McpRpc
import com.wangzan101.mika.core.mcp.McpTaskTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/** HTTP 与 MCP 每次请求都从同一个已认证归属构造任务服务,避免某条协议路径绕过任务、文件或导出权限。 */
class RemoteEndpoints(
    private val tasks: RemoteTasks,
    private val serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val device: DeviceAccess? = null,
    private val history: RemoteHistory? = null,
    private val records: RunExportSource? = null,
    /** 外部大脑会话注册表;为 null 时 brain 入口根本不出现(不是出现了再拒绝)。
     * 由 App 侧在组装好 McpServer(ToolDispatcher 工厂)后传入;见 docs/muse-brain.md。
     * MCP 的 mika_brain_* 工具尚未并入 McpRpc(它目前只认 McpTaskTools),HTTP brain 接口是本分支的首要入口。 */
    private val brain: ExternalBrainSessions? = null,
) {
    private val brainApi = brain?.let { ExternalBrainApi(it, clock) }
    private val clients = LinkedHashMap<String, Long>()
    private val mutable = MutableStateFlow(RemoteActivity())
    val activity = mutable.asStateFlow()

    /** [deviceAccess] 为 false 时这次请求看不到唤醒、解锁和锁屏状态。 */
    fun handle(req: HttpRequest, deviceAccess: Boolean = true, historyAccess: Boolean = true): HttpResponse {
        // Relay adapters do not carry the phone's bearer <redacted> They still get one isolated task namespace,
        // never the internal unscoped view and never local history.
        val owner = req.accessOwner ?: "relay"
        val scopedTasks = tasks.scope(owner)
        val service = RemoteTaskService(scopedTasks, records = records?.scope(owner))
        val rpc = McpRpc(McpTaskTools(service, device.takeIf { deviceAccess }), serverVersion) { remote, name -> seen(remote, name) }
        if (req.path == McpRpc.PATH) return rpc.handle(req)
        // brain 路径必须在任务 API 之前分发:它同样以 /api/v1 开头,任务路由会把它当成不存在的任务端点。
        if (brainApi != null && (req.path == ExternalBrainApi.ROOT || req.path.startsWith("${ExternalBrainApi.ROOT}/"))) {
            seen(req.remote, req.header("x-mika-client")?.take(64))
            return brainApi.handle(req)
        }
        if (req.path == RemoteTaskApi.ROOT || req.path.startsWith("${RemoteTaskApi.ROOT}/")) {
            seen(req.remote, req.header("x-mika-client")?.take(64))
            val api = RemoteTaskApi(service, serverVersion, device.takeIf { deviceAccess }, history = history.takeIf { historyAccess }, brainEnabled = brain != null)
            return api.handle(req)
        }
        return RemoteTaskApi.error(404, "not_found", "No such endpoint")
    }

    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key); clients[key] = now
            clients.entries.removeAll { now - it.value > RemoteHost.RECENT_MS }
            mutable.value = RemoteActivity(clients.size, now)
        }
    }
}
