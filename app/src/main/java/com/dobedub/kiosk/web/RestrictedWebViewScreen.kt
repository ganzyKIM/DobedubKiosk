package com.dobedub.kiosk.web

import android.annotation.SuppressLint
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dobedub.kiosk.ui.theme.BackgroundNormal
import com.dobedub.kiosk.ui.theme.LabelSecondary
import com.dobedub.kiosk.ui.theme.LineNeutral
import kotlinx.coroutines.delay

/**
 * 화이트리스트 도메인 밖으로 나갈 수 없는 제한 브라우저.
 * 주소창/검색창/방문기록/다운로드/외부 인텐트/파일선택/롱프레스 메뉴를 모두 막는다(§4.3).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RestrictedWebViewScreen(
    startUrl: String,
    allowedDomains: List<String>,
    onExitToKioskHome: () -> Unit,
    onUserInteraction: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }

    // 기기/제스처 뒤로가기는 웹뷰 자체 히스토리를 먼저 따르고, 더 갈 곳이 없을 때만 키오스크 홈으로 나간다.
    BackHandler {
        onUserInteraction()
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            onExitToKioskHome()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundNormal)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BackgroundNormal)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                onUserInteraction()
                onExitToKioskHome()
            }) { Icon(Icons.Filled.Close, contentDescription = "키오스크 홈으로 닫기") }

            IconButton(onClick = {
                onUserInteraction()
                loadError = null
                webViewRef?.loadUrl(startUrl)
            }) { Icon(Icons.Filled.Home, contentDescription = "시작 화면") }

            IconButton(onClick = {
                onUserInteraction()
                webViewRef?.let { if (it.canGoBack()) it.goBack() }
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }

            IconButton(onClick = {
                onUserInteraction()
                loadError = null
                webViewRef?.reload()
            }) { Icon(Icons.Filled.Refresh, contentDescription = "새로고침") }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(20.dp))
            }
        }
        androidx.compose.material3.HorizontalDivider(color = LineNeutral)

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // 사이트가 웹뷰(UA에 포함된 "wv" 표시)를 감지해 리소스를 다르게 서빙하는 경우를 대비해
                        // 일반 Chrome과 동일한 User-Agent로 보이도록 한다.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")

                        setOnLongClickListener { true } // 롱프레스 컨텍스트 메뉴(이미지 저장/링크 복사 등) 차단
                        setDownloadListener { _, _, _, _, _ ->
                            blockedMessage = "이 도서관 키오스크에서는 파일 다운로드를 지원하지 않아요."
                        }

                        // 마이 보이스(녹음) 기능을 위해 마이크 권한만 팝업 없이 항상 자동 허용한다.
                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) {
                                val granted = request.resources.filter {
                                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                                }
                                if (granted.isNotEmpty()) {
                                    request.grant(granted.toTypedArray())
                                } else {
                                    request.deny()
                                }
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val url = request.url.toString()
                                return if (DomainWhitelist.isAllowed(url, allowedDomains)) {
                                    false // 허용 도메인 → WebView가 직접 로드
                                } else {
                                    blockedMessage = "허용되지 않은 사이트로는 이동할 수 없어요."
                                    true // 차단
                                }
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                isLoading = true
                                loadError = null
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                isLoading = false
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                if (request.isForMainFrame) {
                                    isLoading = false
                                    loadError = "인터넷 연결을 확인해주세요."
                                }
                            }
                        }

                        webViewRef = this
                        loadUrl(startUrl)
                    }
                },
                update = { },
                modifier = Modifier.fillMaxSize()
            )

            if (loadError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundNormal),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(loadError.orEmpty(), color = LabelSecondary, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        onUserInteraction()
                        loadError = null
                        isLoading = true
                        webViewRef?.loadUrl(startUrl)
                    }) { Text("다시 시도") }
                }
            }

            blockedMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.error)
                        .padding(12.dp)
                ) {
                    Text(message, color = Color.White)
                }
                LaunchedEffect(message) {
                    delay(2500)
                    blockedMessage = null
                }
            }
        }
    }
}

/** 무조작 복귀 시 개인정보 보호를 위해 세션(쿠키/스토리지/기록)을 초기화한다. */
fun clearWebSession() {
    android.webkit.CookieManager.getInstance().removeAllCookies(null)
    android.webkit.CookieManager.getInstance().flush()
    android.webkit.WebStorage.getInstance().deleteAllData()
}
