package dev.androidagent.sample.androiduse

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.Closeable
import kotlin.math.roundToInt

/** This local UI is owned by the service and stays outside the MCP tool surface. */
internal object AndroidUseConsent {
    fun show(service: AndroidUseService, packageName: String, title: String?, decide: (Boolean) -> Unit): Closeable {
        val manager = service.getSystemService(WindowManager::class.java)
        val density = service.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val label = try {
            service.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                .loadLabel(service.packageManager).toString().take(80)
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
        val window = title?.takeIf { it.isNotBlank() && it != label }
        val detail = listOfNotNull(label, packageName.takeIf { it != label }, window).joinToString("\n")
        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(160, 174, 190))
            }
            elevation = dp(8).toFloat()
            addView(text(context, "AI SDK · Android Use", 13f))
            addView(text(context, "Allow access to this window?", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(text(context, detail, 14f).apply { setPadding(0, dp(8), 0, dp(8)) })
            addView(text(context, "Screen content may be sent to the configured model.", 13f))
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(button(context, "Deny") { decide(false) })
                addView(button(context, "Allow once") { decide(true) })
            })
        }
        val layout = WindowManager.LayoutParams(
            minOf(dp(380), service.resources.displayMetrics.widthPixels - dp(20)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
            setTitle("AI SDK window approval")
        }
        manager.addView(content, layout)
        return Closeable { manager.removeViewImmediate(content) }
    }

    private fun text(context: Context, label: String, size: Float) = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(Color.rgb(20, 31, 43))
    }

    private fun button(context: Context, label: String, decide: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        filterTouchesWhenObscured = true
        setOnClickListener { decide() }
    }
}
