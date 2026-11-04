package com.wangzan101.mika.cap.phone.setup

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

// Root command runner, muse-brain only.
// Why a separate runner, and why it does not touch the helper:
// The shell helper must stay shell uid 2000; HelperHub rejects a helper
// started as root outright, so "run the helper as root" can never work.
// This runner therefore never talks to the helper at all. It runs one
// command from Mika's own app process through su, exactly the way
// RootGrant already proves su works from this process once the owner
// has granted Mika root in KernelSU or Magisk.
// Scope, agreed with the phone owner on 2026-11-04:
// - Only runs the single command the external brain explicitly asked for.
// - Only from Mika's own app process, only via su, never as a helper.
// - No command is invented here, no fallback to the shell helper, no
//   retry with another su binary after one su binary has actually run:
//   a denied or failed su is reported as-is, so the caller can tell the
//   owner what happened instead of silently trying a different route.
// - A first su call may show a KernelSU/Magisk prompt on the main
//   screen; the owner must allow it there, this code cannot allow it.
// - Output is capped so a runaway command cannot flood the brain session.
object RootCommandRunner {
    private const val TAG = "MikaRootCommand"
    private const val MAX_OUTPUT_CHARS = 65_536

    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/busybox/su",
    )

    data class Result(val exitCode: Int, val output: String)

    fun run(command: String, timeoutMs: Long): Result? {
        val timeout = timeoutMs.coerceIn(1_000L, 60_000L)
        for (candidate in suCandidates) {
            val process = try {
                ProcessBuilder(candidate, "-c", command)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                Log.w(TAG, "su candidate not usable: $candidate", e)
                continue
            }
            val output = StringBuilder()
            try {
                // Read on a separate thread while waiting: otherwise a
                // command that prints more than the pipe buffer holds
                // would block forever and the timeout could never fire.
                val readerThread = Thread {
                    try {
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (output.length < MAX_OUTPUT_CHARS) {
                                output.append(line).append('\n')
                            }
                        }
                    } catch (_: Exception) {
                    }
                }.also { it.start() }
                val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
                readerThread.join(1_000L)
                if (!finished) {
                    process.destroyForcibly()
                    return Result(-1, (output.toString() + "\nerror: timeout").take(MAX_OUTPUT_CHARS))
                }
                // One su binary actually ran: report its result, do not
                // try other candidates even when the command itself failed.
                return Result(process.exitValue(), output.toString().take(MAX_OUTPUT_CHARS))
            } catch (e: Exception) {
                Log.w(TAG, "su run failed: $candidate", e)
                runCatching { process.destroyForcibly() }
                return Result(-1, "error: ${e.message}".take(MAX_OUTPUT_CHARS))
            }
        }
        return null
    }
}
