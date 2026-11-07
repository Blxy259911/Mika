package com.wangzan101.mika.mcp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceCredential
import com.wangzan101.mika.device.AndroidDeviceAccessPort
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.brain.ExternalBrainSessions
import com.wangzan101.mika.core.mcp.McpServer
import com.wangzan101.mika.core.mcp.McpTaskHost
import com.wangzan101.mika.core.resource.ResourceScope
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.task.AppMuter
import com.wangzan101.mika.task.AppsAfterTaskEnd
import com.wangzan101.mika.task.AudioMute
import com.wangzan101.mika.task.TaskRecovery
import com.wangzan101.mika.task.TaskService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 外部大脑的手机供给:把 TaskRun.connectPhone 的真实链路抽出来,给 brain 会话用。
 *
 * 链路(和 TaskRun 里同一套,不是假屏):
 * PhoneServices.sharedShell -> 等内置助手 shell -> AccessibilityRepair.awaitService 等无障碍
 * -> TaskRecovery.beginRun -> LazyAgentDisplay / AgentDisplay 虚拟屏
 * -> AndroidScreen -> AppAccessScreenPort -> toolDispatcher(只给手机端口,不给网页、沙箱)。
 *
 * 关键时序,和 McpServer 的 assemble 签名有关:
 * assemble: (RunSpec) -> ToolDispatcher 本身不是 suspend,所以它只建 LazyPhoneScreen、
 * AppAccessScreenPort 和 ToolDispatcher,并不真的接手机。真正接手机发生在第一个需要手机的
 * 工具调用时(LazyPhoneScreen 在 suspend 的 acquire 里调 connectPhone,和 TaskRun 里
 * 第一次 launch_app / 系统命令时才接手机完全一样)。phone_observe 这类只读屏的工具在
 * 没接上之前和 TaskRun 里一样返回空屏 / no_app_open,不会为它去接手机。
 *
 * 生命周期:
 * 每个 brain 会话(McpToolSession 的 RunSpec.id)对应一个 BrainSession,各有自己的
 * ResourceScope、虚拟屏和 TaskRecovery 登记,不会复用一个正在跑的 TaskRun 的 dispatcher。
 * 会话关闭时 ExternalBrainSessions 的 onClose 回调调到这里的 release,把虚拟屏、
 * 静音、TaskRecovery 登记、占用登记和 sharedShell 占用按 TaskRun 收尾的顺序放掉。
 * 会话从没接上过手机时 release 是空操作(只关空的 ResourceScope)。
 *
 * 并发与隔离(明确写出来,不假装):
 * - 每个会话有自己的虚拟屏和自己的占用登记键(RunSpec.id),占用登记走 MikaApp.resources
 *   那张和 TaskCenter 共用的表,所以 brain 会话和普通任务互相占同一个 App 时会按同一
 *   规则挡住,不会悄悄抢。
 * - 但第二块虚拟屏在有些设备上建不出来(TaskRun 里有同样的并行上限处理)。这里不做
 *   排队:建屏失败时按 TaskRun 的做法转成 PhoneUnavailable(用已有的
 *   parallel_second_display_failed 文案),那次工具调用报 phone_unavailable,下次调用
 *   会重试;会话本身不因此作废,等别的屏放掉就能接着用。
 * - brain 会话没有接管(Takeover)状态:AndroidScreen 的 handedOver 恒为 false。也就是说
 *   brain 会话默认手机不在用户手上操作;用户同时在主屏上操作时,不要同时跑 brain 会话。
 * - brain 会话不发布画中画画面,也不跑 TaskRun 的 watchTarget(前台 App 出现时自动静音):
 *   启动前静音(beforeOpen / notOpened)仍在,启动后从别处冒出来的 App 不会自动补静。
 */
