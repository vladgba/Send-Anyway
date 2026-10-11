package x.vladgba.sendanyway.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import x.vladgba.sendanyway.core.QrAssembler
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Grid with one cell per QR chunk: grey = missing, green = received, blue = rebuilt from repair
 * frames. The cell of the frame just scanned is outlined; for a repair frame, every chunk it
 * covers is outlined.
 */
class QrMapView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var assembler: QrAssembler? = null
    private val cell = RectF()
    private val density = resources.displayMetrics.density

    private val missing = Paint().apply { color = 0x55808080 }
    private val received = Paint().apply { color = 0xFF2EAD5B.toInt() }
    private val recovered = Paint().apply { color = 0xFF3D7BF0.toInt() }
    private val current = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFA000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val covered = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFA000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
    }

    fun show(a: QrAssembler?) {
        assembler = a
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val a = assembler ?: return
        val k = a.count
        if (k == 0 || width == 0 || height == 0) return
        // Columns so that square cells fill the view's aspect ratio.
        val cols = max(1, ceil(sqrt(k.toDouble() * width / height)).toInt())
        val rows = ceil(k.toDouble() / cols).toInt()
        val size = minOf(width.toFloat() / cols, height.toFloat() / rows)
        val gap = if (size > 6 * density) density else 0f
        val repairCells = a.lastRepair.toHashSet()
        for (i in 0 until k) {
            val x = (i % cols) * size
            val y = (i / cols) * size
            cell.set(x + gap / 2, y + gap / 2, x + size - gap / 2, y + size - gap / 2)
            canvas.drawRect(
                cell,
                when (a.stateOf(i)) {
                    1 -> received
                    2 -> recovered
                    else -> missing
                },
            )
            when {
                i == a.lastIndex -> canvas.drawRect(cell, current)
                i in repairCells -> canvas.drawRect(cell, covered)
            }
        }
    }
}
