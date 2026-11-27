package io.maru.manime.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import io.maru.manime.tv.nanamiFocusHighlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class TvQrSession(
    val deviceToken: String,
    val shortCode: String,
    val qrUrl: String,
    val expiresInSeconds: Int,
)

internal sealed interface TvQrPollResult {
    data object Pending : TvQrPollResult
    data class Complete(val accessToken: String) : TvQrPollResult
    data class Expired(val message: String) : TvQrPollResult
}

internal object NanamiTvQrAuthClient {
    private val endpoints = listOf(
        "https://maruchan.pages.dev/api/tv-auth",
        "https://maru-api-wine.vercel.app/api/tv-auth",
    )
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    internal fun parseStartResponse(rawJson: String): TvQrSession? {
        val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val device = json.optString("device").trim()
        val shortCode = json.optString("shortCode").trim()
        val qrUrl = json.optString("qrUrl").trim()
        if (device.isEmpty() || shortCode.isEmpty() || qrUrl.isEmpty()) return null
        return TvQrSession(
            deviceToken = device,
            shortCode = shortCode,
            qrUrl = qrUrl,
            expiresInSeconds = json.optInt("expiresIn", 300).coerceIn(30, 600),
        )
    }

    internal fun parsePollResponse(statusCode: Int, rawJson: String): TvQrPollResult {
        val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: JSONObject()
        val status = json.optString("status").trim().lowercase()
        val token = json.optString("access_token").trim()
        if (status == "complete" && token.isNotEmpty()) {
            return TvQrPollResult.Complete(token)
        }
        if (statusCode == 410 || status == "expired") {
            return TvQrPollResult.Expired(json.optString("error").ifBlank { "QR code expired." })
        }
        return TvQrPollResult.Pending
    }

    suspend fun startSession(provider: String): TvQrSession = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", "start")
            .put("provider", provider)
            .toString()
        var lastError = "Could not start QR pairing."
        for (url in endpoints) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonType))
                    .header("Accept", "application/json")
                    .build()
                http.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (res.isSuccessful) {
                        parseStartResponse(raw)?.let { return@withContext it }
                    } else {
                        val err = runCatching { JSONObject(raw).optString("error") }.getOrNull()
                        if (!err.isNullOrBlank()) lastError = err
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.message.isNullOrBlank()) lastError = e.message!!
            }
        }
        error(lastError)
    }

    suspend fun pollSession(deviceToken: String): TvQrPollResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", "poll")
            .put("device", deviceToken)
            .toString()
        for (url in endpoints) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonType))
                    .header("Accept", "application/json")
                    .build()
                http.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (res.isSuccessful || res.code == 410) {
                        return@withContext parsePollResponse(res.code, raw)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try fallback endpoint
            }
        }
        TvQrPollResult.Pending
    }
}

@Composable
internal fun QrCodeCanvas(
    content: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 1),
            )
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix != null) {
            Canvas(modifier = Modifier.size(180.dp)) {
                drawQrMatrix(matrix)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQrMatrix(matrix: BitMatrix) {
    val w = matrix.width.coerceAtLeast(1)
    val h = matrix.height.coerceAtLeast(1)
    val cellW = size.width / w
    val cellH = size.height / h
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (matrix[x, y]) {
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(x * cellW, y * cellH),
                    size = Size(cellW + 0.35f, cellH + 0.35f),
                )
            }
        }
    }
}

@Composable
internal fun NanamiQrAuthDialog(
    providerId: String,
    providerLabel: String,
    accentColor: Color,
    accentOnColor: Color,
    surfaceColor: Color,
    borderColor: Color,
    mutedTextColor: Color,
    onTokenReceived: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var refreshNonce by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<TvQrSession?>(null) }
    var remainingSeconds by remember { mutableIntStateOf(300) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isStarting by remember { mutableStateOf(true) }

    LaunchedEffect(providerId, refreshNonce) {
        isStarting = true
        errorMessage = null
        session = null
        val created = try {
            NanamiTvQrAuthClient.startSession(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Could not start QR pairing."
            isStarting = false
            return@LaunchedEffect
        }
        session = created
        remainingSeconds = created.expiresInSeconds
        isStarting = false

        var elapsedMs = 0L
        while (remainingSeconds > 0) {
            delay(1_000L)
            remainingSeconds = (remainingSeconds - 1).coerceAtLeast(0)
            elapsedMs += 1_000L
            if (elapsedMs % 2_000L == 0L) {
                when (val result = NanamiTvQrAuthClient.pollSession(created.deviceToken)) {
                    is TvQrPollResult.Complete -> {
                        onTokenReceived(result.accessToken)
                        return@LaunchedEffect
                    }
                    is TvQrPollResult.Expired -> {
                        errorMessage = result.message
                        return@LaunchedEffect
                    }
                    TvQrPollResult.Pending -> Unit
                }
            }
        }
        errorMessage = "QR code expired. Generate a new code."
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = surfaceColor,
                border = BorderStroke(1.dp, borderColor),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, tint = accentColor)
                            Text(
                                text = "Sign In with $providerLabel QR Code",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp,
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.nanamiFocusHighlight(accentColor, cornerRadius = 20.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = mutedTextColor)
                        }
                    }

                    HorizontalDivider(color = borderColor, thickness = 1.dp)

                    when {
                        isStarting -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(color = accentColor)
                                    Text("Generating QR pairing code…", color = mutedTextColor, fontSize = 13.sp)
                                }
                            }
                        }
                        errorMessage != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = errorMessage.orEmpty(),
                                    color = Color(0xFFFF8A80),
                                    fontSize = 13.5.sp,
                                    textAlign = TextAlign.Center,
                                )
                                Button(
                                    onClick = { refreshNonce++ },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accentColor,
                                        contentColor = accentOnColor,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.nanamiFocusHighlight(accentColor),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Generate New Code", fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                        session != null -> {
                            val active = session!!
                            val mins = remainingSeconds / 60
                            val secs = remainingSeconds % 60
                            val timerText = "%d:%02d".format(mins, secs)

                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val wide = maxWidth >= 460.dp
                                val infoColumn: @Composable (Modifier) -> Unit = { colMod ->
                                    Column(
                                        modifier = colMod,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "1. Scan the QR code with your phone camera.",
                                            color = Color.White,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "2. Or open maruchan.pages.dev/tv-auth and enter this code:",
                                            color = mutedTextColor,
                                            fontSize = 12.5.sp,
                                            lineHeight = 17.sp,
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.Black.copy(alpha = 0.45f),
                                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
                                        ) {
                                            Text(
                                                text = active.shortCode,
                                                color = accentColor,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 2.sp,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            )
                                        }
                                        Text(
                                            text = "Waiting for approval on phone • Expires in $timerText",
                                            color = mutedTextColor,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }

                                if (wide) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                                    ) {
                                        QrCodeCanvas(content = active.qrUrl)
                                        infoColumn(Modifier.weight(1f))
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        QrCodeCanvas(content = active.qrUrl)
                                        infoColumn(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = { refreshNonce++ },
                            border = BorderStroke(1.dp, borderColor),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.nanamiFocusHighlight(accentColor),
                        ) {
                            Text("Refresh QR", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = accentOnColor,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.nanamiFocusHighlight(accentColor),
                        ) {
                            Text("Close", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}
