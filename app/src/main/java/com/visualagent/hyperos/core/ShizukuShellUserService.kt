package com.visualagent.hyperos.core

import android.os.Os
import com.visualagent.hyperos.shizuku.IShizukuShellService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 由 Shizuku 守护进程拉起、运行在 UID 2000 (shell) 权限下的 UserService
 */
class ShizukuShellUserService : IShizukuShellService.Stub() {

    override fun execCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val completed = process.waitFor(8, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return "ERROR: Command timed out: $command"
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("STDERR: ").append(stderr.trim())
                }
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    override fun getUid(): Int = Os.getuid()

    override fun destroy() {
        exitProcess(0)
    }
}
