package dev.androidagent.sample

import dev.androidagent.sdk.AgentMcpServer
import dev.androidagent.sdk.AgentTool
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal class SampleMcpTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val execute: suspend (JsonObject) -> JsonObject,
)

/** Sample host's stateless Streamable HTTP service. Each turn receives its own tool grant. */
internal class SampleMcpServer(
    scope: CoroutineScope,
    tools: List<AgentTool>,
    private val serverName: String = "Android sample",
    extraTools: List<SampleMcpTool> = emptyList(),
    private val authorize: (AgentTool) -> Boolean = { true },
) : Closeable {
    private val catalog = tools.associateBy(AgentTool::name)
    private val extraCatalog = extraTools.associateBy(SampleMcpTool::name)
    private val token = "Bearer ${UUID.randomUUID()}"
    private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val binding = AgentMcpServer("http://127.0.0.1:${server.localPort}/mcp",
        mapOf("Authorization" to token), catalog.keys + extraCatalog.keys)

    private val worker = scope.launch {
        while (isActive) {
            val socket = try { server.accept() } catch (failure: SocketException) {
                if (server.isClosed) break
                throw failure
            }
            socket.use {
                it.soTimeout = 2_000
                try { serve(it) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { runCatching { it.reply("400 Bad Request") } }
            }
        }
    }

    private suspend fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val line = input.line(4096)
        val headers = buildMap {
            repeat(50) {
                val header = input.line(8192)
                if (header.isEmpty()) return@buildMap
                put(header.substringBefore(':').lowercase(), header.substringAfter(':').trim())
            }
            error("Too many headers")
        }
        val given = headers["authorization"].orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            socket.reply("401 Unauthorized")
            return
        }
        if (!line.startsWith("POST /mcp HTTP/")) {
            socket.reply("405 Method Not Allowed")
            return
        }
        val length = headers["content-length"]?.toIntOrNull()
        require(length != null && length in 1..256 * 1024)
        val body = ByteArray(length)
        java.io.DataInputStream(input).readFully(body)
        val request = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        if ("id" !in request) {
            socket.reply("202 Accepted")
            return
        }
        val method = request.getValue("method").jsonPrimitive.content
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        val result = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", params["protocolVersion"] ?: JsonPrimitive("2025-03-26"))
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", serverName); put("version", "1.0") }
            }
            "ping" -> buildJsonObject {}
            "tools/list" -> buildJsonObject {
                putJsonArray("tools") {
                    for (tool in catalog.values.filter(authorize)) addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                    }
                    for (tool in extraCatalog.values) addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.schema)
                    }
                }
            }
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.content
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val extra = extraCatalog[name]
                if (extra != null) {
                    extra.execute(args)
                } else {
                    val result = catalog[name]?.takeIf(authorize)?.execute?.invoke(args)
                    buildJsonObject {
                        if (result != null) put("structuredContent", result) else put("isError", true)
                        putJsonArray("content") { addJsonObject {
                            put("type", "text"); put("text", result?.toString() ?: "Tool unavailable.")
                        } }
                    }
                }
            }
            else -> null
        }
        socket.reply("200 OK", buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", request.getValue("id"))
            if (result != null) put("result", result) else putJsonObject("error") {
                put("code", -32601); put("message", "Method not found.")
            }
        }.toString())
    }

    private fun Socket.reply(status: String, body: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nConnection: close\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun BufferedInputStream.line(limit: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        repeat(limit) {
            val byte = read()
            require(byte >= 0)
            if (byte == 10) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            bytes.write(byte)
        }
        error("HTTP line too long")
    }

    override fun close() {
        server.close()
        worker.cancel()
    }
}
