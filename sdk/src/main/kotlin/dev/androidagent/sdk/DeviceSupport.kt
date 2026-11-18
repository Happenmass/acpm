package dev.androidagent.sdk

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DeviceSupport {
    data class Supported(val landlockAbi: Int) : DeviceSupport
    data object UnsupportedArchitecture : DeviceSupport
    data object MissingLauncher : DeviceSupport
    data object LauncherFailed : DeviceSupport
    data object IsolationBlockedByAppPolicy : DeviceSupport
    data class IsolationUnavailable(val detectedAbi: Int?) : DeviceSupport

    companion object {
        /**
         * Tests from the host app UID, including Android's executable packaging restrictions.
         * Use extracted JNI libraries in the host app (Gradle useLegacyPackaging).
         */
        suspend fun check(context: Context): DeviceSupport = withContext(Dispatchers.IO) {
            if ("arm64-v8a" !in Build.SUPPORTED_ABIS) return@withContext UnsupportedArchitecture
            val launcher = File(context.applicationInfo.nativeLibraryDir, "libandroid_agent_sandbox.so")
            if (!launcher.isFile) return@withContext MissingLauncher
            val process = try {
                ProcessBuilder(launcher.absolutePath, "--abi").redirectErrorStream(true).start()
            } catch (_: IOException) {
                return@withContext LauncherFailed
            }
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) return@withContext LauncherFailed
                val abi = process.inputStream.bufferedReader().use { it.readLine()?.toIntOrNull() }
                if (process.exitValue() == 0 && abi != null && abi >= 6) {
                    Supported(abi)
                } else if (process.exitValue() == 125) {
                    IsolationUnavailable(abi)
                } else if (process.exitValue() == 126) {
                    IsolationBlockedByAppPolicy
                } else {
                    LauncherFailed
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                process.outputStream.close()
                process.errorStream.close()
            }
        }
    }
}
