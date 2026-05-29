package com.example.smarty.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LaTeXView(
    latex: String,
    isBlock: Boolean = true,
    modifier: Modifier = Modifier,
    textColor: ComposeColor = if (isSystemInDarkTheme()) ComposeColor.White else ComposeColor.Black,
    backgroundColor: ComposeColor = ComposeColor.Transparent
) {
    val context = LocalContext.current
    var isReady by remember { mutableStateOf(false) }
    
    val minHeight = if (isBlock) 40.dp else 24.dp
    val maxHeight = if (isBlock) 400.dp else 60.dp
    
    Box(
        modifier = modifier
            .then(if (isBlock) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = minHeight, max = maxHeight)
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    setBackgroundColor(Color.TRANSPARENT)
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isReady = true
                        }
                    }
                    
                    loadUrl("file:///android_asset/latex_render.html")
                }
            },
            update = { webView ->
                if (isReady && latex.isNotEmpty()) {
                    val escapedLatex = latex
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "")
                    val hexColor = String.format("#%06X", 0xFFFFFF and textColor.toArgb())
                    
                    // Try rendering immediately, retry after delay if KaTeX wasn't ready
                    webView.evaluateJavascript(
                        "renderLatex('$escapedLatex', $isBlock, '$hexColor')",
                        null
                    )
                    // Retry after 500ms in case KaTeX CDN was still loading
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "renderLatex('$escapedLatex', $isBlock, '$hexColor')",
                            null
                        )
                    }, 500)
                }
            },
            modifier = Modifier
        )
    }
}

@Composable
fun LaTeXViewInline(
    latex: String,
    modifier: Modifier = Modifier,
    textColor: ComposeColor = if (isSystemInDarkTheme()) ComposeColor.White else ComposeColor.Black
) {
    LaTeXView(
        latex = latex,
        isBlock = false,
        modifier = modifier,
        textColor = textColor
    )
}
