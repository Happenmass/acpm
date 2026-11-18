package dev.androidagent.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dev.androidagent.client.McpTurnOutcome
import dev.androidagent.sample.androiduse.AndroidUseSession
import dev.androidagent.sdk.AgentRuntime
import dev.androidagent.sdk.AgentRun
import dev.androidagent.sdk.DeviceSupport
import dev.androidagent.sdk.ModelEndpoint
import dev.androidagent.sdk.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime by lazy { AgentRuntime.get(applicationContext) }
    private val preferences by lazy { getSharedPreferences("sample-model", MODE_PRIVATE) }
    private val notes by lazy { SampleNotes(applicationContext) }
    private lateinit var promptField: EditText
    private lateinit var noteStatus: TextView
    private lateinit var revokeNote: Button
    private var androidTarget: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installed = runtime.installedBackends()
        val listing = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                installed.map { "${it.backend.name} · ${it.version}" })
        }
        val endpoint = field("Model base URL", preferences.getString("endpoint", "").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val model = field("Model identifier", preferences.getString("model", "").orEmpty())
        val credential = field("API key if required", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { isSaveEnabled = false }
        val task = field("Task", "Discover the Android host's read_note tool if needed. " +
            "Call it with id demo and tell me the release codename from its result. Use the host tool.")
        promptField = task
        noteStatus = TextView(this)
        val selectNote = Button(this).apply { text = "Choose text document" }
        revokeNote = Button(this).apply { text = "Revoke document access" }
        val status = TextView(this)
        val output = TextView(this).apply { setTextIsSelectable(true) }
        val fresh = CheckBox(this).apply { text = "Start a new session" }
        val scripts = CheckBox(this).apply { text = "Allow JavaScript in the demo workspace" }
        val background = CheckBox(this).apply { text = "Keep running when I leave the app" }
        val androidUse = CheckBox(this).apply { text = "Allow Android Use for this task" }
        val chooseApp = Button(this).apply {
            text = "Choose Android app"
            visibility = View.GONE
            setOnClickListener { chooseAndroidApp(this) }
        }
        val accessibility = Button(this).apply {
            text = "Android Accessibility settings"
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        androidUse.setOnCheckedChangeListener { _, enabled ->
            chooseApp.visibility = if (enabled) View.VISIBLE else View.GONE
            accessibility.visibility = chooseApp.visibility
        }
        val run = Button(this).apply { text = "Run task"; isEnabled = false }
        val cancel = Button(this).apply { text = "Cancel"; isEnabled = false }
        val speech = Button(this).apply {
            text = "Speech tools"
            setOnClickListener { startActivity(Intent(this@MainActivity, SpeechActivity::class.java)) }
        }
        val title = TextView(this).apply { text = "Local Agent"; textSize = 28f }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
            for (view in listOf<View>(title, speech, status, listing, endpoint, model, credential, noteStatus, selectNote, revokeNote, task, fresh, scripts, background, androidUse, chooseApp, accessibility, run, cancel, output)) {
                addView(view)
            }
        }
        val screen = ScrollView(this).apply {
            isFillViewport = true
            addView(form)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(screen)
        background.setOnCheckedChangeListener { _, enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 105)
            }
        }
        showNoteStatus()
        selectNote.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "text/plain"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, SELECT_NOTE)
        }
        revokeNote.setOnClickListener {
            notes.revoke()
            showNoteStatus()
        }

        var supported = false
        scope.launch {
            if (runtime.activeRun.value != null) return@launch
            val session = runtime.sessions().lastOrNull { it.workspace == "demo" } ?: return@launch
            val previous = runtime.history(session).lastOrNull() ?: return@launch
            if (runtime.activeRun.value == null) {
                output.text = listOfNotNull(previous.answer.ifEmpty { null }, previous.message, previous.status.name)
                    .joinToString("\n\n")
                val index = installed.indexOfFirst { it.backend == session.backend }
                if (index >= 0) listing.setSelection(index)
            }
        }
        scope.launch {
            status.text = when (val support = DeviceSupport.check(applicationContext)) {
                is DeviceSupport.Supported -> {
                    supported = true
                    if (installed.isEmpty()) "Install a build with a backend pack." else "Local agents ready"
                }
                DeviceSupport.UnsupportedArchitecture -> "This build requires an ARM64 device."
                DeviceSupport.MissingLauncher -> "Install a build with the extracted sandbox launcher."
                DeviceSupport.LauncherFailed -> "Android could not start the sandbox launcher."
                DeviceSupport.IsolationBlockedByAppPolicy -> "Android app policy blocks Landlock on this device."
                is DeviceSupport.IsolationUnavailable -> "This device cannot provide Landlock ABI 6."
            }
            run.isEnabled = supported && installed.isNotEmpty() && runtime.activeRun.value == null
        }

        scope.launch {
            runtime.activeRun.filterNotNull().collect { handle ->
                run.isEnabled = false
                cancel.isEnabled = true
                val approvals = launch { showApprovals(handle) }
                val final = handle.state.first { turn ->
                    output.text = listOfNotNull(turn.activeTool?.let { "Tool: $it" }, turn.answer.ifEmpty { null },
                        turn.message, turn.status.takeIf { it != RunStatus.Running }?.name).joinToString("\n\n")
                    turn.status != RunStatus.Running
                }
                approvals.cancel()
                cancel.isEnabled = false
                run.isEnabled = supported && installed.isNotEmpty()
                if (final.answer.isBlank() && final.message == null) output.text = final.status.name
            }
        }
        cancel.setOnClickListener {
            AndroidUseSession.revoke()
            runtime.activeRun.value?.cancel()
        }

        run.setOnClickListener {
            val backend = installed.getOrNull(listing.selectedItemPosition)?.backend ?: return@setOnClickListener
            val base = endpoint.text.toString().trim()
            val modelId = model.text.toString().trim()
            val target = androidTarget
            if (androidUse.isChecked) {
                if (target == null) {
                    output.text = "Choose the Android app this task may operate."
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 105)
                    output.text = "Enable notifications to show the Android Use stop control."
                    return@setOnClickListener
                }
                val endpointHost = runCatching { java.net.URI(base).host }.getOrNull()
                AlertDialog.Builder(this)
                    .setTitle("Allow Android Use for this task?")
                    .setMessage("The agent may read and operate $target while it is in the foreground. " +
                        "Screen content can be sent to the configured model (${endpointHost ?: "model endpoint"}). " +
                        "If the task opens another app or a system window, AI SDK will ask before allowing access. " +
                        "Use the task notification to stop access at any time.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Allow this task") { _, _ ->
                        run.isEnabled = false
                        scope.launch {
                            try {
                                val handle = (application as SampleAgentHost).androidUse.start(
                                    backend, task.text.toString(),
                                    ModelEndpoint(base, modelId, credential.text.toString().ifBlank { null }), target,
                                )
                                try {
                                    startTaskService(handle.id)
                                } catch (_: IllegalStateException) {
                                    AndroidUseSession.revoke()
                                    handle.cancel()
                                    output.text = "Android did not allow the task notification."
                                }
                            } catch (failure: IllegalArgumentException) {
                                output.text = failure.message
                                run.isEnabled = supported
                            } catch (_: dev.androidagent.sdk.AgentBusyException) {
                                output.text = "Another task is running."
                            }
                        }
                    }.show()
                return@setOnClickListener
            }
            run.isEnabled = false
            scope.launch {
                try {
                    val endpoint = ModelEndpoint(base, modelId, credential.text.toString().ifBlank { null })
                    preferences.edit().putString("endpoint", base).putString("model", modelId).apply()
                    val previous = if (fresh.isChecked) null else runtime.sessions()
                        .lastOrNull { it.backend == backend && it.workspace == "demo" }
                    val session = previous ?: runtime.openSession(backend, "demo")
                    fresh.isChecked = false
                    val sessionTools = if (scripts.isChecked) {
                        listOf(notes.readNote, runtime.workspaceScriptTool(session.workspace))
                    } else listOf(notes.readNote)
                    val host = application as SampleAgentHost
                    val hostScope = host.agentScope
                    val mcp = SampleMcpServer(hostScope, sessionTools)
                    val handle = try {
                        runtime.submitWithHostMcp(session, task.text.toString(), endpoint, mcp.binding)
                    } catch (failure: Exception) {
                        mcp.close()
                        throw failure
                    }
                    host.consoleClient.observeMcp(hostScope, backend.name, mcp.binding.port, mcp.binding.toolNames) {
                        McpTurnOutcome.valueOf(handle.state.first { it.status != RunStatus.Running }.status.name)
                    }
                    hostScope.launch {
                        mcp.use { handle.state.first { it.status != RunStatus.Running } }
                    }
                    if (background.isChecked) {
                        try {
                            startTaskService(handle.id)
                        } catch (_: IllegalStateException) {
                            status.text = "Android did not allow background execution for this task."
                        }
                    }
                } catch (failure: IllegalArgumentException) {
                    output.text = failure.message
                    run.isEnabled = supported
                } catch (_: dev.androidagent.sdk.AgentBusyException) {
                    output.text = "Another task is running."
                }
            }
        }
    }

    private fun startTaskService(runId: String) {
        startForegroundService(Intent(this, AgentTaskService::class.java).putExtra(AgentTaskService.RUN_ID, runId))
    }

    private fun chooseAndroidApp(button: Button) {
        @Suppress("DEPRECATION")
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this).setTitle("Choose an Android app")
            .setItems(apps.map { (label, packageName) -> "$label ($packageName)" }.toTypedArray()) { _, index ->
                val (label, packageName) = apps[index]
                androidTarget = packageName
                button.text = label
            }.setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SELECT_NOTE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            notes.select(uri)
            promptField.setText("Use read_note with id document and summarize the selected note.")
            showNoteStatus()
        } catch (_: SecurityException) {
            noteStatus.text = "The provider did not grant access to this document."
        }
    }

    private fun showNoteStatus() {
        val selected = notes.hasDocument()
        noteStatus.text = if (selected) "Selected document available to read_note" else "Using the synthetic sample note"
        revokeNote.isEnabled = selected
    }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            this.hint = hint
            inputType = type
            setText(value)
        }

    private suspend fun showApprovals(run: AgentRun) {
        run.approval.collectLatest { request ->
            if (request == null || (application as SampleAgentHost).androidUse.runId == run.id) return@collectLatest
            val dialog = AlertDialog.Builder(this@MainActivity).setTitle(request.title)
                .setItems(request.options.map { it.label }.toTypedArray()) { _, index ->
                    run.respondToApproval(request.id, request.options[index].id)
                }
                .setNegativeButton("Cancel") { _, _ -> run.respondToApproval(request.id, null) }
                .setOnCancelListener { run.respondToApproval(request.id, null) }
                .create()
            dialog.show()
            try {
                awaitCancellation()
            } finally {
                dialog.dismiss()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    private companion object {
        const val SELECT_NOTE = 104
    }
}
