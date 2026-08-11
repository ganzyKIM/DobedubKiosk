package com.dobedub.kiosk.web

import android.annotation.SuppressLint
import android.util.Log
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.X
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
 * 마이보이스 더빙 녹음이 너무 작고 먹먹하게 들어가는 문제 보정 — 마이크 입력 증폭 + 음성 명료화.
 *
 * 이 태블릿(Lenovo TB-J606F)의 내장 마이크는 입력이 매우 약해서, 아이가 평상시 목소리로
 * 더빙해도 원본 성우 음성보다 훨씬 작게 녹음된다. 사이트를 고치지 않고 앱에서 해결하기 위해
 * `getUserMedia` 를 감싸, 사이트가 받아가는 마이크 스트림에 처리 체인을 끼운다.
 * 사이트 입장에선 그냥 평범한 마이크 스트림이라 사이트 코드 수정이 필요 없다.
 *
 * 체인 순서와 이유(순서가 중요하다):
 *   1) 하이패스 90Hz  — 에어컨/책상 진동/발소리 같은 저역 럼블 제거.
 *                       증폭 **전에** 깎아야 잡음까지 같이 커지지 않는다.
 *                       아이 목소리 기본주파수는 250Hz 이상이라 목소리는 안 건드린다.
 *   2) 피킹 +3.5dB @3kHz — 자음(ㅅ,ㅊ,ㅌ…) 대역을 살짝 올려 말이 또렷해진다.
 *                       과하게 올리면 치찰음이 쏘므로 3.5dB 정도로 제한.
 *   3) 로우패스 11kHz — 태블릿 마이크 특유의 고역 히스 제거. 한국어 명료도는
 *                       8kHz 이하에서 대부분 결정되므로 손실이 없다.
 *   4) 게인 +16dB     — 본 증폭.
 *   5) 리미터         — 증폭 후 피크만 눌러 클리핑(소리 깨짐) 방지. 반드시 마지막.
 *
 * ⚠ 게인/필터 값은 실측이 아니라 청감 기준이다. 너무 크면 잡음까지 커지고 깨지므로
 *   실기기에서 들어보고 조정할 것. 값만 바꾸면 되도록 상수로 빼뒀다.
 */
private const val MIC_GAIN_DB = 30.0       // 본 증폭 (16dB로는 부족했다)
private const val MIC_HPF_HZ = 90.0        // 저역 럼블 컷
private const val MIC_PRESENCE_HZ = 3000.0 // 자음 명료도 대역
private const val MIC_PRESENCE_DB = 3.5
private const val MIC_LPF_HZ = 11000.0     // 고역 히스 컷

// 리미터 임계값. 이걸 낮게 잡으면(예전 -6dB) 증폭한 신호 대부분이 압축비에 걸려 눌려서
// 게인을 올려도 실제로는 거의 안 커진다 — 16dB가 작게 들렸던 주된 이유가 이것이었다.
// 0dBFS 바로 아래에서 "진짜 피크만" 잡도록 올려서 게인이 그대로 살아나게 한다.
private const val MIC_LIMIT_DB = -1.5

private val MIC_GAIN_FIX_JS = """
(function(){
  if (window.__dobedubMicGain) return;
  window.__dobedubMicGain = true;
  var md = navigator.mediaDevices;
  if (!md || !md.getUserMedia) return;

  var GAIN = Math.pow(10, ($MIC_GAIN_DB) / 20);   // dB → 배율
  var orig = md.getUserMedia.bind(md);

  md.getUserMedia = function (constraints) {
    return orig(constraints).then(function (stream) {
      try {
        if (!constraints || !constraints.audio) return stream;
        if (!stream.getAudioTracks || stream.getAudioTracks().length === 0) return stream;

        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return stream;
        var ctx = new AC();

        var src = ctx.createMediaStreamSource(stream);

        // 1) 저역 럼블 제거 (증폭 전에)
        var hpf = ctx.createBiquadFilter();
        hpf.type = 'highpass';
        hpf.frequency.value = $MIC_HPF_HZ;
        hpf.Q.value = 0.707;                 // 버터워스 — 통과대역이 평탄해 목소리 왜곡 없음

        // 2) 자음 명료도 (프레즌스)
        var presence = ctx.createBiquadFilter();
        presence.type = 'peaking';
        presence.frequency.value = $MIC_PRESENCE_HZ;
        presence.Q.value = 1.0;
        presence.gain.value = $MIC_PRESENCE_DB;

        // 3) 고역 히스 제거
        var lpf = ctx.createBiquadFilter();
        lpf.type = 'lowpass';
        lpf.frequency.value = $MIC_LPF_HZ;
        lpf.Q.value = 0.707;

        // 4) 본 증폭
        var gain = ctx.createGain();
        gain.gain.value = GAIN;

        // 5) 리미터: 0dBFS 직전의 진짜 피크만 잡는다.
        //    threshold 를 낮게 잡으면 증폭분이 전부 압축비에 먹혀 게인이 사라지므로
        //    -1.5dB 로 올리고, 대신 knee 0 / 높은 ratio / 빠른 attack 으로 확실히 막는다.
        var comp = ctx.createDynamicsCompressor();
        comp.threshold.value = $MIC_LIMIT_DB;
        comp.knee.value = 0;
        comp.ratio.value = 20;
        comp.attack.value = 0.001;
        comp.release.value = 0.10;

        var dest = ctx.createMediaStreamDestination();
        src.connect(hpf); hpf.connect(presence); presence.connect(lpf);
        lpf.connect(gain); gain.connect(comp); comp.connect(dest);

        // 증폭된 오디오 트랙으로 교체하되, 원본 트랙은 사이트가 stop() 할 수 있도록
        // 새 스트림이 끝날 때 같이 정리한다.
        var out = dest.stream;
        var orgTracks = stream.getAudioTracks();
        out.getAudioTracks().forEach(function (t) {
          var origStop = t.stop.bind(t);
          t.stop = function () {
            try { orgTracks.forEach(function (o) { o.stop(); }); } catch (e) {}
            try { ctx.close(); } catch (e) {}
            origStop();
          };
        });
        // 비디오 트랙 요청이 섞여 있으면 그대로 옮겨준다.
        if (stream.getVideoTracks) {
          stream.getVideoTracks().forEach(function (v) { out.addTrack(v); });
        }
        return out;
      } catch (e) {
        return stream;   // 실패하면 원본 스트림 그대로 — 녹음 자체가 막히면 안 된다
      }
    });
  };
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
                icon = TablerIcons.X,
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

                        // 마이크 증폭 스크립트는 반드시 **문서 시작 시점**에 넣어야 한다.
                        // 사이트가 모듈 로드 때 getUserMedia 참조를 미리 바인딩해두면 onPageFinished
                        // 주입은 이미 늦고(실기기 CDP로 확인함), iframe 안 코드도 못 덮는다.
                        // addDocumentStartJavaScript 는 모든 프레임에 페이지 스크립트보다 먼저 실행된다.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            runCatching {
                                WebViewCompat.addDocumentStartJavaScript(
                                    this, MIC_GAIN_FIX_JS, setOf("*")
                                )
                            }.onFailure { Log.w("KioskWebView", "마이크 증폭 주입 실패: ${it.message}") }
                        } else {
                            Log.w("KioskWebView", "DOCUMENT_START_SCRIPT 미지원 — 마이크 증폭 미적용")
                        }

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