class BrainPhone(
    private val app: MikaApp,
    private val tasks: McpTaskHost,
) {
    private val phoneServices = PhoneServices.of(app)
    private val recovery = TaskRecovery.of(app)
    private val appAccess = app.appAccess
    private val resources = app.resources
    private val taskState = app.taskState

    /** 按 McpToolSession 的 RunSpec.id 索引的 brain 手机会话;关闭时删掉。 */
    private val sessions = ConcurrentHashMap<String, BrainSession>()

    /**
     * 给 McpRuntime 的 brain 注册表,建一次。McpServer 的 assemble 在每次
     * openToolSession 时调到这里的 assemble,建出该会话自己的手机端口和 dispatcher。
     */
    val brain: ExternalBrainSessions by lazy {
        ExternalBrainSessions(
            McpServer(assemble = { spec -> assemble(spec) }, tasks = tasks),
            onClose = { session -> release(session.session.spec.id.value) },
        )
    }

    private fun assemble(spec: RunSpec): ToolDispatcher {
        val session = BrainSession(spec.id.value)
        sessions[spec.id.value] = session
        val metrics = mainScreenMetrics()
        val lazyScreen = LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
            listApps = { withContext(Dispatchers.IO) { launchableApps(app.packageManager, app.packageName) } },
            appCopies = {
                phoneServices.hub.shell.value?.let { s ->
                    withContext(Dispatchers.IO) {
                        runCatching { AppCopyReader.read(s, launchableApps(app.packageManager, app.packageName)) }.getOrNull()
                    }
                }.orEmpty()
            },
            deviceUnlock = { kind, chars ->
                // LazyPhoneScreen.unlockDevice 已先确保接上 shell,此处 session.shell 不为空才继续。
                val shell = session.shell
                if (shell == null) false else withContext(Dispatchers.IO) {
                    val port = AndroidDeviceAccessPort(app) { session.shell }
                    val state = runCatching { port.state() }.getOrNull()
                    if (state?.ready == true) true else {
                        var ok = runCatching { port.wake() }.getOrDefault(false)
                        if (ok) ok = runCatching { port.dismiss() }.getOrDefault(false)
                        if (ok) {
                            val credentialKind = if (kind == "password") CredentialKind.Password else CredentialKind.Pin
                            val credential = DeviceCredential(credentialKind, chars)
                            try { runCatching { port.enter(credential) }.getOrDefault(false) } finally { credential.close() }
                        } else false
                    }
                }
            },
            dial = { number ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        app.startActivity(intent)
                        true
                    }.getOrDefault(false)
                }
            }) { connectPhone(session) }
        session.lazyScreen = lazyScreen
        val accessed = AppAccessScreenPort(
            lazyScreen, appAccess,
            packages = { session.screen?.accessPackages().orEmpty() },
            protectedUi = { appAccess.sensitiveUi }, ownPackage = app.packageName,
            windowToken = { ScreenAccessibility.windowGeneration() },
        )
        session.accessed = accessed
        return toolDispatcher(accessed, runSpec = spec)
    }

    /** 占着某个 App 的一方是不是还在:在跑的 brain 会话,或者在跑的普通任务。 */
    private fun holderActive(holder: String): Boolean =
        sessions.containsKey(holder) || taskState.runs.value.any { it.key == holder }

    private suspend fun release(key: String) {
        val session = sessions.remove(key) ?: return
        session.release()
    }

    private inner class BrainSession(val key: String) {
        val scope = ResourceScope()
        @Volatile var lazyScreen: LazyPhoneScreen? = null
        @Volatile var accessed: AppAccessScreenPort? = null
        @Volatile var connection: ShellBinding? = null
        @Volatile var acquiredShell = false
        @Volatile var recoveryBegun = false
        @Volatile var shell: IMikaShell? = null
        @Volatile var screen: AndroidScreen? = null
        @Volatile var display: LazyAgentDisplay? = null
        @Volatile var audio: AudioMute? = null
        @Volatile var muter: AppMuter? = null
        @Volatile var shellDied = false
        // connectPhone 在外层设这两个值,所以不能是 private(inner 类的 private 外层看不到)。
        var shellDeath: IBinder.DeathRecipient? = null
        var shellBinder: IBinder? = null
        private val released = AtomicBoolean(false)

        /**
         * 收尾,顺序照 TaskRun.execute 的 finally:先定屏上 App 的去留、再还原静音、
         * 再关 ResourceScope(按建的反序放虚拟屏),最后放 sharedShell 占用和占用登记。
         * 幂等,失败一半也不影响其余步骤,关闭回调里抛错也不会把一次成功的会话关闭
         * 变成失败(见 ExternalBrainSessions.close 的 runCatching)。
         */
        suspend fun release() {
            if (!released.compareAndSet(false, true)) return
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    val s = shell
                    val d = display?.current
                    if (s != null && d != null && !shellDied) {
                        runCatching { AppsAfterTaskEnd.finish(s, d.displayId, lazyScreen?.afterTask) }
                    }
                    runCatching { audio?.restoreAll() }
                } finally {
                    try {
                        if (recoveryBegun) recovery.endRun(key)
                    } finally {
                        runCatching { scope.close() }
                    }
                }
            }
            runCatching {
                val binder = shellBinder
                val recipient = shellDeath
                if (binder != null && recipient != null) binder.unlinkToDeath(recipient, 0)
            }
            if (acquiredShell) {
                runCatching { phoneServices.sharedShell.release() }
                acquiredShell = false
            }
            resources.releaseOwner(key)
        }

        /** 接手机失败时的部分收尾:还没交给会话的 shell 占用和 TaskRecovery 登记要当场放掉, */
        /** 否则下次重试会在 sharedShell 上多占一次。ResourceScope 本身留着,重试还用它。 */
        suspend fun cleanupAfterFailedConnect() {
            if (recoveryBegun) {
                runCatching { recovery.endRun(key) }
                recoveryBegun = false
            }
            if (acquiredShell) {
                runCatching { phoneServices.sharedShell.release() }
                acquiredShell = false
            }
            connection = null
            shell = null
        }
    }

    /**
     * 接上手机,和 TaskRun.connectPhone 同一步骤,只是没有任务界面那几样
     * (DeviceStateWatcher、画中画、接管、并行排队)。
     * 失败时抛 PhoneUnavailable,由 LazyPhoneScreen 转成 phone_unavailable,下次调用重试;
     * 抛之前先把这次已占的 shell / TaskRecovery 登记放掉,见 cleanupAfterFailedConnect。
     */
    private suspend fun connectPhone(session: BrainSession): AndroidScreen {
        try {
            if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) throw PhoneUnavailable(TaskService.TOO_OLD)
            val conn = phoneServices.sharedShell.acquire().also { session.connection = it; session.acquiredShell = true }
            val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
                ?: run {
                    phoneServices.setupPrompt.request()
                    throw PhoneUnavailable(
                        if (phoneServices.shellControl.restart == HelperRestart.UsbCommand) tr(R.string.task_no_phone_helper_usb)
                        else tr(R.string.task_no_phone_helper),
                    )
                }
            session.shell = shell
            val a11y = AccessibilityRepair.awaitService(
                ScreenAccessibility.instance, phoneServices.recovery.accessibilityRepair.phase, A11Y_WAIT_MS,
            ) ?: throw PhoneUnavailable(tr(R.string.task_no_a11y))
            recovery.beginRun(shell, session.key)
            session.recoveryBegun = true
            session.shellDeath = IBinder.DeathRecipient { session.shellDied = true }.also {
                shell.asBinder().linkToDeath(it, 0)
            }
            session.shellBinder = shell.asBinder()
            val metrics = mainScreenMetrics()
            val agentDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
                try {
                    session.scope.acquire(Dispatchers.IO, AgentDisplay::release) {
                        AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                            app.getSystemService(DisplayManager::class.java))
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is IllegalStateException || e is RemoteException) {
                        throw PhoneUnavailable(tr(R.string.parallel_second_display_failed))
                    }
                    throw e
                }
            }
            session.display = agentDisplay
            val audio = recovery.audioMute(shell, session.key).also { session.audio = it }
            val muter = appMuter(shell, audio).also { session.muter = it }
            fun claimKey(pkg: String, user: Int?) = if (user == null) pkg else "$pkg@$user"
            fun stillUsed(pkg: String, user: Int?): () -> Boolean = {
                runCatching { if (user == null) shell.displayOfPackage(pkg) else shell.displayOfPackageInUser(pkg, user) }
                    .getOrDefault(-1) >= 0
            }
            val screen = AndroidScreen(
                a11y, shell, agentDisplay,
                clipHeld = { recovery.markClipHeld(it) },
                clipLock = resources.clipboard,
                checkOtherTasks = true,
                reserve = { pkg -> resources.reserveApp(pkg, session.key, ::holderActive, stillUsed(pkg, null)) },
                reserveCopy = { pkg, user -> resources.reserveApp(claimKey(pkg, user), session.key, ::holderActive, stillUsed(pkg, user)) },
                appFree = { pkg, user -> resources.appFree(claimKey(pkg, user), session.key, ::holderActive, stillUsed(pkg, user)) },
                clipSaved = { original, ours -> recovery.saveClip(original, ours) },
                handedOver = { false },
                beforeOpen = muter::beforeOpen,
                notOpened = muter::notOpened,
                appAccess = appAccess,
                awaitApp = { pkg, user, timeoutMs, free ->
                    resources.awaitApp(claimKey(pkg, user), session.key, timeoutMs, ::holderActive, free)
                },
            )
            session.screen = screen
            return screen
        } catch (e: Exception) {
            session.cleanupAfterFailedConnect()
            throw e
        }
    }

    /** 和 TaskRun.appMuter 同一规则,只是静音失败时只记日志(没有任务进度可写)。 */
    private fun appMuter(shell: IMikaShell, audio: AudioMute): AppMuter {
        val pm = app.packageManager
        val own = app.packageName
        fun system(pkg: String) = runCatching {
            pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrElse { runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false) }
        return AppMuter(
            audio,
            enabled = { FeatureFlags.get(app).muteTarget() },
            mutable = { pkg -> pkg != own && (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null || !system(pkg)) },
            onMainScreen = { pkg, user -> runCatching { shell.visibleOnMainScreen(pkg, user ?: AppCopyReader.myUser) }.getOrDefault(true) },
            failed = { pkg -> Log.w(TAG, "brain: could not mute $pkg, it may make sound") },
        )
    }

    @Suppress("DEPRECATION")
    private fun mainScreenMetrics(): DisplayMetrics = DisplayMetrics().also {
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    private companion object {
        const val SHELL_WAIT_MS = 15_000L
        const val A11Y_WAIT_MS = 5_000L
        const val TAG = "MikaBrainPhone"
    }
}
