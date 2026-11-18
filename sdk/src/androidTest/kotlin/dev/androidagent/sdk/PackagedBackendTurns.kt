package dev.androidagent.sdk

import dev.androidagent.sample.SampleMcpServer
import android.content.Context
import android.util.Log
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.JsonRpcException
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

internal object PackagedBackendTurns {
    suspend fun verify(context: Context) {
        val packages = PackagedBackends(context)
        val driver = LocalAgentDriver(context, packages)
        val isolated = DeviceSupport.check(context) is DeviceSupport.Supported
        Log.i("AndroidAgentProbe", "Backend turn isolation launcher enabled: $isolated")
        for ((backend) in packages.installed()) {
            val scratch = Files.createTempDirectory(context.cacheDir.toPath(), "backend-turn-").toFile()
            try {
                verifyBackend(driver, backend, File(scratch, "allow").apply { mkdir() }, PermissionOptionKind.ALLOW_ONCE, isolated)
                if (backend == AgentBackend.Codex) {
                    verifyBackend(driver, backend, File(scratch, "reject").apply { mkdir() }, PermissionOptionKind.REJECT_ONCE, isolated)
                    verifyBackend(driver, backend, File(scratch, "cancel").apply { mkdir() }, null, isolated)
                }
            } finally {
                deleteAgentFiles(scratch)
            }
        }
    }

