package com.dobedub.kiosk.web

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.dobedub.kiosk.MediaPlaybackState
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
/**
 * 마이크 처리 체인 on/off — **원인 절개용 스위치**.
 *
 * false 로 두면 `getUserMedia` 를 전혀 감싸지 않아 사이트가 브라우저 원본 스트림을 그대로
 * 받는다. 지직거림이 우리 체인 때문인지, 마이크/OS(WebView 의 AEC·노이즈억제·AGC) 단계에서
 * 이미 생기는 것인지 가르는 기준선을 만든다. 게인을 26dB→18dB 로 낮췄는데 오히려 왜곡이
 * 심해졌다는 보고가 있어(2026-08-18), "게인이 커서 리미터에 처박힌다"는 기존 설명으로는
 * 맞지 않는다 — 체인 밖 원인을 먼저 배제해야 한다.
 */
private const val MIC_PROCESSING_ENABLED = true

// **왜곡의 정체는 증폭된 마이크 노이즈였다** (2026-08-18 실기기 A/B 로 확정).
// 처리 체인을 완전히 끄고 녹음하면 "깨끗하지만 아주 작다" — 즉 마이크 자체는 멀쩡하고,
// 우리가 JS 로 크게 증폭하면서 노이즈 플로어까지 같이 키운 것이 지직거림의 원인이었다.
// 게인을 26dB→18dB 로 낮췄을 때 오히려 "더 심해졌다"고 느낀 것도 같은 이유다 — 절대
// 잡음은 줄었지만 목소리가 더 작아져 상대적으로 잡음이 도드라졌다.
//
// 그래서 접근을 바꾼다: **증폭을 우리가 하지 않고 WebView(WebRTC) 의 음성처리에 맡긴다.**
//   - autoGainControl  : 하드웨어/WebRTC 레벨의 자동 게인. 노이즈를 덜 키우면서 레벨을 올린다.
//   - echoCancellation : 스피커로 나가는 원본 성우 음성이 마이크로 되들어오는 것 방지.
// 사이트가 이 값들을 끄고 요청하더라도 우리가 켜서 다시 요청한다.
//
// ⚠ **noiseSuppression 은 끈다(2026-08-18 실기기).** 켰더니 "어절의 시작과 끝에서 한 번씩
//   지직" 거렸다 — WebRTC 노이즈 억제는 음성 구간을 판정해 무음을 눌러버리는데, 말이
//   시작/끝날 때 그 전환이 클릭으로 들린다. 예전에 우리가 직접 만든 노이즈 게이트를
//   같은 증상("열고 닫히는 게 그대로 들린다")으로 걷어낸 적이 있다 — 원리가 같다.
//   마이크 자체는 조용하다는 것이 A/B 로 확인됐으니(원본 = 깨끗) 억제가 필요 없다.
//
// 그 위에 얹는 우리 처리는 **최소한**으로만 둔다. DynamicsCompressor 2단(컴프+리미터)을
// 직렬로 물렸던 이전 구조는 아티팩트를 만들었으므로 컴프레서를 걷어내고,
// 고정 게인 + 안전 리미터 하나만 남긴다.
private const val MIC_GAIN_DB = 4.0         // 10→4: AGC 가 이미 충분히 올려 과하게 컸다
private const val MIC_HPF_HZ = 90.0         // 저역 럼블 컷 (증폭 전에 깎아야 잡음이 안 커진다)
private const val MIC_LPF_HZ = 9000.0       // 고역 히스 컷. 한국어 명료도는 8k 이하에서 결정된다.
// 안전 리미터: 피크만 부드럽게 막는다. knee 를 넓게 둬 걸릴 때 딱딱하게 잘리지 않게 한다.
// attack 을 너무 빠르게 잡으면 어절 첫머리 피크에서 급제동이 걸려 그 자체가 클릭이 된다.
private const val MIC_LIMIT_DB = -3.0
private const val MIC_LIMIT_KNEE_DB = 8.0   // 6→8: 더 부드럽게 진입
private const val MIC_LIMIT_ATTACK_S = 0.010 // 5→10ms: 어절 시작 트랜지언트에서 급제동 방지
private const val MIC_LIMIT_RELEASE_S = 0.15 // 0.10→0.15: 어절 끝에서 게인이 튀어오르지 않게

/**
 * 페이지의 미디어 재생을 감지해 네이티브에 하트비트를 보낸다(무조작 홈 복귀 유예용).
 * play/pause/ended 는 버블링하지 않지만 캡처 단계 리스너에는 오므로 document 하나로 덮고,
 * Web Audio 재생은 AudioBufferSourceNode.start 를 후킹해 잡는다. 재생 중일 때만 15초마다
 * 보내고, 페이지가 죽으면 네이티브 lease 가 스스로 만료된다(MediaPlaybackState).
 */
/** 페이지 JS(MEDIA_WATCH_JS)가 호출하는 네이티브 수신구. @JavascriptInterface 메서드만 노출된다. */
private class KioskNativeBridge {
    @JavascriptInterface
    fun mediaHeartbeat() = MediaPlaybackState.noteWebMediaHeartbeat()
}

private val MEDIA_WATCH_JS = """
(function(){
  if (window.__dbdMediaWatch) return; window.__dbdMediaWatch = true;
  var playing = new Set();
  function beat(){ try { KioskNative.mediaHeartbeat(); } catch (e) {} }
  document.addEventListener('play', function(e){ playing.add(e.target); beat(); }, true);
  function drop(e){ playing.delete(e.target); }
  document.addEventListener('pause', drop, true);
  document.addEventListener('ended', drop, true);
  document.addEventListener('emptied', drop, true);
  try {
    if (window.AudioBufferSourceNode) {
      var os = AudioBufferSourceNode.prototype.start;
      AudioBufferSourceNode.prototype.start = function(){ beat(); return os.apply(this, arguments); };
    }
  } catch (e) {}
  setInterval(function(){ if (playing.size > 0) beat(); }, 15000);
})();
"""

