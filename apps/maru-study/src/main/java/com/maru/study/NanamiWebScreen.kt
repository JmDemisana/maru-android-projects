package com.maru.study

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private const val NANAMI_AI_URL = "https://nanami-ai.pages.dev"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NanamiWebScreen(onExit: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onExit()
    }

    Box(Modifier.fillMaxSize().background(MaruPageMid)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#111315"))

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.setSupportMultipleWindows(false)
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.textZoom = 100
                    settings.userAgentString = settings.userAgentString + " MaruStudy/0.1"

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            failed = false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                loading = false
                                failed = true
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val host = uri.host.orEmpty()
                            val internal = host.endsWith("nanami-ai.pages.dev") ||
                                host.endsWith("pages.dev") ||
                                host.endsWith("maru-api-wine.vercel.app")
                            if (internal) return false

                            return runCatching {
                                view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                true
                            }.getOrDefault(false)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }

                    loadUrl(NANAMI_AI_URL)
                }.also { webView = it }
            },
            update = { view ->
                canGoBack = view.canGoBack()
            },
        )

        AnimatedVisibility(
            visible = loading && progress < 100,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaruAccent,
                    trackColor = Color.Transparent,
                )
            }
        }

        if (failed) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.9f)
                    .background(MaruPanelDeepSolid)
                    .padding(16.dp),
            ) {
                Text(
                    "Nami couldn't load",
                    color = MaruTextStrong,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Check your connection, then try again.",
                    color = MaruTextMuted,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    MaruPrimaryButton(
                        "Retry",
                        onClick = {
                            failed = false
                            loading = true
                            progress = 0
                            webView?.reload()
                        },
                    )
                    MaruGhostButton(
                        "Back",
                        onClick = onExit,
                    )
                }
            }
        }
    }
}
