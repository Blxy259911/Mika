package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.util.Log
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.shell.HelperHandoff
import kotlinx.coroutines.delay

// Root-started helper, muse-brain only, as a fallback when the adb route
// cannot connect at all (the owner's phone: wireless debugging on, but
// Mika finds 0 connect candidates, so AdbConnectPort throws CONNECT_FAILED
// and a manual wireless-debugging toggle is the only thing that helps).
//
// Why this is not just "run the helper as root":
// HelperHub.offer only accepts a helper whose calling uid is shell uid
// 2000, with reported uid and pid matching the caller, and whose Binder
// identity matches. A helper started as root uid 0 is rejected outright.
// So every su attempt here drops to shell first, then runs exactly the
// same HelperHandoff.command the adb route runs, with a fresh nonce
// issued by Mika itself for that attempt. The helper's stdin is the echo
// pipe inside that command, not Mika's process stdin, so closing Mika's
// side after handoff does not kill the setsid-detached helper.
//
// Scope: only Mika's own helper, only when the adb route already failed,
// only from Mika's own app process after the owner gave Mika root.
// Failure returns false and leaves everything as it was: the caller
// rethrows the original adb exception, so the engine's retry and
// backoff behave exactly as before. No other app, no other command,
// no root-started process is ever left running on success or failure:
// a failed attempt's su process is closed or destroyed.
object RootHelperLauncher {
    private const val TAG = "MikaRootHelper"
    private const val PER_ATTEMPT_MS = 8_000L
    private const val POLL_MS = 200L

    private val suBinaries = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
    )

    private val shellUsers = listOf("shell", "2000")

    suspend fun tryLaunch(app: Context, hub: HelperHub, command: String): Boolean {
        val appContext = app.applicationContext
        for (binary in suBinaries) {
            for (user in shellUsers) {
                if (tryOne(appContext, hub, binary, user, command)) return true
            }
        }
        return false
    }

    private suspend fun tryOne(
        app: Context,
        hub: HelperHub,
        binary: String,
        user: String,
        command: String,
    ): Boolean {
        val before = hub.shell.value
        val process = try {
            ProcessBuilder(binary, user, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "su binary not usable: $binary", e)
            return false
        }
        try {
            process.outputStream.write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
            process.outputStream.flush()
        } catch (e: Exception) {
            Log.w(TAG, "cannot write nonce to su: $binary $user", e)
            runCatching { process.destroyForcibly() }
            return false
        }
        val deadline = System.currentTimeMillis() + PER_ATTEMPT_MS
        while (System.currentTimeMillis() < deadline) {
            val current = hub.shell.value
            if (current != null && current !== before) {
                Log.i(TAG, "helper handed off via root su: $binary $user")
                runCatching { process.outputStream.close() }
                return true
            }
            if (!process.isAlive) {
                Log.w(TAG, "su process exited before handoff: $binary $user")
                return false
            }
            delay(POLL_MS)
        }
        Log.w(TAG, "su handoff timed out: $binary $user")
        runCatching { process.outputStream.close() }
        runCatching { process.destroyForcibly() }
        return false
    }
}
