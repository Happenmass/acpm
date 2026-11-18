package dev.androidagent.sample

import android.app.Application
import android.util.AtomicFile
import dev.androidagent.client.SpeechServiceClient
import dev.androidagent.sample.androiduse.AndroidUseTurn
import dev.androidagent.sdk.AgentRuntime
import dev.androidagent.sdk.AgentSessionPersistence
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SampleAgentHost : Application() {
    internal val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val consoleClient by lazy { SpeechServiceClient(this, packageName) }

    internal val androidUse by lazy { AndroidUseTurn(this, agentScope) }

    override fun onCreate() {
        super.onCreate()
        val root = File(noBackupFilesDir, "android-agent")
        val file = AtomicFile(File(root, "sessions.json"))
        val storage = object : AgentSessionPersistence {
            override fun readSnapshot(): String? = try {
                file.readFully().toString(Charsets.UTF_8)
            } catch (_: FileNotFoundException) {
                null
            }

            override fun writeSnapshot(snapshot: String) {
                val stream = file.startWrite()
                try {
                    stream.write(snapshot.toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (failure: Exception) {
                    file.failWrite(stream)
                    throw failure
                }
            }
        }
        AgentRuntime.get(this, storage, root)
    }
}