private val MIC_GAIN_FIX_JS = """
(function(){
  if (window.__dobedubMicGain) return;
  window.__dobedubMicGain = true;
  var md = navigator.mediaDevices;
  if (!md || !md.getUserMedia) return;

  var GAIN = Math.pow(10, ($MIC_GAIN_DB) / 20);   // dB → 배율
  var orig = md.getUserMedia.bind(md);

  md.getUserMedia = function (constraints) {
    // 1) 오디오 제약 보강 — WebRTC 음성처리를 반드시 켜서 요청한다.
    //    증폭을 우리가 하는 대신 여기에 맡기는 것이 이 수정의 핵심이다.
    var req = constraints;
    try {
      if (constraints && constraints.audio) {
        var a = (typeof constraints.audio === 'object' && constraints.audio) ? constraints.audio : {};
        var merged = {};
        for (var k in a) { if (Object.prototype.hasOwnProperty.call(a, k)) merged[k] = a[k]; }
        merged.autoGainControl = true;
        merged.noiseSuppression = false;  // 어절 경계에서 게이팅 클릭을 만든다 — 위 주석 참고
        merged.echoCancellation = true;
        req = {};
        for (var k2 in constraints) { if (Object.prototype.hasOwnProperty.call(constraints, k2)) req[k2] = constraints[k2]; }
        req.audio = merged;
      }
    } catch (e) { req = constraints; }

    return orig(req).then(function (stream) {
      try {
        if (!req || !req.audio) return stream;
        if (!stream.getAudioTracks || stream.getAudioTracks().length === 0) return stream;

        // 제약이 실제로 먹었는지 확인해두면 나중에 진단이 쉽다.
        try {
          var st = stream.getAudioTracks()[0].getSettings ? stream.getAudioTracks()[0].getSettings() : {};
          console.log('[dobedub] mic settings agc=' + st.autoGainControl +
                      ' ns=' + st.noiseSuppression + ' aec=' + st.echoCancellation);
        } catch (e) {}

        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return stream;
        var ctx = new AC();
        var src = ctx.createMediaStreamSource(stream);

        // 2) 저역 럼블 컷 — 증폭 전에 깎아야 잡음이 같이 커지지 않는다.
        var hpf = ctx.createBiquadFilter();
        hpf.type = 'highpass';
        hpf.frequency.value = $MIC_HPF_HZ;
        hpf.Q.value = 0.707;

        // 3) 고역 히스 컷
        var lpf = ctx.createBiquadFilter();
        lpf.type = 'lowpass';
        lpf.frequency.value = $MIC_LPF_HZ;
        lpf.Q.value = 0.707;

        // 4) 보조 게인 (고정) — 컴프레서는 쓰지 않는다. 2단 직렬이 아티팩트를 만들었다.
        var gain = ctx.createGain();
        gain.gain.value = GAIN;

        // 5) 안전 리미터 — 피크만 부드럽게.
        var limiter = ctx.createDynamicsCompressor();
        limiter.threshold.value = $MIC_LIMIT_DB;
        limiter.knee.value = $MIC_LIMIT_KNEE_DB;
        limiter.ratio.value = 12;
        limiter.attack.value = $MIC_LIMIT_ATTACK_S;
        limiter.release.value = $MIC_LIMIT_RELEASE_S;

        var dest = ctx.createMediaStreamDestination();
        src.connect(hpf); hpf.connect(lpf); lpf.connect(gain);
        gain.connect(limiter); limiter.connect(dest);

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
    DisposableEffect(Unit) {
        onDispose { MediaPlaybackState.clearWebMedia() }
    }
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
                        // 마이보이스/보이스툰은 대사 오디오를 사용자 제스처 없이 순서대로 재생한다.
                        // 이 값이 기본값(true)이면 WebView 가 "제스처 없는 재생"을 막아, 처음 몇 개만
                        // 나오고 이후로는 소리가 끊긴다 — 화면을 만지면 제스처가 생겨 그 타이밍의
                        // 소리만 다시 나오는 증상으로 나타난다(실기기 확인). 키오스크는 화이트리스트
                        // 안의 우리 사이트만 열므로 자동재생을 허용해도 안전하다.
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // 사이트가 데스크탑 버전을 내려주도록 데스크탑 Chrome UA로 접속한다.
                        settings.userAgentString = DESKTOP_USER_AGENT

                        // 마이크 증폭 스크립트는 반드시 **문서 시작 시점**에 넣어야 한다.
                        // 사이트가 모듈 로드 때 getUserMedia 참조를 미리 바인딩해두면 onPageFinished
                        // 주입은 이미 늦고(실기기 CDP로 확인함), iframe 안 코드도 못 덮는다.
                        // addDocumentStartJavaScript 는 모든 프레임에 페이지 스크립트보다 먼저 실행된다.
                        addJavascriptInterface(KioskNativeBridge(), "KioskNative")
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            runCatching {
                                WebViewCompat.addDocumentStartJavaScript(this, MEDIA_WATCH_JS, setOf("*"))
                            }.onFailure { Log.w("KioskWebView", "미디어 감지 주입 실패: ${it.message}") }
                        }
                        if (MIC_PROCESSING_ENABLED &&
                            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
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
                                // DOCUMENT_START_SCRIPT 미지원 기기 폴백 — __dbdMediaWatch 가드로 멱등
                                view.evaluateJavascript(MEDIA_WATCH_JS, null)
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
