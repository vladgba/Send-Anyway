package x.vladgba.sendanyway.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import x.vladgba.sendanyway.R
import java.util.Locale

internal val mainHandler = Handler(Looper.getMainLooper())

internal fun ui(block: () -> Unit) {
    mainHandler.post(block)
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

internal fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

internal fun android.content.Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Visual state of a status badge: icon + container colour. */
internal enum class Tone(val icon: Int, val container: Int, val content: Int) {
    INFO(R.drawable.ic_qr_code, R.color.accent_container, R.color.on_accent_container),
    WORKING(R.drawable.ic_swap, R.color.accent_container, R.color.on_accent_container),
    SUCCESS(R.drawable.ic_check, R.color.success_container, R.color.success),
    WARNING(R.drawable.ic_alert, R.color.warning_container, R.color.warning),
    ERROR(R.drawable.ic_alert, R.color.error_container, R.color.error),
}

/** Recolours a round [R.style.IconBadge] to show [tone]. */
internal fun ImageView.showTone(tone: Tone) {
    setImageResource(tone.icon)
    backgroundTintList = ColorStateList.valueOf(context.getColor(tone.container))
    imageTintList = ColorStateList.valueOf(context.getColor(tone.content))
}

/** Same as [showTone] but with any icon, in the default accent colours. */
internal fun ImageView.showBadge(icon: Int, container: Int = R.color.accent_container, content: Int = R.color.on_accent_container) {
    setImageResource(icon)
    backgroundTintList = ColorStateList.valueOf(context.getColor(container))
    imageTintList = ColorStateList.valueOf(context.getColor(content))
}

/** A round icon badge, built in code (list rows, result cards). */
internal fun android.content.Context.iconBadge(icon: Int, sizeDp: Int = 40): ImageView =
    ImageView(this, null, 0, R.style.IconBadge).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        showBadge(icon)
    }

internal fun Activity.missingPermissions(perms: Array<String>): Array<String> =
    perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

/** Full brightness + screen stays on while a QR code is displayed. */
internal fun Activity.qrScreenMode(on: Boolean) {
    window.attributes = window.attributes.apply {
        screenBrightness = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/** Rate-limits progress updates coming from worker threads. */
internal class Throttle(private val intervalMs: Long = 100) {
    private var last = 0L
    fun ready(force: Boolean = false): Boolean {
        val now = SystemClock.uptimeMillis()
        if (!force && now - last < intervalMs) return false
        last = now
        return true
    }
}

/** Tracks transfer progress from any thread: speed for the UI, idle time for the stall watchdog. */
internal class TransferMeter {
    private val start = SystemClock.uptimeMillis()
    @Volatile private var lastChange = start
    @Volatile private var last = 0L

    fun update(done: Long) {
        if (done != last) {
            last = done
            lastChange = SystemClock.uptimeMillis()
        }
    }

    fun idleMs(): Long = SystemClock.uptimeMillis() - lastChange

    fun speed(): String {
        val ms = maxOf(1L, SystemClock.uptimeMillis() - start)
        return formatSize(last * 1000 / ms) + "/s"
    }
}

/** Calls [onStall] (main thread) once if the meter hasn't moved for [limitMs]. */
internal class StallWatchdog(
    private val meter: TransferMeter,
    private val limitMs: Long,
    private val onStall: () -> Unit,
) : Runnable {
    fun start() = mainHandler.postDelayed(this, CHECK_MS)
    fun stop() = mainHandler.removeCallbacks(this)
    override fun run() {
        if (meter.idleMs() > limitMs) onStall() else mainHandler.postDelayed(this, CHECK_MS)
    }

    private companion object { const val CHECK_MS = 2_000L }
}

internal const val STALL_LIMIT_MS = 20_000L

/** Copies [text] to the clipboard, marked sensitive so Android 13+ doesn't preview it. */
internal fun Activity.copySecret(label: String, text: String) {
    val clip = android.content.ClipData.newPlainText(label, text)
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
    // Android 13+ shows its own "copied" confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) {
        android.widget.Toast.makeText(this, x.vladgba.sendanyway.R.string.password_copied, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Needed on Android 13+ to show the transfer progress notification (the transfer works without it). */
internal fun notificationPermission(): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

@Suppress("DEPRECATION")
internal fun android.content.Context.vibrateTick() {
    val v = getSystemService(android.os.Vibrator::class.java) ?: return
    try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            v.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
        } else if (android.os.Build.VERSION.SDK_INT >= 26) {
            v.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(20)
        }
    } catch (_: Exception) {}
}

@Suppress("DEPRECATION")
internal fun android.content.Context.vibrateSuccess() {
    val v = getSystemService(android.os.Vibrator::class.java) ?: return
    try {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 80), -1))
        } else {
            v.vibrate(longArrayOf(0, 40, 60, 80), -1)
        }
    } catch (_: Exception) {}
}

internal fun Activity.checkBatteryOptimization() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
    val pm = getSystemService(android.os.PowerManager::class.java) ?: return
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        try {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {}
    }
}

