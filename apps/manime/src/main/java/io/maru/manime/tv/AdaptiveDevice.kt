package io.maru.manime.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.StatFs
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

internal enum class NanamiDeviceFormFactor {
    PHONE,
    TABLET,
    TV,
}

@Suppress("DEPRECATION")
internal fun isAndroidTv(context: Context): Boolean {
    val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    if ((context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true
    }
    val pm = context.packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature("amazon.hardware.fire_tv")
}

@Composable
internal fun rememberDeviceFormFactor(): NanamiDeviceFormFactor {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration.uiMode, configuration.smallestScreenWidthDp) {
        when {
            isAndroidTv(context) -> NanamiDeviceFormFactor.TV
            configuration.smallestScreenWidthDp >= 600 -> NanamiDeviceFormFactor.TABLET
            else -> NanamiDeviceFormFactor.PHONE
        }
    }
}

/**
 * Computes a conservative streaming cache limit for low-storage Android TV devices
 * so temporary torrent caches never exhaust internal flash storage.
 */
internal fun computeTvStreamCacheLimitBytes(availableBytes: Long): Long {
    if (availableBytes <= 0L) return 96L * 1024L * 1024L
    return (availableBytes / 5L).coerceIn(96L * 1024L * 1024L, 384L * 1024L * 1024L)
}

internal fun computeTvStreamCacheLimitBytes(cacheDir: File): Long {
    val available = runCatching {
        val target = cacheDir.takeIf { it.exists() } ?: cacheDir.parentFile ?: cacheDir
        StatFs(target.absolutePath).availableBytes
    }.getOrDefault(0L)
    return computeTvStreamCacheLimitBytes(available)
}

internal fun enforceStreamCacheBudget(rootDir: File, maxBytes: Long) {
    if (!rootDir.exists()) return
    val children = rootDir.listFiles()?.toList().orEmpty()
    if (children.isEmpty()) return
    val entries = children.map { file ->
        file to computeDirectoryBytes(file)
    }.sortedBy { it.first.lastModified() }
    var totalBytes = entries.sumOf { it.second }
    for ((entry, size) in entries) {
        if (totalBytes <= maxBytes) break
        runCatching { entry.deleteRecursively() }
        totalBytes -= size
    }
}

private fun computeDirectoryBytes(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * Adds a high-contrast focus outline and gentle scale transform when focused via TV D-pad or keyboard,
 * without interfering with normal touch interactions.
 */
internal fun Modifier.nanamiFocusHighlight(
    accentColor: Color,
    cornerRadius: Dp = 8.dp,
    focusedScale: Float = 1.035f,
    strokeWidth: Dp = 2.5.dp,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer {
            val scale = if (focused) focusedScale else 1f
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (focused) {
                val r = cornerRadius.toPx()
                val stroke = strokeWidth.toPx()
                drawRoundRect(
                    color = accentColor,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = stroke),
                )
            }
        }
}
