package com.maru.study

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
)

object GradeReportExport {

    private const val WIDTH = 1080
    private const val PAD = 72f
    private const val INK = 0xFFE4E6EB.toInt()
    private const val MUTED = 0xFFB0B3B8.toInt()
    private const val FAINT = 0xFF82858B.toInt()
    private const val HAIRLINE = 0xFF3A3B3C.toInt()
    private const val ACCENT = 0xFF2A7FFF.toInt()

    fun saveSingle(
        context: Context,
        course: String,
        prelim: String,
        midterm: String,
    ): String? {
        val required = computeRequiredEndterm(prelim, midterm)
        val difficulty = difficultyFor(required)
        val accent = difficulty?.color?.toArgb() ?: HAIRLINE
        val subject = course.trim().ifBlank { "Grade solver" }

        val height = 900
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, height)

        drawBrand(canvas)

        val eyebrow = text(22f, ACCENT, bold = true).apply { letterSpacing = 0.22f }
        canvas.drawText("REQUIRED ENDTERM", PAD, 226f, eyebrow)

        val title = text(46f, INK, bold = true)
        canvas.drawText(fit(subject, title, WIDTH - PAD * 2), PAD, 296f, title)

        val result = text(150f, accent, bold = true)
        canvas.drawText(formatRequiredEndterm(required), PAD, 468f, result)

        val badgeText = difficulty?.label ?: "Waiting"
        val badgePaint = text(24f, accent, bold = true).apply { letterSpacing = 0.12f }
        drawChip(canvas, badgeText.uppercase(), badgePaint, accent, PAD, 512f)

        canvas.drawRect(PAD, 604f, WIDTH - PAD, 605f, fill(HAIRLINE))

        drawRow(canvas, "PRELIM", gradeText(prelim), 686f, MUTED)
        drawRow(canvas, "MIDTERM", gradeText(midterm), 762f, MUTED)

        drawFooter(canvas, height)
        return save(context, bitmap, "grade")
    }

    fun saveBulk(context: Context, subjects: List<Triple<String, String, String>>): String? {
        val rows = subjects.mapIndexed { index, (course, prelim, midterm) ->
            val required = computeRequiredEndterm(prelim, midterm)
            GradeReportRow(
                label = course.trim().ifBlank { "Subject ${index + 1}" },
                detail = "Prelim ${gradeText(prelim)}  ·  Midterm ${gradeText(midterm)}",
                value = formatRequiredEndterm(required),
                accent = difficultyFor(required)?.color?.toArgb() ?: FAINT,
            )
        }

        val height = 380 + rows.size * 96 + 120
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, height)
        drawBrand(canvas)

        val eyebrow = text(22f, ACCENT, bold = true).apply { letterSpacing = 0.22f }
        canvas.drawText("GRADE REPORT", PAD, 226f, eyebrow)

        val title = text(46f, INK, bold = true)
        canvas.drawText(classCountLabel(rows.size), PAD, 296f, title)

        var y = 372f
        rows.forEach { row ->
            canvas.drawRect(PAD, y - 24f, PAD + 3f, y + 28f, fill(row.accent))

            val label = text(30f, INK, bold = true)
            canvas.drawText(fit(row.label, label, 560f), PAD + 22f, y, label)

            val value = text(34f, row.accent, bold = true)
            val valueWidth = value.measureText(row.value)
            canvas.drawText(row.value, WIDTH - PAD - valueWidth, y, value)

            val detail = text(21f, FAINT)
            canvas.drawText(fit(row.detail, detail, 560f), PAD + 22f, y + 34f, detail)

            y += 96f
            canvas.drawRect(PAD, y - 52f, WIDTH - PAD, y - 51f, fill(HAIRLINE))
        }

        drawFooter(canvas, height)
        return save(context, bitmap, "grades")
    }

    private fun drawBackground(canvas: Canvas, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(0xFF18191A.toInt(), 0xFF111315.toInt(), 0xFF0B0D0F.toInt()),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 9f, fill(ACCENT))
    }

    private fun drawBrand(canvas: Canvas) {
        val brand = text(24f, INK, bold = true).apply { letterSpacing = 0.32f }
        canvas.drawText("MARU STUDY", PAD, 92f, brand)
        canvas.drawRect(PAD, 130f, WIDTH - PAD, 131f, fill(HAIRLINE))
    }

    private fun drawChip(
        canvas: Canvas,
        label: String,
        paint: Paint,
        accent: Int,
        x: Float,
        y: Float,
    ) {
        val padding = 18f
        val width = paint.measureText(label) + padding * 2
        val top = y - 40f
        val bottom = y + 16f
        canvas.drawRect(x, top, x + width, bottom, fill(addAlpha(accent, 0x2E)))
        val border = fill(0)
        border.color = accent
        border.style = Paint.Style.STROKE
        border.strokeWidth = 2f
        canvas.drawRect(x, top, x + width, bottom, border)
        canvas.drawText(label, x + padding, y - 3f, paint)
    }

    private fun drawRow(canvas: Canvas, label: String, value: String, y: Float, valueColor: Int) {
        canvas.drawText(label, PAD, y, text(24f, FAINT, bold = true).apply { letterSpacing = 0.16f })
        val valuePaint = text(32f, valueColor, bold = true)
        canvas.drawText(value, WIDTH - PAD - valuePaint.measureText(value), y, valuePaint)
    }

    private fun drawFooter(canvas: Canvas, height: Int) {
        canvas.drawRect(PAD, height - 120f, WIDTH - PAD, height - 119f, fill(HAIRLINE))
        val stamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US))
        canvas.drawText("Generated $stamp", PAD, height - 66f, text(21f, FAINT))
        val tag = text(21f, FAINT).apply { letterSpacing = 0.1f }
        canvas.drawText("MARU STUDY", WIDTH - PAD - tag.measureText("MARU STUDY"), height - 66f, tag)
    }

    private fun gradeText(raw: String): String =
        parseGrade(raw)?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

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

    private fun addAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun save(context: Context, bitmap: Bitmap, prefix: String): String? {
        val name = "maru-study-$prefix-${System.currentTimeMillis()}.png"
        return runCatching {
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
    }
}
