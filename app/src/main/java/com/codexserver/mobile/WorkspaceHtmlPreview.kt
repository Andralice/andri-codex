package com.codexserver.mobile

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                    fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                        emptyMap(), ByteArrayInputStream(ByteArray(0)))
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

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { error = "" }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, detail: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) error = "预览加载失败：${detail.description}"
                }
            }
        }
    }
    DisposableEffect(view) { onDispose { view.stopLoading(); view.destroy() } }
    LaunchedEffect(view, document) { view.loadDataWithBaseURL(baseUrl, document, "text/html", "UTF-8", null) }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    AndroidView(factory = { view }, modifier = modifier.fillMaxSize())
}
