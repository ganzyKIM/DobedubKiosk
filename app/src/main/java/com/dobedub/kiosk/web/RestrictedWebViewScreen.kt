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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.dobedub.kiosk.ui.components.KidActionButton
import com.dobedub.kiosk.ui.theme.BackgroundNormal
import com.dobedub.kiosk.ui.theme.KidBlue
import com.dobedub.kiosk.ui.theme.KidBlueDark
import com.dobedub.kiosk.ui.theme.KidGreen
import com.dobedub.kiosk.ui.theme.KidGreenDark
import com.dobedub.kiosk.ui.theme.KidPurple
import com.dobedub.kiosk.ui.theme.KidPurpleDark
import com.dobedub.kiosk.ui.theme.LabelSecondary
import com.dobedub.kiosk.ui.theme.LineNeutral
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Home2
import compose.icons.tablericons.Refresh
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
 * 웹툰 리더 높이 붕괴(그림 안 보임) 수정용 CSS 주입.
 *
 * 이 태블릿의 WebView는 CSS `dvh`(동적 뷰포트 높이) 단위를 **0으로 계산하는 버그**가 있다
 * (실기기 CDP 실측: 100dvh=0, 100vh/svh/lvh=1276 정상). 사이트의 보이스툰 리더 루트
 * `.viewer-layout`이 `height:100dvh`로 높이를 잡는데, 그 값이 0이 되면서 리더 컬럼 전체가
 * height 0으로 붕괴 → 이미지가 로드돼도 스크롤 뷰포트가 0이라 화면이 백지가 된다.
 *
 * 그래서 리더 전용 컨테이너 `.viewer-layout`의 높이만 `vh`로 강제한다(vh는 정상 동작).
 * - 홈/목록 페이지엔 `viewer-layout`이 없어(실측 count 0) 영향이 없다.
 * - 폭/스크롤 좌표는 건드리지 않으므로 마이 보이스 재생(이미지·싱크 정상)에 영향이 없다.
 * - SPA 라우팅이라 최초 페이지 로드 시 한 번 넣은 <style>이 이후 리더 진입에도 계속 적용된다.
 *
 * [더빙 카운트다운 중앙정렬 수정]
 * 마이보이스 더빙의 3·2·1 카운트다운 숫자(SVG: images/myvoice/countdown-N.svg)가 원(링) 중심에서
 * 좌우로 밀리는 문제. 사이트는 Tailwind v4의 **독립 `translate` 속성**(`translate:-50%`)으로
 * 중앙정렬하는데, 이는 `transform`과 별개 속성이라 둘 다 있으면 **합산**된다. 그래서 우리가
 * `transform:translateX(-50%)`만 덮어쓰면 -100%가 되어 오히려 왼쪽으로 튄다(실측: 숫자 cx 292.5,
 * 링·부모 cx 400). `translate:none`으로 사이트 몫을 지우고 transform 하나만 남겨 결정적으로
 * 중앙에 고정한다 — 사이트가 나중에 translate를 빼도 동작이 같다.
 * (실기기 CDP 실측: 숫자 cx 400 = 부모 400 = 링 399.5)
 */
private const val READER_HEIGHT_FIX_JS = """
(function(){
  if (window.__dobedubReaderFix) return;
  window.__dobedubReaderFix = true;
  var s = document.createElement('style');
  s.id = '__dobedub_reader_fix';
  s.textContent =
    '[class*="viewer-layout"]{height:100vh !important;}' +
    'img[src*="myvoice/countdown"]{left:50% !important;translate:none !important;transform:translateX(-50%) !important;}';
  (document.head || document.documentElement).appendChild(s);
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
        // 아이들이 쓰는 화면이라 아이콘만 있는 작은 버튼 대신 색이 구분되는 큰 동그란
        // 버튼으로 둔다. 가장 많이 쓰는 "키오스크 홈으로"만 글자를 붙여 알약 모양으로 크게.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BackgroundNormal)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KidActionButton(
                icon = TablerIcons.Home2,
                contentDescription = "키오스크 홈으로 닫기",
                label = "닫기",
                face = KidGreen,
                shade = KidGreenDark,
                onClick = {
                    onUserInteraction()
                    onExitToKioskHome()
                }
            )

            KidActionButton(
                icon = TablerIcons.ArrowLeft,
                contentDescription = "뒤로",
                face = KidBlue,
                shade = KidBlueDark,
                onClick = {
                    onUserInteraction()
                    webViewRef?.let { if (it.canGoBack()) it.goBack() }
                }
            )

            KidActionButton(
                icon = TablerIcons.Refresh,
                contentDescription = "새로고침",
                face = KidPurple,
                shade = KidPurpleDark,
                onClick = {
                    onUserInteraction()
                    loadError = null
                    webViewRef?.reload()
                }
            )

            if (isLoading) {
                CircularProgressIndicator(
                    color = KidGreen,
                    strokeWidth = 4.dp,
                    modifier = Modifier.padding(start = 6.dp).size(30.dp)
                )
            }
        }
        androidx.compose.material3.HorizontalDivider(color = LineNeutral)

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    // 원격 지원/진단용 WebView 디버깅 활성화(잠긴 키오스크라 위험 낮음).
                    WebView.setWebContentsDebuggingEnabled(true)
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
                                // WebView의 dvh=0 버그로 리더가 붕괴하는 것만 vh로 바로잡는다(폭/transform 불변).
                                view.evaluateJavascript(READER_HEIGHT_FIX_JS, null)
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
