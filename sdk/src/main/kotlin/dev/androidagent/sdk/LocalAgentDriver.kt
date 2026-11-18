package dev.androidagent.sdk

import android.content.Context
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class LocalAgentDriver(
    private val app: Context,
    private val backends: PackagedBackends,
) {
    suspend fun run(
        record: SessionRecord,
        prompt: String,
        model: ModelEndpoint,
        workspace: File,
        home: File,
        approve: suspend (AcpToolCall, List<PermissionOption>) -> PermissionOption?,
        nativeSession: (String) -> Unit,
        update: suspend (SessionUpdate) -> Unit,
        hostMcp: AgentMcpServer? = null,
    ): StopReason {
        val support = DeviceSupport.check(app)
        if (support !is DeviceSupport.Supported) throw UnsupportedAgentDevice(support)
        return execute(record, prompt, model, workspace, home, approve, nativeSession, update,
            start = ProcessBuilder::start, hostMcp = hostMcp)
    }

    internal suspend fun execute(
        record: SessionRecord,
        prompt: String,
        model: ModelEndpoint,
        workspace: File,
        home: File,
        approve: suspend (AcpToolCall, List<PermissionOption>) -> PermissionOption?,
        nativeSession: (String) -> Unit,
        update: suspend (SessionUpdate) -> Unit,
        start: (ProcessBuilder) -> Process,
        hostMcp: AgentMcpServer? = null,
    ): StopReason = coroutineScope {
        val backend = record.session.backend
        val payload = backends.open(backend)
        val task = Files.createTempDirectory(File(app.cacheDir, "android-agent-tasks").apply { mkdirs() }.toPath(), "run-").toFile()
        workspace.mkdirs()
        home.mkdirs()

        try {
            // Scope and model tokens are specific to this turn. The upstream key lives in Kotlin.
            ModelRelay(backend, model.baseUrl.toHttpUrl(), model.apiKey).use { relay ->
                val mcp = hostMcp?.let {
                    listOf(McpServer.Http(HOST_MCP_SERVER_NAME, it.url.toString(),
                        it.headers.map { (name, value) -> HttpHeader(name, value) }))
                }.orEmpty()
                val child = start(process(backend, payload, home, task, workspace, relay,
                    hostMcp?.url?.port, model))
                AcpProcess(this, child).use { peer ->
                    val session = peer.open(workspace, record.nativeId, mcp, approve)
                    nativeSession(session.sessionId.value)
                    try {
                        val result = try {
                            peer.prompt(session, prompt, update)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            relay.failedStatus?.let { throw ModelServiceException(it) }
                            throw failure
                        }
                        relay.failedStatus?.let { throw ModelServiceException(it) }
                        result
                    } finally {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                            withContext(NonCancellable) { withTimeoutOrNull(300) { session.cancel() } }
                        }
                    }
                }
            }
        } finally {
            deleteAgentFiles(task)
        }
    }

    private fun process(
        backend: AgentBackend,
        payload: StagedAdapter,
        home: File,
        task: File,
        workspace: File,
        relay: ModelRelay,
        mcpPort: Int?,
        endpoint: ModelEndpoint,
    ): ProcessBuilder {
        val native = File(app.applicationInfo.nativeLibraryDir)
        val launcher = File(native, "libandroid_agent_sandbox.so")
        return ProcessBuilder(
            launcher.absolutePath, task.absolutePath, native.absolutePath,
            backends.node.absolutePath, payload.entry.absolutePath,
        ).directory(task).redirectError(File(task, "backend.stderr")).apply {
            environment().apply {
                clear()
                put("HOME", home.absolutePath)
                put("TMPDIR", task.absolutePath)
                put("PATH", "/system/bin")
                put("SHELL", "/system/bin/sh")
                put("ANDROID_AGENT_WORKSPACE", workspace.absolutePath)
                put("ANDROID_AGENT_SESSION_HOME", home.absolutePath)
                put("ANDROID_AGENT_RUNTIME", payload.directory.absolutePath)
                put("ANDROID_AGENT_MODEL_PORT", relay.port.toString())
                if (mcpPort != null) put("ANDROID_AGENT_MCP_PORT", mcpPort.toString())

                when (backend) {
                    AgentBackend.Pi -> {
                        put("PI_CODING_AGENT_DIR", home.absolutePath)
                        configurePi(home, relay.localBase, endpoint)
                    }
                    AgentBackend.Codex -> {
                        put("CODEX_HOME", home.absolutePath)
                        put("CODEX_PATH", backends.codex.absolutePath)
                        put("NO_BROWSER", "1")
                        configureCodex(home, relay.localBase, endpoint)
                    }
                }
            }
        }
    }

    private fun configurePi(home: File, localBase: String, endpoint: ModelEndpoint) {
        val model = endpoint.model
        replace(home, "models.json", buildJsonObject {
            put("providers", buildJsonObject {
                put("android-relay", buildJsonObject {
                    put("baseUrl", localBase)
                    put("api", "openai-completions")
                    put("apiKey", "local-relay")
                    put("models", buildJsonArray {
                        add(buildJsonObject {
                            put("id", model)
                            if (endpoint.acceptsImages) putJsonArray("input") { add("text"); add("image") }
                        })
                    })
                })
            })
        })
        replace(home, "settings.json", buildJsonObject {
            put("defaultProvider", "android-relay")
            put("defaultModel", model)
            put("theme", "dark")
            put("quietStartup", true)
        })
    }

    private fun configureCodex(home: File, localBase: String, endpoint: ModelEndpoint) {
        val model = endpoint.model
        val catalog = if (endpoint.acceptsImages) {
            val fileName = "vision-models.json"
            replace(home, fileName, buildJsonObject {
                putJsonArray("models") {
                    add(buildJsonObject {
                        put("slug", model)
                        put("display_name", model)
                        put("base_instructions", "You are an AI assistant running in Android Agent SDK. Use the available tools to complete the user's task and verify the result before reporting success.")
                        put("shell_type", "shell_command")
                        put("visibility", "list")
                        put("supported_in_api", true)
                        put("priority", 1)
                        put("support_verbosity", false)
                        put("supports_reasoning_summary_parameter", false)
                        putJsonArray("supported_reasoning_levels") {}
                        putJsonArray("experimental_supported_tools") {}
                        putJsonArray("input_modalities") { add("text"); add("image") }
                        put("truncation_policy", buildJsonObject { put("mode", "tokens"); put("limit", 10_000) })
                    })
                }
            })
            "model_catalog_json = ${JsonPrimitive(File(home, fileName).absolutePath)}"
        } else ""
        // codex-acp checks account requirements before passing thread-level options to Codex.
        // Configure the keyless loopback provider when the App Server starts.
        replace(home, "config.toml", """
            model = ${JsonPrimitive(model)}
            model_provider = "android-relay"
            web_search = "disabled"
            $catalog

            [model_providers.android-relay]
            name = "Android model relay"
            base_url = ${JsonPrimitive(localBase)}
            wire_api = "responses"
            requires_openai_auth = false
            supports_websockets = false

            [shell_environment_policy]
            inherit = "none"
        """.trimIndent())
    }

    private fun replace(directory: File, name: String, json: JsonObject) = replace(directory, name, json.toString())

    private fun replace(directory: File, name: String, contents: String) {
        val staging = Files.createTempFile(directory.toPath(), ".config-", ".tmp")
        try {
            staging.toFile().writeText(contents)
            Files.move(staging, File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}

internal fun deleteAgentFiles(root: File) {
    // A backend may create symlinks to persistent files. Delete the link itself.
    Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    }
}

internal class ModelServiceException(val statusCode: Int) : IllegalStateException()
