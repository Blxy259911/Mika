package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.wangzan101.mika.core.shell.PermissionCommands
import java.util.concurrent.TimeUnit

// Root grant for Mika itself, and nothing else.
// Scope, agreed with the phone owner on 2026-11-03:
// - Only Mika's own package, only WRITE_SECURE_SETTINGS, the permission Mika's
//   own recovery already asks for through the helper shell.
// - Only tried when Mika does not already hold that permission.
// - Only run from Mika's own app process, after the owner gave Mika root in
//   KernelSU. It never grants anything to another app, never runs a command
//   other than the pm grant below, and never starts the helper as root:
//   the helper handoff only accepts shell uid 2000, so a root-started helper
//   would be rejected anyway.
// - Failure is silent by design: no root, root denied, or su missing all just
//   mean Mika keeps the old helper-shell path, exactly as before this patch.
// - Reversible: pm revoke <package> android.permission.WRITE_SECURE_SETTINGS.
object RootGrant {
    private const val TAG = "MikaRootGrant"
    private const val TIMEOUT_SECONDS = 10L

    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/busybox/su",
    )

    fun alreadyGranted(context: Context): Boolean {
        val app = context.applicationContext
        return app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    // Returns true only when Mika holds the permission afterwards.
    fun tryGrant(context: Context): Boolean {
        val app = context.applicationContext
        if (alreadyGranted(app)) return true
        val pkg = app.packageName
        val grant = "pm grant $pkg ${PermissionCommands.WRITE_SECURE_SETTINGS}"
        for (candidate in suCandidates) {
            val process = try {
                ProcessBuilder(candidate, "-c", grant)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                Log.w(TAG, "su candidate not usable: $candidate", e)
                continue
            }
            try {
                val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    Log.w(TAG, "su grant timed out: $candidate")
                    return alreadyGranted(app)
                }
                if (process.exitValue() != 0) {
                    Log.w(TAG, "su grant exited ${process.exitValue()}: $candidate")
                    return alreadyGranted(app)
                }
            } catch (e: Exception) {
                Log.w(TAG, "su grant failed: $candidate", e)
                return alreadyGranted(app)
            }
            // One su prompt is enough: do not try other candidates after a
            // su binary actually ran, whether it granted or was denied.
            return alreadyGranted(app)
        }
        return false
    }
}
