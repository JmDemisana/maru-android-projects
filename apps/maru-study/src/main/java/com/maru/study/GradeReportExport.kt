package com.maru.study

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GradeReportRow(
    val label: String,
    val detail: String,
    val value: String,
    val accent: Int,
    val difficulty: String,
)

/**
 * Draws the grade report PNGs. Everything is laid out by hand on a 1080px
 * canvas so the image stays crisp when it is posted or sent to classmates.
 */
object GradeReportExport {

    private const val WIDTH = 1080
    private const val PAD = 72f

    private const val BG_TOP = 0xFF18191A.toInt()
    private const val BG_MID = 0xFF111315.toInt()
    private const val BG_BOTTOM = 0xFF0B0D0F.toInt()
    private const val CARD = 0xFF1B1D20.toInt()
    private const val CARD_BORDER = 0xFF2C2F33.toInt()
    private const val INK = 0xFFE4E6EB.toInt()
    private const val MUTED = 0xFFB0B3B8.toInt()
    private const val FAINT = 0xFF82858B.toInt()
    private const val HAIRLINE = 0xFF3A3B3C.toInt()
    private const val ACCENT = 0xFF2A7FFF.toInt()

    private val difficultyColors = intArrayOf(
        0xFF34D399.toInt(),
        0xFF4ADE80.toInt(),
        0xFFFBBF24.toInt(),
        0xFFFB923C.toInt(),
        0xFFF87171.toInt(),
    )
    private val difficultyLabels = arrayOf(
        "Already passed",
        "Comfortable",
        "Manageable",
        "Demanding",
        "Critical",
    )

    // -----------------------------------------------------------------------
    // Single subject
    // -----------------------------------------------------------------------

    fun saveSingle(
        context: Context,
        course: String,
        prelim: String,
        midterm: String,
    ): String? {
        val required = computeRequiredEndterm(prelim, midterm)
        val difficulty = difficultyFor(required)
        val accent = difficulty?.color?.toArgb() ?: ACCENT
        val subject = course.trim().ifBlank { "Grade solver" }
        val passed = required != null && required <= 0.0

        val height = 1120
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, height)
        drawBrand(canvas)

        val titlePaint = text(52f, INK, bold = true)
        val titleLines = wrap(subject, titlePaint, WIDTH - PAD * 2, 2)
        titleLines.forEachIndexed { index, line ->
            canvas.drawText(fit(line, titlePaint, WIDTH - PAD * 2), PAD, 200f + index * 58f, titlePaint)
        }

        if (passed) {
            canvas.drawText("PASSED", PAD, 420f, text(96f, accent, bold = true))
            canvas.drawText("already passed", PAD, 466f, text(24f, MUTED))
        } else {
            val valueText = formatRequiredEndterm(required)
            var valueSize = 180f
            var valuePaint = text(valueSize, accent, bold = true)
            while (valueSize > 96f && valuePaint.measureText(valueText) > 520f) {
                valueSize -= 6f
                valuePaint = text(valueSize, accent, bold = true)
            }
            valuePaint.shader = LinearGradient(
                PAD,
                0f,
                PAD + valuePaint.measureText(valueText),
                0f,
                intArrayOf(accent, lighten(accent, 0.42f)),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawText(valueText, PAD, 420f, valuePaint)
            canvas.drawText("required endterm", PAD, 466f, text(24f, MUTED))
        }

        drawDifficultyMeter(canvas, required, accent)

        val cardTop = 690f
        val cardGap = 20f
        val cardWidth = (WIDTH - PAD * 2 - cardGap) / 2f
        drawStatCard(canvas, PAD, cardTop, cardWidth, "PRELIM", gradeText(prelim))
        drawStatCard(canvas, PAD + cardWidth + cardGap, cardTop, cardWidth, "MIDTERM", gradeText(midterm))

        canvas.drawText(
            "30% prelim   ·   30% midterm   ·   40% endterm",
            PAD,
            916f,
            text(21f, FAINT),
        )

        drawFooter(canvas, height)
        return save(context, bitmap, "grade-${slug(subject, "subject")}")
    }

    // -----------------------------------------------------------------------
    // Bulk report
    // -----------------------------------------------------------------------

    fun saveBulk(context: Context, subjects: List<Triple<String, String, String>>): String? {
        val rows = subjects.mapIndexed { index, (course, prelim, midterm) ->
            val required = computeRequiredEndterm(prelim, midterm)
            GradeReportRow(
                label = course.trim().ifBlank { "Subject ${index + 1}" },
                detail = "Prelim ${gradeText(prelim)}   ·   Midterm ${gradeText(midterm)}",
                value = formatRequiredEndterm(required),
                accent = difficultyFor(required)?.color?.toArgb() ?: FAINT,
                difficulty = difficultyLabels[difficultyIndex(required)],
            )
        }

        val rowHeight = 102f
        val rowGap = 16f
        val rowStart = 330f
        val height = (rowStart + rows.size * (rowHeight + rowGap) + 150f).toInt()
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, height)
        drawBrand(canvas)

        val title = classCountLabel(rows.size)
        canvas.drawText(title, PAD, 210f, text(52f, INK, bold = true))

        var top = rowStart
        rows.forEach { row ->
            drawReportRow(canvas, row, top, rowHeight)
            top += rowHeight + rowGap
        }

