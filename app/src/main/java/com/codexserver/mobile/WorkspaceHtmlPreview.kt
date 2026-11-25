package com.codexserver.mobile

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.net.URLConnection
import kotlinx.coroutines.runBlocking

@Composable
internal fun WorkspaceHtmlPreview(document: String, target: WorkspacePreviewTarget, vm: MobileViewModel,
    onOpenLink: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val openLink by rememberUpdatedState(onOpenLink)
    var error by remember(target.source) { mutableStateOf("") }
    var resourceError by remember(target.source) { mutableStateOf("") }
    var loading by remember(target.source) { mutableStateOf(true) }
    var progress by remember(target.source) { mutableIntStateOf(0) }
    val baseUrl = workspaceHtmlUrl(target)
    val view = remember(context, target.source) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (url.substringBefore('#') == baseUrl.substringBefore('#')) return false
                    val local = workspaceHtmlResource(url, target)
                    if (local != null) openLink(workspaceFileLink(local), target.linkBase)
                    else if (request.url.host != "andri-preview.invalid") openLink(url, target.linkBase)
                    return true
                }

                // WebView calls this on its resource thread, not the Android UI thread.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != "andri-preview.invalid") return null
                    fun missing(): WebResourceResponse {
                        if (!request.isForMainFrame && !request.url.path.orEmpty().endsWith("/favicon.ico")) {
                            view.post { resourceError = "部分网页资源未能加载，可刷新重试" }
                        }
                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    val path = workspaceHtmlResource(request.url.toString(), target) ?: return missing()
                    return runCatching {
                        val file = runBlocking { vm.cacheWorkspaceFile(path, target.linkBase) }
                        val mime = when (path.substringAfterLast('.', "").lowercase()) {
                            "js", "mjs" -> "application/javascript"
                            "css" -> "text/css"
                            "svg" -> "image/svg+xml"
                            "woff2" -> "font/woff2"
                            "woff" -> "font/woff"
                            else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
                        }
                        WebResourceResponse(mime, "UTF-8", file.inputStream())
                    }.getOrElse { missing() }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    error = ""
                    resourceError = ""
                    loading = true
                    progress = 0
                }
                override fun onPageFinished(view: WebView, url: String) { loading = false }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, detail: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) {
                        error = "网页加载失败：${detail.description}"
                        loading = false
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) {
                        error = "网页加载失败（HTTP ${response.statusCode}）"
                        loading = false
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            }
        }
    }
    DisposableEffect(view) { onDispose { view.stopLoading(); view.destroy() } }
    LaunchedEffect(view, document) { view.loadDataWithBaseURL(baseUrl, document, "text/html", "UTF-8", null) }
    Column(modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        val message = error.ifBlank { resourceError }
        if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
