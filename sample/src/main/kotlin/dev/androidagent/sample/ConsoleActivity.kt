package dev.androidagent.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.androidagent.client.McpTurnOutcome
import dev.androidagent.sdk.AgentActivity
import dev.androidagent.sdk.AgentRuntime
import dev.androidagent.sdk.DeviceSupport
import dev.androidagent.speech.HtpAsr
import dev.androidagent.speech.HtpAvailability
import dev.androidagent.speech.HtpServiceState
import dev.androidagent.speech.HtpServiceStatus
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConsoleActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var scroll: ScrollView
    private var scope: CoroutineScope? = null
    private var section = Section.OVERVIEW
    private val models by lazy { SpeechModels(this) }
    private val agent by lazy { AgentRuntime.get(this) }
    private var agentStatus = emptyList<AgentActivity>()
    private var availableBackends = emptySet<String>()
    private var agentSupported: Boolean? = null
    private var availableAsr: HtpAvailability? = null
    private var availableSpeakers = false
    private var service = SpeechServiceActivity()
    private var htp = HtpServiceStatus()
    private val memory = ArrayDeque<Long>()
    private val zh get() = resources.configuration.locales[0].language == "zh"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = Section.entries.getOrElse(savedInstanceState?.getInt("section") ?: 0) { Section.OVERVIEW }
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        val root = column().apply { setBackgroundColor(BACKGROUND) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        root.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            pad(18, 11, 18, 7)
            addView(ImageView(this@ConsoleActivity).apply {
                background = panel(ACCENT_DARK, 16)
                setImageResource(R.drawable.ic_sdk_mark)
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(column().apply {
                pad(11, 0, 0, 0)
                addView(label("AI SDK", 22, PRIMARY, bold = true))
                addView(label(t("Runtime console", "运行控制台"), 13, MUTED))
            })
        })
        tabs = row().apply { pad(18, 5, 18, 8) }
        root.addView(tabs)
        body = column().apply { pad(18, 10, 18, 24) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        drawTabs()
        updateBody()
    }

    override fun onStart() {
        super.onStart()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).apply {
            launch {
                agentSupported = DeviceSupport.check(this@ConsoleActivity) is DeviceSupport.Supported
                withContext(Dispatchers.IO) { runCatching { models.prepareRuntime() } }
            }
            launch {
                McpRegistrations.records.collect { if (section == Section.MCP) updateBody() }
            }
            launch {
                while (true) {
                    val (asr, speakers, pss) = withContext(Dispatchers.IO) {
                        Triple(models.asrAvailability(), models.speakerModelsAvailable, Debug.getPss())
                    }
                    availableAsr = asr
                    availableSpeakers = speakers
                    availableBackends = agent.installedBackends().map { it.backend.name }.toSet()
                    agentStatus = agent.activity()
                    service = AiSpeechService.activity.value
                    htp = HtpAsr.serviceStatus()
                    memory.addLast(pss)
                    if (memory.size > 30) memory.removeFirst()
                    if (section != Section.MCP) updateBody()
                    delay(2_000)
                }
            }
        }
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("section", section.ordinal)
        super.onSaveInstanceState(outState)
    }

    private fun drawTabs() {
        tabs.removeAllViews()
        val labels = listOf(t("Overview", "概览"), "Agents", t("Models", "模型"), "MCP")
        Section.entries.forEachIndexed { index, item ->
            tabs.addView(label(labels[index], 14, if (section == item) ACCENT else MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                background = panel(if (section == item) ACCENT_DARK else CARD, 12)
                isSelected = section == item
                setOnClickListener {
                    section = item
                    scroll.scrollTo(0, 0)
                    drawTabs()
                    updateBody()
                }
            }, LinearLayout.LayoutParams(0, dp(45), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
    }

    private fun updateBody() {
        val previousScroll = scroll.scrollY
        body.removeAllViews()
        when (section) {
            Section.OVERVIEW -> overview()
            Section.AGENTS -> agents()
            Section.MODELS -> modelDetails()
            Section.MCP -> mcpDetails()
        }
        scroll.scrollTo(0, previousScroll)
    }

    private fun overview() {
        val hero = column().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(33, 63, 48), Color.rgb(22, 38, 32))).apply { cornerRadius = dp(22).toFloat() }
            pad(18, 16, 18, 18)
            addView(label(t("On-device inference", "端侧推理"), 14, ACCENT, bold = true))
            addView(label(htpState(), 30, PRIMARY, bold = true).apply { pad(0, 3, 0, 5) })
            addView(label("Qwen3-ASR · Qualcomm HTP", 13, MUTED))
            addView(label(asrDetails(), 13, PRIMARY).apply { pad(0, 9, 0, 0) })
        }
        body.addView(hero)
        val metrics = row().apply {
            metric(t("Service requests", "服务请求"), service.completed.toString(), t("This service process", "当前服务进程"))
            metric(t("Agent sessions", "Agent 会话"), agentStatus.sumOf { it.sessions }.toString(), t("SDK host history", "当前宿主历史"))
        }
        body.addView(metrics, spaced(12))
        body.addView(card {
            heading(t("Memory", "内存"))
            val pss = memory.lastOrNull()?.takeIf { it > 0 }
            addView(label(pss?.let(::mib) ?: "—", 28, PRIMARY, bold = true))
            addView(label(t("SDK process PSS", "SDK 应用进程 PSS"), 13, MUTED))
            addView(MemoryChart(), LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(9) })
            line(t("HTP worker · last RSS", "HTP worker · 最近 RSS"), htp.memoryRssKiB?.let(::mib) ?: "—")
            line(t("HTP worker · peak RSS", "HTP worker · 峰值 RSS"), htp.peakRssKiB?.let(::mib) ?: "—")
        }, spaced(12))
        body.addView(card {
            heading(t("Service calls", "服务调用"))
            line(t("In progress", "处理中"), service.active.toString())
            line(t("Failed", "失败"), service.failed.toString())
            if (service.callers.isEmpty()) addView(label(t("Waiting for the first client request", "等待客户端发来首个请求"), 13, MUTED))
            service.callers.entries.sortedByDescending { it.value }.forEach { (caller, count) -> line(caller, count.toString()) }
        }, spaced(12))
    }

    private fun agents() {
        body.addView(card {
            heading(t("Agent backends", "Agent 后端"))
            agentStatus.forEach { item ->
                val backend = item.backend.name
                val state = when {
                    item.awaitingApproval -> t("Awaiting approval", "等待授权")
                    item.running -> t("Running", "运行中")
                    backend !in availableBackends -> t("Not installed", "未安装")
                    agentSupported == false -> t("Unsupported", "设备不支持")
                    else -> t("Ready", "就绪")
                }
                line(backend, state)
                addView(label(t("${item.sessions} sessions  ·  ${item.turns} turns",
                    "${item.sessions} 个会话  ·  ${item.turns} 个回合"), 13, MUTED))
                item.lastStatus?.let { status ->
                    if (!item.running) line(t("Last result", "最近回合"), when (status) {
                        dev.androidagent.sdk.RunStatus.Succeeded -> t("Succeeded", "完成")
                        dev.androidagent.sdk.RunStatus.Failed -> t("Failed", "失败")
                        dev.androidagent.sdk.RunStatus.Cancelled -> t("Cancelled", "取消")
                        dev.androidagent.sdk.RunStatus.Interrupted -> t("Interrupted", "中断")
                        dev.androidagent.sdk.RunStatus.Running -> t("Running", "运行中")
                    })
                }
                val usage = item.context
                if (usage != null && usage.limitTokens > 0) {
                    line(t("Reported context", "已上报上下文"), "${usage.usedTokens} / ${usage.limitTokens}")
                    addView(ProgressBar(this@ConsoleActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = (usage.usedTokens.toDouble() / usage.limitTokens * 1000).toInt().coerceIn(0, max)
                        progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                        contentDescription = t("Reported context usage", "已上报上下文用量")
                    }, LinearLayout.LayoutParams(-1, dp(12)))
                } else addView(label(t("Context usage not reported", "暂无上下文用量上报"), 13, MUTED))
            }
            action(t("Open Agent tools", "打开 Agent 工具")) { startActivity(Intent(this@ConsoleActivity, MainActivity::class.java)) }
        })
        body.addView(card {
            heading(t("Tools & image output", "工具与图像输出"))
            val tools = agentStatus.flatMap { it.tools.entries }.groupingBy { it.key }.fold(0) { total, item -> total + item.value }
            if (tools.isEmpty()) addView(label(t("No tool calls reported", "暂无工具调用上报"), 13, MUTED))
            tools.entries.sortedByDescending { it.value }.forEach { (kind, count) -> line(kind, count.toString()) }
            line(t("Images reported by ACP", "ACP 上报的图像"), agentStatus.sumOf { it.images }.toString())
        }, spaced(12))
    }

    private fun modelDetails() {
        body.addView(card {
            heading("Qwen3-ASR")
            line(t("Backend", "推理后端"), "Qualcomm HTP")
            line(t("State", "状态"), htpState())
            addView(label(asrDetails(), 13, MUTED))
            line(t("Completed windows", "完成的窗口"), htp.completedWindows.toString())
            line(t("Last Decode", "最近一次 Decode"), htp.lastDecodeMs?.let { String.format(Locale.ROOT, "%.0f ms", it) } ?: "—")
        })
        body.addView(card {
            heading(t("Speaker analysis", "语音与说话人"))
            val state = if (availableSpeakers) t("Ready", "就绪") else t("Models not installed", "模型未安装")
            line("Pyannote VAD", state)
            line("ERes2NetV2", state)
            line(t("Speaker clustering", "声纹聚类"), t("Ready", "就绪"))
            action(t("Open speech tools", "打开语音工具")) { startActivity(Intent(this@ConsoleActivity, SpeechActivity::class.java)) }
        }, spaced(12))
    }

    private fun mcpDetails() {
        val (current, recent) = McpRegistrations.records.value.partition { it.outcome == null }
        body.heading(t("Mounted now (${current.size})", "当前挂载（${current.size}）"))
        if (current.isEmpty()) body.addView(card {
            addView(label(t("No active mounts. Host apps attach MCP tools when a turn starts.",
                "暂无挂载。宿主 App 启动 Agent 回合时会挂载 MCP 工具。"), 14, MUTED))
        })
        current.forEach { body.addView(mcpCard(it), spaced(8)) }

        body.addView(label(t("Recent mounts", "最近挂载"), 16, PRIMARY, bold = true).apply {
            pad(0, 18, 0, 4)
        })
        body.addView(label(t("Reports received in this SDK process", "当前 SDK 进程收到的上报"), 13, MUTED))
        if (recent.isEmpty()) body.addView(card {
            addView(label(t("No finished mounts yet", "暂无已结束的挂载"), 14, MUTED))
        }, spaced(8))
        recent.forEach { body.addView(mcpCard(it), spaced(8)) }
    }

    private fun mcpCard(record: McpRegistration) = card {
        heading(record.host)
        line(t("Status", "状态"), when (record.outcome) {
            null -> t("Mounted", "已挂载")
            McpTurnOutcome.Succeeded -> t("Turn completed", "回合已完成")
            McpTurnOutcome.Failed -> t("Turn failed", "回合失败")
            McpTurnOutcome.Cancelled -> t("Turn cancelled", "回合已取消")
            McpTurnOutcome.Interrupted -> t("Interrupted", "已中断")
        })
        line(t("Agent backend", "Agent 后端"), record.backend.name)
        line(t("Local address", "回环地址"), "127.0.0.1:${record.port}")
        line(t("Transport", "传输"), "Streamable HTTP")
        val time = DateFormat.getTimeFormat(this@ConsoleActivity)
        line(t("Mounted at", "挂载时间"), time.format(Date(record.mountedAt)))
        record.endedAt?.let { line(t("Ended at", "结束时间"), time.format(Date(it))) }
        val tools = if (record.tools.isEmpty()) t("Tool names not reported", "宿主未上报工具名")
            else record.tools.joinToString("\n")
        addView(label(t("Declared tools", "宿主声明的工具"), 13, MUTED).apply { pad(0, 6, 0, 4) })
        addView(label(tools, 13, PRIMARY))
    }

    private fun htpState(): String = when (htp.state) {
        HtpServiceState.LOADING -> t("Loading model", "加载模型")
        HtpServiceState.RUNNING -> t("Running inference", "正在推理")
        HtpServiceState.WARM -> t("Model warm", "模型已驻留")
        HtpServiceState.UNLOADED -> t("Model unloaded", "模型已卸载")
        HtpServiceState.STOPPED -> when (availableAsr) {
            HtpAvailability.READY -> t("Ready", "等待请求")
            null -> t("Checking", "正在检查")
            else -> t("Unavailable", "暂不可用")
        }
    }

    private fun asrDetails() = when (availableAsr) {
        HtpAvailability.READY -> t("Loads on first request · unloads after 1 h idle", "首次请求加载 · 空闲 1 小时卸载")
        HtpAvailability.UNSUPPORTED_DEVICE -> t("HTP unavailable on this device", "当前设备不支持此 HTP 模型")
        HtpAvailability.RUNTIME_NOT_INSTALLED -> t("HTP runtime not installed", "未安装 HTP 运行库")
        HtpAvailability.MODELS_NOT_INSTALLED -> t("Qwen weights not installed", "未安装 Qwen 权重")
        null -> Build.MODEL
    }

    private fun LinearLayout.metric(title: String, value: String, detail: String) {
        val index = childCount
        addView(card {
            addView(label(title, 13, MUTED, bold = true))
            addView(label(value, 29, PRIMARY, bold = true).apply { pad(0, 3, 0, 3) })
            addView(label(detail, 12, MUTED))
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(10) })
    }

    private fun LinearLayout.heading(title: String) { addView(label(title, 16, PRIMARY, bold = true).apply { pad(0, 0, 0, 8) }) }

    private fun LinearLayout.line(title: String, value: String) {
        addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            pad(0, 6, 0, 6)
            addView(label(title, 13, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(value, 13, PRIMARY, bold = true).apply { gravity = Gravity.END; pad(8, 0, 0, 0) })
        })
    }

    private fun LinearLayout.action(title: String, onClick: () -> Unit) {
        addView(label(title, 14, ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            background = panel(ACCENT_DARK, 12)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(13) })
    }

    private fun card(content: LinearLayout.() -> Unit) = column().apply {
        background = panel(CARD, 18, BORDER)
        pad(16, 14, 16, 14)
        content()
    }

    private fun panel(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun spaced(gap: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun View.pad(left: Int, top: Int, right: Int, bottom: Int) = setPadding(dp(left), dp(top), dp(right), dp(bottom))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun t(en: String, cn: String) = if (zh) cn else en
    private fun mib(kib: Long) = String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0)

    private inner class MemoryChart : View(this@ConsoleActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            paint.color = BORDER
            paint.strokeWidth = dp(1).toFloat()
            for (part in 1..3) {
                val y = height * part / 4f
                canvas.drawLine(0f, y, width.toFloat(), y, paint)
            }
            val readings = memory.toList()
            if (readings.size < 2) return
            val minimum = readings.min() - 4096
            val maximum = readings.max() + 4096
            val line = Path()
            readings.forEachIndexed { index, value ->
                val x = index * width.toFloat() / (readings.size - 1)
                val y = height - (value - minimum).toFloat() / (maximum - minimum) * height
                if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            paint.color = ACCENT
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            canvas.drawPath(line, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private enum class Section { OVERVIEW, AGENTS, MODELS, MCP }
    private companion object {
        val BACKGROUND = Color.rgb(15, 22, 19)
        val CARD = Color.rgb(23, 33, 28)
        val BORDER = Color.rgb(43, 62, 51)
        val ACCENT_DARK = Color.rgb(38, 61, 46)
        val ACCENT = Color.rgb(211, 246, 164)
        val PRIMARY = Color.rgb(241, 247, 240)
        val MUTED = Color.rgb(171, 192, 179)
    }
}
