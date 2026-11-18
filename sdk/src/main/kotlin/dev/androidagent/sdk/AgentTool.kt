package dev.androidagent.sdk

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

internal const val HOST_MCP_SERVER_NAME = "android_host"

/** A host-owned Streamable HTTP MCP server bound to the Android loopback interface. */
class AgentMcpServer(
    url: String,
    headers: Map<String, String> = emptyMap(),
    toolNames: Set<String> = emptySet(),
) {
    internal val url = url.toHttpUrl().also {
        require(it.scheme == "http" && it.host == "127.0.0.1" && it.port > 1023 &&
            it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) {
            "Use a host-owned loopback MCP endpoint on an ephemeral port."
        }
    }
    internal val headers = headers.toMap()
    val port: Int get() = url.port
    val toolNames: Set<String> = toolNames.toSet()
}

/**
 * A tool implemented by the host. Check access to specific objects in [execute].
 * Tool arguments are untrusted even when the tool appears in the approved catalog.
 */
class AgentTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val execute: suspend (JsonObject) -> JsonObject,
) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid MCP tool name." }
    }
}