        drawFooter(canvas, height)
        return save(context, bitmap, "grades")
    }

    // -----------------------------------------------------------------------
    // Pieces
    // -----------------------------------------------------------------------

    private fun drawBackground(canvas: Canvas, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(BG_TOP, BG_MID, BG_BOTTOM),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), paint)

        // Soft blue glow in the top right corner, same blue as the app chrome.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                WIDTH - 60f,
                40f,
                700f,
                intArrayOf(addAlpha(ACCENT, 0x24), addAlpha(ACCENT, 0x00)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 760f, glow)

        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 8f, fill(ACCENT))
    }

    private fun drawBrand(canvas: Canvas) {
        canvas.drawText(
            "MARU STUDY",
            PAD,
            86f,
            text(24f, INK, bold = true).apply { letterSpacing = 0.32f },
        )

        canvas.drawRect(PAD, 114f, WIDTH - PAD, 115f, fill(HAIRLINE))
    }

    private fun drawDifficultyMeter(canvas: Canvas, required: Double?, accent: Int) {
        canvas.drawText(
            "DIFFICULTY",
            PAD,
            566f,
            text(18f, FAINT, bold = true).apply { letterSpacing = 0.2f },
        )

        val activeIndex = difficultyIndex(required)
        val label = difficultyLabels[activeIndex]
        val labelPaint = text(19f, if (required == null) FAINT else accent, bold = true)
        canvas.drawText(label, WIDTH - PAD - labelPaint.measureText(label), 566f, labelPaint)

        val gap = 8f
        val segmentWidth = (WIDTH - PAD * 2 - gap * 4) / 5f
        var x = PAD
        repeat(5) { index ->
            val color = difficultyColors[index]
            val active = index == activeIndex && required != null
            val rect = RectF(x, 588f, x + segmentWidth, 604f)
            canvas.drawRoundRect(rect, 8f, 8f, fill(if (active) color else addAlpha(color, 0x2E)))
            if (active) {
                val border = fill(0).apply {
                    this.color = color
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                canvas.drawRoundRect(rect, 8f, 8f, border)
            }
            x += segmentWidth + gap
        }
    }

    private fun drawStatCard(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        label: String,
        value: String,
    ) {
        val rect = RectF(left, top, left + width, top + 150f)
        canvas.drawRoundRect(rect, 20f, 20f, fill(CARD))
        canvas.drawRoundRect(rect, 20f, 20f, stroke(CARD_BORDER))

        canvas.drawText(
            label,
            left + 24f,
            top + 44f,
            text(18f, FAINT, bold = true).apply { letterSpacing = 0.18f },
        )

        canvas.drawText(value, left + 24f, top + 112f, text(46f, INK, bold = true))
    }

    private fun drawReportRow(canvas: Canvas, row: GradeReportRow, top: Float, height: Float) {
        val rect = RectF(PAD, top, WIDTH - PAD, top + height)
        canvas.drawRoundRect(rect, 20f, 20f, fill(CARD))
        canvas.drawRoundRect(rect, 20f, 20f, stroke(CARD_BORDER))

        canvas.drawRoundRect(
            RectF(PAD + 18f, top + 24f, PAD + 22f, top + height - 24f),
            2f,
            2f,
            fill(row.accent),
        )

        val labelPaint = text(30f, INK, bold = true)
        canvas.drawText(
            fit(row.label, labelPaint, 520f),
            PAD + 42f,
            top + 48f,
            labelPaint,
        )

        canvas.drawText(
            fit(row.detail, text(19f, FAINT), 520f),
            PAD + 42f,
            top + 78f,
            text(19f, FAINT),
        )

        val valuePaint = text(36f, row.accent, bold = true)
        canvas.drawText(
            row.value,
            WIDTH - PAD - 24f - valuePaint.measureText(row.value),
            top + 50f,
            valuePaint,
        )

        val difficultyPaint = text(16f, row.accent)
        canvas.drawText(
            row.difficulty.lowercase(Locale.US),
            WIDTH - PAD - 24f - difficultyPaint.measureText(row.difficulty.lowercase(Locale.US)),
            top + 78f,
            difficultyPaint,
        )
    }

    private fun drawFooter(canvas: Canvas, height: Int) {
        canvas.drawRect(PAD, height - 104f, WIDTH - PAD, height - 103f, fill(HAIRLINE))

        val stamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US))
        canvas.drawText("Generated $stamp", PAD, height - 56f, text(19f, FAINT))

        val tag = "made with <3 by Maru and Nanami"
        val tagPaint = text(19f, FAINT)
        canvas.drawText(tag, WIDTH - PAD - tagPaint.measureText(tag), height - 56f, tagPaint)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun gradeText(raw: String): String =
        parseGrade(raw)?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

    private fun difficultyIndex(required: Double?): Int = when {
        required == null -> 0
        required <= 0.0 -> 0
        required <= 4.0 -> 1
        required <= 6.5 -> 2
        required <= 8.5 -> 3
        else -> 4
    }

    private fun text(size: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
        }

    private fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private fun addAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun lighten(color: Int, amount: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf("")

        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        if (lines.size <= maxLines) return lines

        val head = lines.take(maxLines).toMutableList()
        head[maxLines - 1] = fit(head[maxLines - 1] + "…", paint, maxWidth)
        return head
    }

    private fun slug(raw: String, fallback: String): String = raw
        .uppercase(Locale.US)
        .replace(Regex("[^A-Z0-9]+"), "-")
        .trim('-')
        .take(28)
        .ifBlank { fallback }

    private fun save(context: Context, bitmap: Bitmap, prefix: String): String? {
        val name = "maru-study-$prefix-${System.currentTimeMillis()}.png"
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/MaruStudy",
                    )
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                "Pictures/MaruStudy/$name"
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "MaruStudy",
                ).apply { mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                file.absolutePath
            }
        }.getOrNull()
        bitmap.recycle()
        return result
    }
}
