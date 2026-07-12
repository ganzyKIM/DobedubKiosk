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
 * 웹툰 리더에서 **그림 영역을 화면 좌우 꽉 차게** 만드는 CSS 주입 (태블릿 실기기 CDP 실측으로 검증).
 *
 * 리더는 데스크탑 UA로도 그림 컬럼을 460px로 캡(`.viewer-layout`의 `max-width:460px` +
 * `margin:auto` 중앙정렬)해서 넓은 화면 가운데 좁게 박혀 나온다. 이 컬럼을 화면 폭까지 넓혀야
 * 한다.
 *
 * **왜 transform이 아니라 실제 폭 변경인가**: 이 WebView(Chromium)는 overflow-scroll 하는
 * 요소(`.toon-scroll-layer`)나 그 조상에 `transform: scale`을 걸어도, 스크롤 영역 안의 그림을
 * 실제로 확대 렌더링하지 않는다 — getBoundingClientRect는 확대된 크기(800)를 보고하지만 합성된
 * 픽셀은 원래 460 폭에서 잘린다(실기기 스크린샷으로 확인: 오른쪽 말풍선이 잘림). 그래서
 * transform 대신 **레이아웃 폭 자체**를 화면 폭으로 바꾼다.
 *
 * 방법: `.viewer-layout`의 460 캡·중앙정렬을 풀고, 컬럼 체인과 `.toon-image`를 `100vw`로,
 * 스크롤 레이어 안쪽 래퍼(클래스 없는 div 포함)의 460 잔재 중앙정렬도 좌측정렬로 눕힌다.
 * 그러면 그림이 실제로 화면 폭을 꽉 채우고(x=0..화면폭) 좌우가 잘리지 않으며, 세로는 자연스러운
 * 스크롤이라 아래로도 잘리지 않는다. 사이트는 넓어진 컬럼을 그대로 인식(폰에서 보듯)하므로 컷
 * offset 기반 오디오-스크롤 싱크가 유지된다. 헤더/푸터 UI는 컬럼 밖 요소라 그대로다.
 *
 * 클래스 셀렉터 기반이라 리더가 아닌 페이지(홈/목록)에선 매칭되는 요소가 없어 무효과다. SPA
 * 라우팅으로 head가 유지되므로 한 번 주입한 <style>이 리더 진입 시 자동 적용되고, 혹시 사라지면
 * setInterval로 다시 채운다.
 *
 * **오디오-스크롤 싱크 보정(좌표 어댑터)**: 사이트는 스크롤 목표를 모바일 기준 폭(460px)
 * 좌표계로 계산한다. 컬럼을 화면 폭(예: 800)으로 넓히면 콘텐츠 실제 높이는 S=화면폭/460 배
 * 길어지는데 사이트는 여전히 460 기준 값으로 scrollTop을 쓰므로 스크롤이 S배 모자라게 움직인다
 * (실기기 증상: 스크롤이 너무 적게 움직임). 그래서 `.toon-scroll-layer` 인스턴스의
 * scrollTop(읽기/쓰기)·scrollHeight·clientHeight·scrollTo를 가로채 사이트에게는 ÷S 한 값(460
 * 좌표계)을 보여주고, 사이트가 쓰는 값은 ×S 해서 실제 픽셀에 적용한다. 사이트는 폰에서처럼
 * 460 세계에서 일관되게 계산하고, 실제 스크롤은 정확히 보정된다. 사용자 터치 스크롤도 같은
 * 어댑터를 통해 읽히므로 일관성이 유지된다.
 */
private const val TOON_FIT_JS = """
(function(){
  if (window.__dobedubToonFit) return;
  window.__dobedubToonFit = true;
  var ID = '__dobedub_toon_fit';
  var BASE = 460; // 사이트가 스크롤 계산에 쓰는 모바일 기준 폭
  var CSS =
    '[class*="viewer-layout"]{max-width:100vw !important;width:100vw !important;margin:0 !important;}' +
    '[class*="viewer-body"],[class*="viewer-body-player"],[class*="toon-view"],[class*="toon-content"],[class*="ToonBox_toonBox"],[class*="ToonBox_toonContent"],.toon-scroll-layer{width:100vw !important;max-width:100vw !important;left:0 !important;margin:0 !important;}' +
    '.toon-scroll-layer{justify-content:flex-start !important;align-items:flex-start !important;}' +
    '.toon-scroll-layer div{width:100vw !important;max-width:100vw !important;left:0 !important;margin:0 !important;transform:none !important;}' +
    '.toon-image{width:100vw !important;max-width:100vw !important;height:auto !important;margin:0 !important;left:0 !important;}';
  function S(){ return (window.innerWidth || BASE) / BASE; }
  function ensure(){
    try {
      var st = document.getElementById(ID);
      if (!st) { st = document.createElement('style'); st.id = ID; (document.head || document.documentElement).appendChild(st); }
      if (st.textContent !== CSS) st.textContent = CSS;
      // 스크롤 레이어 좌표 어댑터: 사이트가 보는 값은 460-좌표계, 실제 적용은 ×S.
      var sl = document.querySelector('.toon-scroll-layer');
      if (sl && !sl.__dobedubAdapter) {
        var dTop = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop');
        var dSH  = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollHeight');
        var dCH  = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight');
        Object.defineProperty(sl, 'scrollTop', { configurable: true,
          get: function(){ return dTop.get.call(this) / S(); },
          set: function(v){ dTop.set.call(this, v * S()); } });
        Object.defineProperty(sl, 'scrollHeight', { configurable: true,
          get: function(){ return dSH.get.call(this) / S(); } });
        Object.defineProperty(sl, 'clientHeight', { configurable: true,
          get: function(){ return dCH.get.call(this) / S(); } });
        var oTo = sl.scrollTo.bind(sl);
        sl.scrollTo = function(a, b){
          if (typeof a === 'object' && a) { var c = Object.assign({}, a); if (typeof c.top === 'number') c.top *= S(); return oTo(c); }
          return oTo(a, (b || 0) * S());
        };
        sl.__dobedubAdapter = true;
      }
    } catch (e) {}
  }
  setInterval(ensure, 500);
  ensure();
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