    private suspend fun verifyBackend(
        driver: LocalAgentDriver, backend: AgentBackend, scratch: File, decision: PermissionOptionKind?, isolated: Boolean,
    ) {
        val marker = "note-${UUID.randomUUID()}"
        val model = "fixture-model"
        val secret = "synthetic-fixture-token"
        val invocations = AtomicInteger()
        val approvals = AtomicInteger()
        val shouldInvoke = decision == PermissionOptionKind.ALLOW_ONCE
        val fixture = ToolCallingModel(backend, model, secret, marker, shouldInvoke)
        val note = AgentTool("read_note", "Read the note for id demo",
            Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""").jsonObject) { args ->
            check(args["id"] == JsonPrimitive("demo"))
            invocations.incrementAndGet()
            buildJsonObject { put("text", marker) }
        }
        MockWebServer().use { server ->
            server.dispatcher = fixture
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val record = SessionRecord(AgentSession(UUID.randomUUID().toString(), backend, "fixture"))
            val stderr = File(scratch, "backend.stderr")
            suspend fun turn(recordForTurn: SessionRecord, prompt: String): Pair<String, String> {
                var id: String? = null
                val answer = StringBuilder()
                val result = withTimeout(20_000) {
                    SampleMcpServer(this, listOf(note)).use { host ->
                        driver.execute(
                        recordForTurn, prompt,
                        ModelEndpoint(server.url("/v1/").toString(), model, secret),
                        File(scratch, "workspace"), File(scratch, "home"),
                        approve = { call, options ->
                            if (backend == AgentBackend.Codex) check(call.hostToolName == note.name)
                            approvals.incrementAndGet()
                            decision?.let { kind -> options.first { it.kind == kind } }
                        },
                        nativeSession = { id = it },
                        update = { update ->
                            val chunk = (update as? SessionUpdate.AgentMessageChunk)?.content as? ContentBlock.Text
                            chunk?.let { answer.append(it.text) }
                        },
                        start = { builder ->
                            // Packaging/protocol test only, using a loopback fixture and synthetic data.
                            // The regular app always enters via the Android isolation launcher.
                            check(builder.environment().values.none { secret in it })
                            if (!isolated) builder.command(builder.command().drop(3))
                            builder.redirectError(stderr).start()
                        },
                            hostMcp = host.binding,
                        )
                    }
                }
                check(result == StopReason.END_TURN && !id.isNullOrBlank()) { "Unexpected stop reason $result." }
                return checkNotNull(id) to answer.toString()
            }

            try {
                val (id, answer) = turn(record, "Read the note with id demo using read_note.")
                check(invocations.get() == if (shouldInvoke) 1 else 0) { "Unexpected Kotlin note invocations: ${invocations.get()}." }
                if (backend == AgentBackend.Codex) check(approvals.get() == 1) { "Missing ACP approval prompt." }
                if (shouldInvoke) {
                    check(fixture.observedResult && marker in answer) { "The note did not reach the model and streamed answer." }
                    val (resumed, nextAnswer) = turn(record.copy(nativeId = id), "What note did we just read?")
                    check(resumed == id && marker in nextAnswer && invocations.get() == 1) {
                        "The restarted adapter did not reuse its native conversation."
                    }
                } else {
                    check(fixture.observedRefusal && "The tool was blocked." in answer && marker !in answer)
                }
                check(!stderr.readText().contains(secret))
            } catch (failure: Exception) {
                val diagnostic = (failure as? JsonRpcException)?.data
                throw IllegalStateException("$backend tool turn failed. Events: ${fixture.events}. " +
                    "$diagnostic\n${stderr.takeIf(File::exists)?.readText()?.takeLast(2500)}", failure)
            }
            Log.i("AndroidAgentProbe", if (shouldInvoke) "Packaged $backend called Kotlin note tool and resumed native session after restart"
            else "Packaged $backend kept the Kotlin tool uncalled after ${decision?.name ?: "CANCEL"}")
        }
    }
}

internal class ToolCallingModel(
    private val backend: AgentBackend,
    private val model: String,
    private val secret: String,
    private val marker: String,
    private val shouldInvoke: Boolean,
    private val toolName: String = "read_note",
    private val arguments: String = """{"id":"demo"}""",
) : Dispatcher() {
    private val requestId = AtomicInteger()
    val events = ConcurrentLinkedQueue<String>()
    @Volatile var observedResult = false
        private set
    @Volatile var observedRefusal = false
        private set

    override fun dispatch(request: RecordedRequest): MockResponse = try {
        val suffix = if (backend == AgentBackend.Pi) "/chat/completions" else "/responses"
        check(request.path == "/v1$suffix") { "unexpected path ${request.path}" }
        check(request.getHeader("Authorization") == "Bearer $secret") { "incorrect host credential" }
        val serialized = request.body.readUtf8()
        check(secret !in serialized) { "credential reached model payload" }
        val body = Json.parseToJsonElement(serialized).jsonObject
        check(body["model"] == JsonPrimitive(model)) { "wrong model ${body["model"]}" }
        val seq = requestId.incrementAndGet()
        check(seq <= 5) { "unexpected repeat" }
        val toolResult = if (backend == AgentBackend.Codex) {
            (body["input"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .lastOrNull { it["type"] == JsonPrimitive("function_call_output") }
                ?.get("output")?.toString().orEmpty()
        } else {
            (body["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .lastOrNull { it["role"] == JsonPrimitive("tool") }
                ?.get("content")?.toString().orEmpty()
        }
        if (toolResult.isNotEmpty()) events += "tool-result:" + toolResult.take(400)
        val advertised = tools(body["tools"])
        val exact = advertised.firstOrNull { (name) -> name.endsWith("__$toolName") || name == toolName }
        val output = if (!shouldInvoke && listOf("cancelled", "rejected").any { word -> toolResult.contains(word, ignoreCase = true) }) {
            observedRefusal = true
            FixtureOutput(answer = "The tool was blocked.")
        } else if (marker in toolResult) {
            observedResult = true
            events += "received-note"
            FixtureOutput(answer = "The note says $marker.")
        } else if (exact != null) {
            events += "invoke-note:${exact.first}:${exact.second}"
            FixtureOutput(tool = exact.first, namespace = exact.second, args = arguments)
        } else {
            check(advertised.any { (name) -> name == "tool_search" }) { "note/tool_search not exposed: $advertised" }
            events += "discover-note"
            FixtureOutput(tool = "tool_search", args = buildJsonObject { put("query", toolName) }.toString())
        }
        val frames = if (backend == AgentBackend.Pi) chat(seq, output) else responses(seq, output)
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(frames)
    } catch (failure: Exception) {
        events += failure.message ?: "unknown fixture error"
        MockResponse().setResponseCode(400)
    }

    private fun tools(value: kotlinx.serialization.json.JsonElement?, namespace: String? = null): List<Pair<String, String?>> =
        (value as? JsonArray).orEmpty().flatMap { row ->
            val objectRow = row as? JsonObject ?: return@flatMap emptyList()
            val function = objectRow["function"] as? JsonObject ?: objectRow
            val name = (function["name"] as? JsonPrimitive)?.content ?: return@flatMap emptyList()
            if (objectRow["type"] == JsonPrimitive("namespace")) tools(objectRow["tools"], name)
            else listOf(name to namespace)
        }

    private fun chat(seq: Int, output: FixtureOutput): String {
        val delta = if (output.tool != null) {
            """{"role":"assistant","tool_calls":[{"index":0,"id":"call_$seq","type":"function","function":{"name":${quote(output.tool)},"arguments":${quote(output.args)}}}]}"""
        } else """{"role":"assistant","content":${quote(output.answer)}}"""
        val reason = if (output.tool != null) "tool_calls" else "stop"
        fun chunk(delta: String, finish: String) =
            """{"id":"chat_$seq","object":"chat.completion.chunk","created":0,"model":${quote(model)},"choices":[{"index":0,"delta":$delta,"finish_reason":$finish}]}"""
        return "data: ${chunk(delta, "null")}\n\ndata: ${chunk("{}", quote(reason))}\n\ndata: [DONE]\n\n"
    }

    private fun responses(seq: Int, output: FixtureOutput): String {
        val item = if (output.tool != null) {
            val namespace = output.namespace?.let { ",\"namespace\":${quote(it)}" }.orEmpty()
            """{"type":"function_call","id":"fc_$seq","call_id":"call_$seq","name":${quote(output.tool)},"arguments":${quote(output.args)},"status":"completed"$namespace}"""
        } else """{"type":"message","id":"msg_$seq","role":"assistant","status":"completed","content":[{"type":"output_text","text":${quote(output.answer)},"annotations":[]}]}"""
        fun response(status: String, items: String) =
            """{"id":"resp_$seq","object":"response","model":${quote(model)},"status":${quote(status)},"created_at":0,"parallel_tool_calls":false,"output":$items,"usage":{"input_tokens":20,"output_tokens":8,"total_tokens":28,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}"""
        val frames = buildList {
            add("response.created" to ("response" to response("in_progress", "[]")))
            add("response.output_item.added" to ("item" to item))
            if (output.tool == null) {
                val split = output.answer.length / 2
                add("response.output_text.delta" to ("delta" to quote(output.answer.take(split))))
                add("response.output_text.delta" to ("delta" to quote(output.answer.drop(split))))
            }
            add("response.output_item.done" to ("item" to item))
            add("response.completed" to ("response" to response("completed", "[$item]")))
        }
        return frames.joinToString("") { (type, field) ->
            "event: $type\ndata: {\"type\":\"$type\",\"output_index\":0,\"${field.first}\":${field.second}}\n\n"
        }
    }

    private fun quote(value: String) = JsonPrimitive(value).toString()

    private data class FixtureOutput(
        val tool: String? = null, val namespace: String? = null, val args: String = "{}", val answer: String = "",
    )
}
