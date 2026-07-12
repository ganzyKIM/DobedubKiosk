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
 * 사이트를 **데스크탑 버전**으로 받기 위한 데스크탑 Chrome User-Agent.
 * 모바일 UA로 접속하면 사이트가 모바일 레이아웃(좁은 460 폭 리더)을 내려주는데, 그러면
 * 태블릿 가로 화면에서 모바일처럼 보인다. 데스크탑 UA를 주면 넓은 화면용 데스크탑 리더가
 * 적용되어 그림 영역이 화면을 채우고 오디오-스크롤 싱크도 데스크탑 폭 기준으로 동작한다.
 */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * 웹툰 리더에서 **그림 영역만** 화면에 꽉 차게 확대하는 스크립트 (태블릿 실기기 CDP 실측으로 검증).
 *
 * 리더 구조: 그림은 `.toon-scroll-layer`(레이아웃 460px 컬럼, 화면 가운데, overflow 스크롤)
 * 안에서 사이트가 오디오에 맞춰 scrollTop을 구동한다. 그 부모 `.toon-box`(ToonBox_toonBox)는
 * 스크롤/클립을 하지 않는 래퍼다. 헤더(뒤로/제목)·푸터(재생하기/마이 보이스 등) UI는 이
 * 래퍼 밖의 별도 요소다.
 *
 * 확대 대상 = **스크롤 레이어가 아니라 그 부모 `.toon-box`**. 스크롤 레이어(clip 요소)에 직접
 * transform을 걸면 WebView 합성 단계에서 클립이 원래 460px 폭 그대로 적용돼 그림 좌우가 잘린다
 * (실기기 실측 확인). 반면 스크롤 안 하는 부모를 확대하면, 스크롤 레이어는 460 폭으로 그림을
 * 온전히 담은 뒤 그 결과 전체가 부모 transform으로 800까지 확대돼 **좌우가 잘리지 않는다**.
 *
 * 배율 S = 화면폭/컬럼폭. 스크롤 레이어의 논리 높이를 `화면높이/S`로 맞춰 "사이트가 보인다고
 * 계산하는 영역"과 "확대되어 실제 보이는 영역"을 일치시킨다 → 현재 재생 컷이 아래로 잘려
 * 안 보이는 일이 없다. 원점 50% 0: 윗변은 화면 위에 고정된 채 아래로만 늘어나고 좌우는 가운데
 * 정렬로 채운다. scrollHeight(19277)·컬럼 레이아웃 폭(460)은 불변이라 오디오-스크롤 싱크 유지.
 * CSS transform은 브라우저가 터치 좌표를 자동 보정하므로 확대 후에도 터치·스크롤이 정상 동작.
 * 헤더/푸터는 래퍼 밖이라 크기가 변하지 않는다.
 *
 * SPA 라우팅으로 리더에 들어왔다 나갈 수 있어 setInterval로 감시하고, 리더가 아니면 스타일을
 * 비워 원상복구한다.
 */
private const val TOON_FIT_JS = """
(function(){
  if (window.__dobedubToonFit) return;
  window.__dobedubToonFit = true;
  var ID = '__dobedub_toon_fit';
  function apply(){
    try {
      var st = document.getElementById(ID);
      var sl = document.querySelector('.toon-scroll-layer');
      if (!sl) { if (st) st.textContent = ''; return; }
      var w = sl.clientWidth;               // 컬럼 레이아웃 폭(조상 transform 영향 없음, 보통 460)
      if (!w) return;
      var S = window.innerWidth / w;        // 화면 폭을 채우는 배율
      if (S < 1.01) { if (st) st.textContent = ''; return; }
      var h = Math.floor(window.innerHeight / S); // 확대 후 화면 높이와 일치하는 논리 높이
      // 확대 대상은 스크롤 레이어가 아니라 그 부모(.toon-box). 스크롤 레이어엔 높이만 맞춰
      // 보이는 영역을 일치시킨다.
      var css = '[class*="ToonBox_toonBox"]{transform:scale(' + S.toFixed(4) + ') !important;' +
                'transform-origin:50% 0 !important;height:' + h + 'px !important;}' +
                '.toon-scroll-layer{height:' + h + 'px !important;max-height:' + h + 'px !important;}';
      if (!st) { st = document.createElement('style'); st.id = ID; document.head.appendChild(st); }
      if (st.textContent !== css) st.textContent = css;
    } catch (e) {}
  }
  setInterval(apply, 500);
  apply();
})();
"""

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
                        // 데스크탑 레이아웃으로 렌더링: 넓은 뷰포트를 사용하고 콘텐츠를 화면 폭에 맞춰 축소.
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // 사이트가 데스크탑 버전을 내려주도록 데스크탑 Chrome UA로 접속한다.
                        settings.userAgentString = DESKTOP_USER_AGENT

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
                                // 웹툰 리더 그림 영역 확대 스크립트 주입(SPA 대비 setInterval로 자체 감시).
                                view.evaluateJavascript(TOON_FIT_JS, null)
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
