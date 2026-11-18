package dev.androidagent.sdk

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A separate, network-disabled worker for model-generated code. */
internal class WorkspaceScripts(context: Context, private val node: File) {
    private val app = context.applicationContext

    fun tool(workspace: File, workspaceVariable: String): AgentTool = AgentTool(
        "run_javascript",
        "Run JavaScript with Node in this task's workspace. Use process.env.$workspaceVariable for durable files.",
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("required", JsonArray(listOf(JsonPrimitive("source"))))
            put("properties", buildJsonObject { put("source", buildJsonObject { put("type", "string") }) })
        },
    ) { args ->
        val source = (args["source"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        if (args.keys != setOf("source") || source.isNullOrBlank() || source.length > 16_384) {
            error("Provide one JavaScript program up to 16,384 characters.")
        }
        run(workspace, workspaceVariable, source)
    }

    private suspend fun run(workspace: File, workspaceVariable: String, source: String): JsonObject = withContext(Dispatchers.IO) {
        val support = DeviceSupport.check(app)
        if (support !is DeviceSupport.Supported) throw UnsupportedAgentDevice(support)
        check(node.isFile) { "The Node runtime pack is absent." }
        val taskRoot = File(app.cacheDir, "android-agent-tasks").apply { mkdirs() }
        val scratch = Files.createTempDirectory(taskRoot.toPath(), "script-").toFile()
        try {
            workspace.mkdirs()
            File(scratch, "script.cjs").writeText(source)
            val native = File(app.applicationInfo.nativeLibraryDir)
            val launcher = File(native, "libandroid_agent_sandbox.so")
            val process = ProcessBuilder(
                launcher.absolutePath, scratch.absolutePath, native.absolutePath, node.absolutePath,
                "--permission", "--allow-fs-read=${workspace.absolutePath}", "--allow-fs-write=${workspace.absolutePath}",
                "--allow-fs-read=script.cjs", "--max-old-space-size=64", "script.cjs",
            ).directory(scratch).redirectError(File("/dev/null")).apply {
                environment().apply {
                    clear()
                    put("ANDROID_AGENT_WORKSPACE", workspace.absolutePath)
                    put(workspaceVariable, workspace.absolutePath)
                    put("ANDROID_AGENT_SCRIPT", "1")
                    put("TMPDIR", scratch.absolutePath)
                }
            }.start()

            try {
                val (status, stdout) = withTimeoutOrNull(10_000) { process.capture() }
                    ?: error("Script timed out.")
                buildJsonObject {
                    put("exitCode", status)
                    put("stdout", stdout)
                }
            } finally {
                withContext(NonCancellable) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    process.outputStream.close()
                    process.inputStream.close()
                    process.errorStream.close()
                }
            }
        } finally {
            deleteAgentFiles(scratch)
        }
    }

    private suspend fun Process.capture(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { destroy() }
        Dispatchers.IO.dispatch(continuation.context) {
            try {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val size = inputStream.read(buffer)
                    if (size == -1) break
                    if (output.size() + size > 16_384) throw IOException("Script output exceeded the tool limit.")
                    output.write(buffer, 0, size)
                }
                continuation.resume(waitFor() to output.toString(Charsets.UTF_8.name()))
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }
}
