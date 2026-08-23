'use strict';
// 서버 렌더링 HTML (프레임워크 없이 템플릿 문자열).
//
// 이 화면은 읽는 문서가 아니라 **조작하는 계기판**이다. 그래서 원칙이 셋 있다:
//   1. 표는 스캔 전용 — 편집은 전부 모달로 뺀다. 접었다 폈다 하면 행 높이가 출렁여서
//      "어느 기기가 문제인가"를 훑는 일 자체가 안 된다.
//   2. 상태는 색만이 아니라 형태로도 표시한다(행 왼쪽 심각도 스트라이프 + 칩).
//   3. 브랜드 라임은 **누를 수 있는 것에만** 쓴다. 상태색은 따로 둔다 — 예전엔 브랜드
//      라임과 성공 초록이 둘 다 초록이라 "최신"인지 "버튼"인지 구분이 안 됐다.
//
// 탭 3개: 기기 현황 / 앱 배포 / 영상 자료실. 폼 POST 후에는 서버가 referer 로 돌려보내
// 보고 있던 탭이 유지된다(server.js 의 backToReferer).

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function relTime(ms) {
  if (!ms) return '기록 없음';
  const d = Date.now() - ms;
  const m = Math.floor(d / 60000);
  if (m < 1) return '방금';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  return `${Math.floor(h / 24)}일 전`;
}

function fmtBytes(n) {
  if (!n) return '-';
  const mb = n / (1024 * 1024);
  return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb.toFixed(0)} MB`;
}

const STYLE = `
/* 중립색은 순회색이 아니라 라임 쪽으로 살짝 기울인 올리브 그레이 — 브랜드와 한 화면에
   놓았을 때 회색이 죽어 보이지 않는다. 상태색(ok/warn/bad)은 브랜드와 별개 축이다. */
:root{
  --bg:#f4f6f0; --surface:#fff; --surface-2:#fafbf7;
  --ink:#1b1e17; --ink-2:#4a5044; --muted:#767c6e; --line:#e2e6da; --line-2:#eef1e8;
  --brand:#8fd613; --brand-ink:#2f4a00; --brand-soft:#eaf7c9;
  --ok:#3f8f5e; --ok-soft:#e4f2e9;
  --warn:#b57611; --warn-soft:#faeed6;
  --bad:#c0392b; --bad-soft:#fae5e2;
  --shadow:0 1px 2px rgba(27,30,23,.06), 0 4px 12px rgba(27,30,23,.04);
  --r-card:12px; --r-chip:999px; --r-btn:8px;
}
@media (prefers-color-scheme:dark){
  :root{
    --bg:#111409; --surface:#191d12; --surface-2:#1f2417;
    --ink:#eef1e4; --ink-2:#c2c9b5; --muted:#8b917f; --line:#2b3120; --line-2:#232819;
    --brand:#a8e232; --brand-ink:#14200a; --brand-soft:#2a3a10;
    --ok:#6cc48c; --ok-soft:#1c3325;
    --warn:#e0a94a; --warn-soft:#3a2d13;
    --bad:#e8776a; --bad-soft:#3a1f1b;
    --shadow:0 1px 2px rgba(0,0,0,.4), 0 4px 14px rgba(0,0,0,.3);
  }
}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{
  margin:0; background:var(--bg); color:var(--ink);
  font-family:"Pretendard","Apple SD Gothic Neo","Malgun Gothic",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
  font-size:14px; line-height:1.55;
}
/* 숫자가 세로로 정렬돼야 훑을 수 있다. */
.num,td,.kpi-n{font-variant-numeric:tabular-nums}
.wrap{max-width:1180px;margin:0 auto;padding:20px 20px 72px}

/* ── 헤더 / 탭 ───────────────────────────────────────────── */
.top{display:flex;align-items:baseline;justify-content:space-between;gap:16px;padding-bottom:14px}
.brandmark{font-size:17px;font-weight:700;letter-spacing:-.01em}
.brandmark span{color:var(--muted);font-weight:500}
.top-actions{display:flex;gap:8px;align-items:center}
.tabs{display:flex;gap:2px;border-bottom:1px solid var(--line);margin-bottom:24px}
.tab{
  padding:10px 16px;text-decoration:none;color:var(--muted);font-weight:600;font-size:14px;
  border-bottom:2px solid transparent;margin-bottom:-1px;
}
.tab:hover{color:var(--ink)}
.tab.on{color:var(--ink);border-bottom-color:var(--brand)}
.tab .count{color:var(--muted);font-weight:500}

/* ── 버튼: 누를 수 있는 것만 라임 ─────────────────────────── */
.btn{
  display:inline-flex;align-items:center;gap:6px;background:var(--brand);color:var(--brand-ink);
  border:1px solid transparent;border-radius:var(--r-btn);padding:8px 14px;
  font:inherit;font-weight:650;font-size:13px;cursor:pointer;text-decoration:none;white-space:nowrap;
}
.btn:hover{filter:brightness(1.05)}
.btn.ghost{background:var(--surface);border-color:var(--line);color:var(--ink-2)}
.btn.ghost:hover{border-color:var(--muted);color:var(--ink)}
.btn.danger{background:transparent;border-color:var(--line);color:var(--bad)}
.btn.danger:hover{background:var(--bad-soft);border-color:var(--bad)}
.btn.sm{padding:4px 9px;font-size:12px}
.btn:disabled{opacity:.35;cursor:not-allowed;filter:none}
:where(a,button,input,select,summary):focus-visible{outline:2px solid var(--brand);outline-offset:2px}

/* ── KPI ─────────────────────────────────────────────────── */
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:22px}
.kpi{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card);padding:14px 16px;box-shadow:var(--shadow)}
.kpi-l{color:var(--muted);font-size:11px;font-weight:600;letter-spacing:.06em;text-transform:uppercase}
.kpi-n{font-size:27px;font-weight:700;line-height:1.15;margin-top:5px;letter-spacing:-.02em}
.kpi-sub{color:var(--muted);font-size:12px;margin-top:1px}

/* ── 카드 / 표 ───────────────────────────────────────────── */
.card{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card);box-shadow:var(--shadow);margin-bottom:18px;overflow:hidden}
.card-h{display:flex;align-items:baseline;justify-content:space-between;gap:12px;padding:14px 18px;border-bottom:1px solid var(--line-2)}
.card-h h2{margin:0;font-size:14px;font-weight:650;letter-spacing:-.01em}
.card-h .sub{color:var(--muted);font-size:12px}
.card-b{padding:16px 18px}
.card-note{padding:11px 18px;border-top:1px solid var(--line-2);background:var(--surface-2);color:var(--muted);font-size:12px;line-height:1.6}
.scroll{overflow-x:auto}
table{width:100%;border-collapse:collapse}
th{
  text-align:left;padding:9px 14px;color:var(--muted);font-size:11px;font-weight:600;
  letter-spacing:.06em;text-transform:uppercase;border-bottom:1px solid var(--line-2);white-space:nowrap;
}
td{padding:12px 14px;border-bottom:1px solid var(--line-2);vertical-align:middle}
tbody tr:last-child td{border-bottom:0}
tbody tr:hover{background:var(--surface-2)}
.empty{padding:36px;text-align:center;color:var(--muted)}

/* 심각도는 색에만 기대지 않는다 — 행 왼쪽 굵기로도 읽힌다. */
td.sev{box-shadow:inset 3px 0 0 var(--sev,transparent)}

/* ── 칩 ──────────────────────────────────────────────────── */
.chip{display:inline-flex;align-items:center;gap:5px;padding:2px 9px;border-radius:var(--r-chip);font-size:12px;font-weight:600;white-space:nowrap}
.chip.ok{background:var(--ok-soft);color:var(--ok)}
.chip.warn{background:var(--warn-soft);color:var(--warn)}
.chip.bad{background:var(--bad-soft);color:var(--bad)}
.chip.quiet{background:var(--line-2);color:var(--muted)}
.dot{width:7px;height:7px;border-radius:50%;flex:none}

.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:11.5px;color:var(--muted);letter-spacing:-.01em}
.muted{color:var(--muted)} .small{font-size:12.5px}
.strong{font-weight:650}
.stack{display:flex;flex-direction:column;gap:3px}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.right{text-align:right} .nowrap{white-space:nowrap}

input,select{
  font:inherit;font-size:13px;padding:7px 10px;border:1px solid var(--line);
  border-radius:var(--r-btn);background:var(--surface);color:var(--ink);
}
input:focus,select:focus{border-color:var(--brand);outline:none}
input[type=file]{padding:6px;font-size:12px}
form.inline{display:inline}

/* ── 호버 설명 팝오버 ────────────────────────────────────── */
/* 클릭할 것(버튼)과 읽을 것(설명)을 분리한다 — 설명은 ? 배지에 올리면 나온다.
   JS 없이 :hover/:focus-within 만 쓴다(키보드 접근 포함). */
.pop{position:relative;display:inline-flex;align-items:center}
.pop-badge{
  width:26px;height:26px;border-radius:50%;border:1px solid var(--line);
  background:var(--surface);color:var(--muted);font-weight:700;font-size:13px;
  display:inline-flex;align-items:center;justify-content:center;cursor:help;
}
.pop:hover .pop-badge,.pop:focus-within .pop-badge{border-color:var(--brand);color:var(--brand-ink);background:var(--brand-soft)}
.pop-tip{
  display:none;position:absolute;top:calc(100% + 10px);right:-8px;width:360px;z-index:60;
  background:var(--surface);border:1px solid var(--line);border-radius:12px;
  box-shadow:0 12px 40px rgba(0,0,0,.18);padding:14px 16px;
  text-align:left;white-space:normal;font-size:12.5px;line-height:1.7;color:var(--ink-2);
}
.pop:hover .pop-tip,.pop:focus-within .pop-tip{display:block}
.pop-tip h4{margin:0 0 7px;font-size:13px;color:var(--ink)}
.pop-tip p{margin:0 0 9px}
.pop-tip p:last-child{margin-bottom:0}

/* ── 모달 ────────────────────────────────────────────────── */
/* dialog 는 top layer 에 뜨지만 CSS 는 DOM 부모에서 상속된다. 표 안에 두면 셀의
   text-align:right / white-space:nowrap 이 그대로 넘어와 글이 우측정렬되고 줄바꿈이
   막혀 가로 스크롤이 생긴다(실제로 그랬다). 지금은 표 밖에 두지만, 어디에 두더라도
   깨지지 않도록 여기서 명시적으로 되돌린다. */
dialog{
  border:1px solid var(--line);border-radius:14px;background:var(--surface);color:var(--ink);
  padding:0;width:min(560px,calc(100vw - 32px));box-shadow:0 12px 40px rgba(0,0,0,.22);
  text-align:left;white-space:normal;font-size:14px;line-height:1.55;
}
dialog *{white-space:normal}
dialog .nowrap,dialog .mono.nowrap{white-space:nowrap}
dialog::backdrop{background:rgba(20,24,16,.5)}
.m-h{padding:16px 20px 12px;border-bottom:1px solid var(--line-2)}
.m-h h3{margin:0;font-size:15px;font-weight:650;letter-spacing:-.01em}
.m-h .who{color:var(--muted);font-size:12.5px;margin-top:2px}
.m-b{padding:16px 20px;max-height:min(60vh,520px);overflow-y:auto;overflow-x:hidden}
.m-f{padding:12px 20px;border-top:1px solid var(--line-2);background:var(--surface-2);display:flex;justify-content:space-between;gap:8px;align-items:center;flex-wrap:wrap}
.m-sec+.m-sec{margin-top:20px;padding-top:18px;border-top:1px solid var(--line-2)}
.m-sec>h4{margin:0 0 4px;font-size:12px;font-weight:650;letter-spacing:.05em;text-transform:uppercase;color:var(--muted)}
.m-sec>p{margin:0 0 10px;color:var(--muted);font-size:12.5px;line-height:1.6}
.list{display:flex;flex-direction:column}
.item{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid var(--line-2);flex-wrap:wrap}
.item:last-child{border-bottom:0}
.item .name{flex:1 1 200px;min-width:0;overflow-wrap:anywhere;word-break:break-word;font-size:13px}
.strip{display:flex;gap:8px;overflow-x:auto;padding-bottom:6px}
.strip img{width:64px;height:auto;border-radius:6px;border:1px solid var(--line);flex:none;background:var(--surface-2)}
.pick{cursor:pointer}
.pick.off{opacity:.5;cursor:default}
.thumb{width:40px;height:58px;object-fit:cover;border-radius:6px;background:var(--line-2);flex:none;display:block}
.thumb.none{display:flex;align-items:center;justify-content:center;color:var(--muted);font-size:10px}
.drop{border:1px dashed var(--line);border-radius:10px;padding:14px;background:var(--surface-2)}

@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
`;

// 모달 안의 이미지는 열 때 비로소 받는다.
// `loading="lazy"` 는 닫힌 <dialog> 안에서 "뷰포트 밖"으로 간주돼 로드가 지연되는데,
// 모달을 열어도 트리거되지 않아 빈 칸만 보였다(실측). 그렇다고 lazy 를 빼면 기기 3대 ×
// 내장 12장 = 10MB 를 대시보드 열 때마다 받는다. 그래서 data-src → src 로 직접 바꾼다.
const SCRIPT = `
function openModal(id){
  var d=document.getElementById(id);
  if(!d) return;
  d.querySelectorAll('img[data-src]').forEach(function(i){
    i.src=i.getAttribute('data-src'); i.removeAttribute('data-src');
  });
  d.showModal();
}

// ── 전송 진행률 폴링 ──
// 기기가 다운로드 중 /api/progress 로 보고한 것을 2초마다 읽어 화면에 그린다.
// .xfer        : 특정 파일 하나의 상태 칩(모달 "받는 중" 항목, 버전 셀 APK 칩)
// .xfer-sum    : 기기 행의 요약 칩("수신중 2개 41%")
(function(){
  function pct(t){ return t.total > 0 ? Math.floor(t.received / t.total * 100) + '%' : '…'; }
  function apply(list){
    document.querySelectorAll('.xfer').forEach(function(el){
      var t = list.find(function(x){
        return x.deviceId === el.dataset.xferDevice && x.kind === el.dataset.xferKind
            && (!el.dataset.xferName || x.name === el.dataset.xferName);
      });
      if (!t) {
        // APK 칩은 평소 숨김. 모달 "받는 중" 칩은 서버가 준 초기 문구를 유지한다.
        if (el.dataset.xferKind === 'apk') el.hidden = true;
        return;
      }
      el.hidden = false;
      el.className = 'chip ' + (t.status === 'failed' ? 'bad' : t.status === 'done' ? 'ok' : 'warn') + ' xfer';
      el.textContent =
        t.status === 'failed' ? '실패' + (t.error ? ': ' + t.error : '') :
        t.status === 'done' ? (el.dataset.xferKind === 'apk' ? '설치 중' : '완료 — 곧 반영됩니다') :
        (el.dataset.xferKind === 'apk' ? 'APK ' : '수신 중 ') + pct(t);
    });
    document.querySelectorAll('.xfer-sum').forEach(function(el){
      var mine = list.filter(function(x){
        return x.deviceId === el.dataset.xferDevice && x.kind === 'video' && x.status === 'downloading';
      });
      if (!mine.length) { el.hidden = true; return; }
      var rec = 0, tot = 0;
      mine.forEach(function(t){ rec += t.received; tot += t.total; });
      el.hidden = false;
      el.textContent = '수신중 ' + mine.length + '개 ' + (tot > 0 ? Math.floor(rec / tot * 100) + '%' : '…');
    });
  }
  // ── 자동 새로고침 ──
  // 서버의 상태 개정 번호(/api/fleet-rev)가 이 페이지를 그린 시점(window.FLEET_REV)과
  // 달라지면 — 체크인 도착, 전송 완료, 다른 창의 관리자 조작 — 화면을 새로 그린다.
  // 단, 관리자가 뭔가 하는 중이면 절대 끊지 않는다: 모달이 열려 있거나, 입력칸에
  // 포커스가 있거나, 쓰다 만 값(파일 선택 포함)이 남아 있으면 다음 기회로 미룬다.
  function busyEditing(){
    if (document.querySelector('dialog[open]')) return true;
    var a = document.activeElement;
    if (a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) return true;
    var dirty = false;
    document.querySelectorAll('input[type=file]').forEach(function(i){ if (i.files && i.files.length) dirty = true; });
    document.querySelectorAll('input[type=text], input[type=number], textarea').forEach(function(i){
      if (i.value && i.value !== i.defaultValue) dirty = true;
    });
    return dirty;
  }
  var failStreak = 0;
  async function poll(){
    if (document.visibilityState !== 'visible') return;
    try {
      var pair = await Promise.all([
        fetch('/api/transfers', { cache: 'no-store' }),
        fetch('/api/fleet-rev', { cache: 'no-store' })
      ]);
      if (!pair[0].ok || !pair[1].ok) { failStreak++; return; }
      failStreak = 0;
      apply(await pair[0].json());
      var rev = (await pair[1].json()).rev;
      if (rev !== window.FLEET_REV && !busyEditing()) location.reload();
    } catch (e) { failStreak++; }
  }
  // 로그인 페이지(FLEET_REV 없음)에서는 폴링하지 않는다.
  if (typeof window.FLEET_REV === 'number') {
    poll();
    setInterval(function(){ if (failStreak < 30) poll(); }, 2000);
    // 백그라운드 탭에서는 폴링을 쉬므로, 다시 보이는 순간 한 번 바로 갱신해
    // 최대 2초의 스테일 표시도 없앤다.
    document.addEventListener('visibilitychange', function(){
      if (document.visibilityState === 'visible') poll();
    });
  }
})();`;

function page(title, body) {
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>${STYLE}</style></head><body><div class="wrap">${body}</div>
<script>${SCRIPT}</script></body></html>`;
}

function loginPage(error) {
  return page('로그인 · 두비덥 키오스크 관리', `
    <div class="card" style="max-width:340px;margin:14vh auto 0;">
      <div class="m-h"><h3>두비덥 키오스크 관리</h3><div class="who">관리자 비밀번호를 입력하세요</div></div>
      <form method="post" action="/login">
        <div class="m-b">
          ${error ? `<p class="chip bad" style="display:block;padding:8px 12px;margin:0 0 12px;">${esc(error)}</p>` : ''}
          <input name="password" type="password" placeholder="비밀번호" style="width:100%" autofocus>
        </div>
        <div class="m-f" style="justify-content:stretch;">
          <button class="btn" type="submit" style="width:100%;justify-content:center;">로그인</button>
        </div>
      </form>
    </div>`);
}

// ─────────────────────────────────────────────────────────────
// 기기 현황
// ─────────────────────────────────────────────────────────────

function deviceModals(d, media, builtin) {
  const vids = Array.isArray(d.videoList) ? d.videoList : [];
  const pendingDel = new Set(d.pendingDeletes || []);
  const pendingPush = d.pendingPushes || [];
  const manual = d.manualImages || [];
  const others = d.otherDevices || [];
  const who = esc(d.app_label || d.device_id);

  // ── 영상 ──
  const owned = vids.map(v => `<div class="item">
      <span class="name">${esc(v.name)}</span>
      <span class="mono nowrap">${fmtBytes(v.size)}</span>
      ${pendingDel.has(v.name)
        ? '<span class="chip warn">삭제 대기</span>'
        : `<form class="inline" method="post" action="/device/video/delete" onsubmit="return confirm('이 영상을 이 태블릿에서 삭제할까요?\\n다음 접속 시 기기에서 실제로 지워집니다.');">
             <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
             <input type="hidden" name="filename" value="${esc(v.name)}">
             <button class="btn ghost sm" type="submit">삭제</button>
           </form>`}
    </div>`).join('') || '<p class="muted small">이 태블릿에 영상이 없습니다.</p>';

  const incoming = pendingPush.map(p => `<div class="item">
      <span class="name">${esc(p.original_name)}</span>
      <span class="mono nowrap">${fmtBytes(p.size)}</span>
      <span class="chip warn xfer" data-xfer-device="${esc(d.device_id)}" data-xfer-kind="video"
        data-xfer-name="${esc(p.original_name)}">${p.mode === 'ask' ? '기기 확인 대기' : '전송 대기'}</span>
      <form class="inline" method="post" action="/media/push/cancel">
        <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
        <input type="hidden" name="mediaId" value="${p.media_id}">
        <button class="btn ghost sm" type="submit">취소</button>
      </form>
    </div>`).join('');

  const ownedNames = new Set(vids.map(v => v && v.name));
  const pendingIds = new Set(pendingPush.map(p => p.media_id));
  const picks = media.map(m => {
    const off = ownedNames.has(m.original_name) ? '<span class="chip ok">보유 중</span>'
              : pendingIds.has(m.id) ? '<span class="chip warn">전송 대기</span>' : null;
    return `<label class="item pick${off ? ' off' : ''}">
        <input type="checkbox" name="mediaId" value="${m.id}" ${off ? 'disabled' : ''}>
        <span class="name">${esc(m.original_name)}</span>
        <span class="mono nowrap">${fmtBytes(m.size)}</span>
        ${off || ''}
      </label>`;
  }).join('');

  // ⚠ 취소/삭제 폼을 push 폼 **안에** 두면 안 된다. HTML 은 중첩 <form> 을 허용하지 않아
  // 브라우저가 안쪽 <form> 태그를 파싱 단계에서 버리고, 그 버튼들이 바깥 push 폼의
  // submit 이 된다 — 실제로 "취소"를 누르면 push 폼의 onsubmit 검사에 걸려
  // "보낼 영상을 선택하세요" 알림만 뜨고 취소가 안 됐다. 그래서 push 폼은 선택 목록만
  // 감싸고, 푸터의 보내기 버튼은 form 속성으로 원격 연결한다.
  const pushFormId = `push-${esc(d.device_id)}`;
  const videoModal = `<dialog id="vid-${esc(d.device_id)}">
      <div class="m-h"><h3>영상 관리</h3><div class="who">${who}</div></div>
      <div class="m-b">
        ${incoming ? `<div class="m-sec"><h4>받는 중</h4><div class="list">${incoming}</div>
          <p class="muted small">취소는 아직 다운로드가 시작되지 않은 항목에만 듣습니다. 이미 받는 중인 영상은 끝까지 받아지며, 필요하면 완료 후 삭제하세요.</p></div>` : ''}
        <div class="m-sec"><h4>이 태블릿의 영상 ${vids.length}개</h4><div class="list">${owned}</div></div>
        ${media.length ? `<form id="${pushFormId}" method="post" action="/media/push"
            onsubmit="return this.querySelector('input[name=mediaId]:checked') ? true : (alert('보낼 영상을 선택하세요.'), false)">
          <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
          <div class="m-sec">
            <h4>보낼 영상 고르기</h4>
            <p>접속 중인 기기는 보내면 몇 초 안에 받기 시작합니다. 꺼져 있으면 다음 접속 때 받습니다.</p>
            <div class="list">${picks}</div>
            <div class="row" style="gap:16px;margin-top:10px;flex-wrap:wrap;">
              <label class="row" style="gap:6px;"><input type="radio" name="mode" value="force" checked> 바로 받기 시작</label>
              <label class="row" style="gap:6px;"><input type="radio" name="mode" value="ask"> 기기 화면에서 물어보고 받기</label>
            </div>
          </div>
        </form>` : '<div class="m-sec"><p>자료실에 영상이 없습니다. 영상 자료실 탭에서 먼저 등록하세요.</p></div>'}
      </div>
      <div class="m-f">
        <button class="btn ghost" type="button" onclick="this.closest('dialog').close()">닫기</button>
        ${media.length ? `<button class="btn" type="submit" form="${pushFormId}">선택한 영상 보내기</button>` : ''}
      </div>
    </dialog>`;

  // ── 이용안내 ──
  const manualRows = manual.map((m, i) => `<div class="item">
      <img class="thumb" data-src="/manual/${m.id}/download" alt="">
      <span class="name"><span class="mono">${String(i + 1).padStart(2, '0')}</span> ${esc(m.original_name)}
        <span class="mono">${fmtBytes(m.size)}</span></span>
      <form class="inline" method="post" action="/device/manual/move">
        <input type="hidden" name="id" value="${m.id}"><input type="hidden" name="dir" value="up">
        <button class="btn ghost sm" type="submit" ${i === 0 ? 'disabled' : ''} aria-label="위로">↑</button>
      </form>
      <form class="inline" method="post" action="/device/manual/move">
        <input type="hidden" name="id" value="${m.id}"><input type="hidden" name="dir" value="down">
        <button class="btn ghost sm" type="submit" ${i === manual.length - 1 ? 'disabled' : ''} aria-label="아래로">↓</button>
      </form>
      <form class="inline" method="post" action="/device/manual/delete">
        <input type="hidden" name="id" value="${m.id}">
        <button class="btn ghost sm" type="submit">삭제</button>
      </form>
    </div>`).join('');

  // 세트가 비어 있으면 "지금 이 태블릿에 실제로 뜨는 화면"이 앱 내장본이므로 그걸 보여준다.
  const builtinPreview = builtin.length
    ? `<div class="strip">${builtin.map((n, i) =>
        `<img data-src="/manual/builtin/${encodeURIComponent(n)}" alt="기본 이용안내 ${i + 1}">`).join('')}</div>`
    : '<p class="muted small">미리보기를 찾을 수 없습니다(앱 리소스 폴더가 이 서버에 없음).</p>';

  const manualModal = `<dialog id="man-${esc(d.device_id)}">
      <div class="m-h"><h3>이용안내 이미지</h3><div class="who">${who}</div></div>
      <div class="m-b">
        <div class="m-sec">
          <h4>${manual.length ? `현재 이 태블릿의 이미지 ${manual.length}장` : '현재: 앱 내장 기본 이용안내'}</h4>
          <p>홈 화면 아래에 위에서부터 순서대로 이어 붙고, 가로폭은 화면에 맞춰 세로로 스크롤됩니다.
             한 장짜리 긴 이미지도, 여러 장도 됩니다. 비워두면 앱에 내장된 기본 안내를 씁니다.</p>
          ${manual.length
            ? `<div class="list">${manualRows}</div>`
            : `${builtinPreview}
               <p class="muted small" style="margin:8px 0 0;">아래에서 이미지를 추가하면 이 기본 안내 대신 그 이미지들이 표시됩니다.</p>`}
        </div>
        <div class="m-sec">
          <h4>이미지 추가</h4>
          <form method="post" action="/device/manual/upload" enctype="multipart/form-data" class="drop">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <div class="row">
              <input type="file" name="images" accept="image/*" multiple required>
              <button class="btn" type="submit">추가</button>
            </div>
            <p class="muted small" style="margin:8px 0 0;">여러 장을 한 번에 고를 수 있습니다. 파일명 순으로 목록 뒤에 붙습니다.</p>
          </form>
        </div>
        ${others.length ? `<div class="m-sec">
          <h4>다른 기기에서 가져오기</h4>
          <p>선택한 기기의 이용안내를 그대로 복사합니다. 이 기기의 현재 세트는 지워집니다.</p>
          <form method="post" action="/device/manual/copy" class="row"
                onsubmit="return confirm('선택한 기기의 이용안내로 이 기기 설정을 덮어씁니다. 계속할까요?');">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <select name="fromDeviceId">${others.map(o =>
              `<option value="${esc(o.device_id)}">${esc(o.app_label || o.device_id)} · ${o.count}장</option>`).join('')}</select>
            <button class="btn ghost" type="submit">가져오기</button>
          </form>
        </div>` : ''}
      </div>
      <div class="m-f">
        ${manual.length ? `<form class="inline" method="post" action="/device/manual/clear"
              onsubmit="return confirm('이 기기의 이용안내를 모두 비울까요?\\n앱에 내장된 기본 이용안내로 돌아갑니다.');">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <button class="btn danger sm" type="submit">전체 비우기</button>
          </form>` : '<span></span>'}
        <button class="btn ghost" type="button" onclick="this.closest('dialog').close()">닫기</button>
      </div>
    </dialog>`;

  // ── 기기 설정 ──
  const contactPending = d.contact_override && d.contact_override !== d.contact;
  const fleetUrlPending = !!(d.fleet_url_override && d.fleet_url_override !== d.fleet_url);
  const manageModal = `<dialog id="mng-${esc(d.device_id)}">
      <div class="m-h"><h3>기기 설정</h3><div class="who">${who}</div></div>
      <div class="m-b">
        <div class="m-sec">
          <h4>기관명</h4>
          <p>기기 목록에서 이 태블릿을 알아보기 위한 이름입니다.</p>
          <form method="post" action="/device/label" class="row">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <input name="label" value="${esc(d.app_label || '')}" placeholder="기관명" style="flex:1">
            <button class="btn ghost" type="submit">저장</button>
          </form>
        </div>
        <div class="m-sec">
          <h4>문의 연락처</h4>
          <p>문제가 생겼을 때 태블릿 화면에 표시됩니다. 태블릿에서는 못 바꾸고 여기서만 바꿉니다.</p>
          <form method="post" action="/device/contact" class="row">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <input name="contact" value="${esc(d.contact_override || d.contact || '')}" placeholder="02-334-2227" style="flex:1">
            <button class="btn ghost" type="submit">저장</button>
          </form>
          ${contactPending ? '<p style="margin:8px 0 0;"><span class="chip warn">적용 대기 — 다음 접속 때 반영</span></p>' : ''}
        </div>
        <div class="m-sec">
          <h4>관리자 PIN 초기화</h4>
          <p>현장에서 PIN을 잊었을 때 쓰는 복구 수단입니다. 다음 접속 때 <b>0000</b>으로 돌아갑니다.</p>
          ${d.pin_reset
            ? '<span class="chip warn">PIN 초기화 대기중</span>'
            : `<form method="post" action="/device/pin-reset" onsubmit="return confirm('이 기기의 관리자 PIN을 0000 으로 초기화할까요?\\n다음 접속 시 적용됩니다.');">
                 <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
                 <button class="btn ghost" type="submit">PIN 초기화</button>
               </form>`}
        </div>
        <div class="m-sec">
          <h4>함대 서버 주소 (원격 변경)</h4>
          <p>기기가 접속할 서버 주소를 원격으로 바꿉니다. 기기는 <b>새 주소가 살아있는지 확인한
          뒤에만</b> 갈아탑니다(잘못된 주소로 기기를 잃지 않게). 서버 이전·NetBird 전환용.</p>
          <form method="post" action="/device/fleet-url" class="row"
                onsubmit="return confirm('이 기기의 서버 주소 변경을 지시할까요?\n기기가 새 주소 확인에 성공해야 적용됩니다.');">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <input name="fleetUrl" value="${esc(d.fleet_url_override || '')}"
                   placeholder="${esc(d.fleet_url || 'http://100.x.y.z:8090')}" style="flex:1">
            <button class="btn ghost" type="submit">지시</button>
          </form>
          <p class="muted small" style="margin:6px 0 0;">기기 보고 현재 주소: <span class="mono">${esc(d.fleet_url || '(미보고 — v2.4.1 이하)')}</span></p>
          ${fleetUrlPending ? '<p style="margin:8px 0 0;"><span class="chip warn">적용 대기 — 기기가 새 주소를 쓰기 시작하면 사라집니다</span></p>' : ''}
        </div>
        <div class="m-sec">
          <h4>원격 재부팅</h4>
          <p>강제 업데이트 후 화면이 안 돌아오는 간헐 현상의 원격 복구 수단입니다.
          지시는 <b>1회만</b> 내려가며, 반응이 없으면 다시 누르면 됩니다.</p>
          <form method="post" action="/device/reboot"
                onsubmit="return confirm('이 기기를 지금 재부팅할까요?\n사용 중이면 화면이 끊깁니다.');">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <button class="btn ghost" type="submit">기기 재부팅</button>
          </form>
        </div>
        <div class="m-sec">
          <h4>기기 정보</h4>
          <div class="stack">
            <div class="row"><span class="muted small" style="width:80px">기기 ID</span><span class="mono">${esc(d.device_id)}</span></div>
            <div class="row"><span class="muted small" style="width:80px">모델</span><span class="mono">${esc(d.model || '-')}</span></div>
            <div class="row"><span class="muted small" style="width:80px">시작 주소</span><span class="mono">${esc(d.start_url || '-')}</span></div>
            <div class="row"><span class="muted small" style="width:80px">접속 IP</span><span class="mono">${esc(d.ip || '-')}</span></div>
            <div class="row"><span class="muted small" style="width:80px">누적 접속</span><span class="mono">${d.checkin_count}회</span></div>
          </div>
        </div>
      </div>
      <div class="m-f">
        <form class="inline" method="post" action="/device/delete" onsubmit="return confirm('이 기기 기록을 삭제할까요? (기기가 다시 체크인하면 재등록됩니다)');">
          <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
          <button class="btn danger sm" type="submit">기기 기록 삭제</button>
        </form>
        <button class="btn ghost" type="button" onclick="this.closest('dialog').close()">닫기</button>
      </div>
    </dialog>`;

  return videoModal + manualModal + manageModal;
}

function devicesTab({ devices, release, media, stats, thresholds, builtinManual }) {
  // 모달은 표 밖에 모아 둔다. 셀 안에 두면 td 의 text-align:right / white-space:nowrap 이
  // 상속돼 모달 글이 우측정렬되고 가로 스크롤이 생긴다.
  const modals = [];
  const rows = devices.map(d => {
    modals.push(deviceModals(d, media, builtinManual || []));
    const age = Date.now() - d.last_seen;
    // 태블릿은 화면이 꺼지면 절전으로 체크인이 늦어진다 — 그건 고장이 아니다. 그래서
    // "오프라인" 대신 마지막 접속 시각을 헤드라인으로 두고, 24시간 넘은 것만 빨강으로 센다.
    const st = age <= thresholds.onlineMsFor(d) ? { k: 'ok', t: '접속 중', c: 'var(--ok)' }
             : age <= thresholds.staleMs ? { k: 'warn', t: '미접속', c: 'var(--warn)' }
             : { k: 'bad', t: '연락 두절', c: 'var(--bad)' };

    const isLatest = release && d.version_code === release.version_code;
    // 구버전 기기에는 두 가지 선택지: "알림 보내기"(기기 화면에서 동의 후 설치)와
    // "즉시 업데이트"(재생 중이어도 바로 설치 — 관리자 명시 선택이라 confirm 을 거친다).
    const verChip = !release ? ''
      : isLatest ? '<span class="chip ok">최신</span>'
      : d.force_update ? '<span class="chip warn">즉시 업데이트 대기중</span>'
      : d.update_prompt ? `<span class="chip warn">알림 대기중</span>
          <form class="inline" method="post" action="/device/update-force"
                onsubmit="return confirm('이 기기를 지금 바로 업데이트할까요?\\n사용(재생) 중이어도 즉시 설치되고 앱이 잠시 재시작됩니다.');">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <button class="btn ghost sm" type="submit">즉시</button>
          </form>`
      : `<form class="inline" method="post" action="/device/update-prompt">
           <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
           <button class="btn ghost sm" type="submit">알림 보내기</button>
         </form>
         <form class="inline" method="post" action="/device/update-force"
               onsubmit="return confirm('이 기기를 지금 바로 업데이트할까요?\\n사용(재생) 중이어도 즉시 설치되고 앱이 잠시 재시작됩니다.');">
           <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
           <button class="btn ghost sm" type="submit">즉시</button>
         </form>`;

    const vids = Array.isArray(d.videoList) ? d.videoList : [];
    const manual = d.manualImages || [];
    const waiting = (d.pendingPushes || []).length + (d.pendingDeletes || []).length;

    // 재부팅하면 원격 관리가 끊길 기기를 **재부팅 전에** 드러낸다. 기기가 always-on VPN
    // 지정 사실을 보고하지 않으면(구버전이거나 지정 실패) 이 칩이 뜬다 — 이 경고가 없어서
    // "코드는 넣었으니 됐겠지" 하고 넘어갔다가 재부팅 QA 를 한 사이클 날렸다.
    const vpnChip = d.always_on_vpn
      ? ''
      : '<span class="chip warn" title="재부팅하면 넷버드가 자동으로 안 붙어 원격 관리가 끊깁니다. 앱 v2.5.2 이상으로 업데이트하세요.">VPN 미지정</span>';

    // 기기 식별: 기관명이 주인공, 접속 중인 AP 가 "어느 도서관인지"의 실질적 단서다.
    const place = [];
    if (d.ap_ssid) place.push(esc(d.ap_ssid));
    if (d.lat != null && d.lng != null) {
      place.push(`<a href="https://www.google.com/maps?q=${d.lat},${d.lng}" target="_blank" rel="noopener noreferrer">지도</a>`);
    }

    return `<tr>
      <td class="sev" style="--sev:${st.c}">
        <div class="stack">
          <span class="strong nowrap">${esc(relTime(d.last_seen))}</span>
          <span class="row" style="gap:5px"><span class="dot" style="background:${st.c}"></span><span class="mono">${esc(st.t)}</span></span>
        </div>
      </td>
      <td>
        <div class="stack">
          <span class="strong">${esc(d.app_label || '(이름 없음)')}</span>
          <span class="mono">${place.join(' · ') || esc(d.device_id.slice(0, 12))}</span>
        </div>
      </td>
      <td>
        <div class="stack">
          <span class="nowrap">${esc(d.version_name || '?')} <span class="mono">${d.version_code == null ? '' : 'code ' + d.version_code}</span>
            <span class="chip ok xfer" data-xfer-device="${esc(d.device_id)}" data-xfer-kind="apk" hidden></span></span>
          <span class="row" style="gap:6px;flex-wrap:wrap;">${verChip}</span>
        </div>
      </td>
      <td class="nowrap">
        <div class="stack">
          <span>${d.kiosk_locked ? '<span class="chip quiet">잠김</span>' : '<span class="chip warn">해제됨</span>'}${vpnChip}</span>
          <span class="mono">배터리 ${Number.isFinite(d.battery) ? d.battery + '%' : '-'}</span>
        </div>
      </td>
      <td class="nowrap">
        <div class="stack">
          <span class="mono">영상 ${vids.length} · 이미지 ${manual.length ? manual.length + '장' : '내장'}</span>
          <span class="row" style="gap:6px;">
            ${waiting ? `<span class="chip warn">대기 ${waiting}</span>` : ''}
            <span class="chip ok xfer-sum" data-xfer-device="${esc(d.device_id)}" hidden></span>
            ${waiting ? '' : '<span class="mono">&nbsp;</span>'}
          </span>
        </div>
      </td>
      <td class="right nowrap">
        <div class="row" style="justify-content:flex-end;gap:6px;">
          <button class="btn ghost sm" type="button" onclick="openModal('vid-${esc(d.device_id)}')">영상</button>
          <button class="btn ghost sm" type="button" onclick="openModal('man-${esc(d.device_id)}')">이미지</button>
          <button class="btn ghost sm" type="button" onclick="openModal('mng-${esc(d.device_id)}')">설정</button>
        </div>
      </td>
    </tr>`;
  }).join('');

  const attention = devices.filter(d => Date.now() - d.last_seen > thresholds.staleMs).length;

  return `
    <div class="kpis">
      <div class="kpi"><div class="kpi-l">전체 기기</div><div class="kpi-n">${stats.total}</div></div>
      <div class="kpi"><div class="kpi-l">접속 중</div><div class="kpi-n" style="color:var(--ok)">${stats.online}</div>
        <div class="kpi-sub">체크인 주기 2회 내</div></div>
      <div class="kpi"><div class="kpi-l">연락 두절</div><div class="kpi-n" style="color:${attention ? 'var(--bad)' : 'inherit'}">${stats.offline}</div>
        <div class="kpi-sub">${Math.round(thresholds.staleMs / 3600000)}시간+ 무소식</div></div>
      <div class="kpi"><div class="kpi-l">최신 버전</div><div class="kpi-n">${stats.onLatest}<span class="muted" style="font-size:17px">/${stats.total}</span></div></div>
    </div>

    <div class="card">
      <div class="card-h"><h2>기기 목록</h2><span class="sub">손볼 기기가 위로 옵니다</span></div>
      ${devices.length ? `<div class="scroll"><table>
        <thead><tr><th>마지막 접속</th><th>기기</th><th>버전</th><th>상태</th><th>콘텐츠</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table></div>` : '<div class="empty">아직 체크인한 기기가 없습니다.</div>'}
      <div class="card-note">
        태블릿은 사용 중일 때 ${Math.round((devices[0] && devices[0].checkin_interval_ms ? devices[0].checkin_interval_ms : 30 * 60 * 1000) / 60000)}분마다 접속합니다.
        화면이 꺼져 있으면 그보다 훨씬 늦어지고 밤에는 아침까지 끊기는 것이 정상입니다 —
        <b>“미접속”은 고장이 아니라 쉬고 있다는 뜻</b>입니다. 24시간 넘게 소식이 없을 때만 확인이 필요합니다.
      </div>
    </div>
    ${modals.join('')}`;
}

// ─────────────────────────────────────────────────────────────
// 앱 배포
// ─────────────────────────────────────────────────────────────

function releaseTab({ devices, release, releases, relPaging, stats }) {
  const outdated = release ? devices.filter(d => d.version_code !== release.version_code).length : 0;

  const current = release ? `
    <div class="card-b">
      <div class="row" style="justify-content:space-between;align-items:flex-start;">
        <div class="stack" style="gap:6px;">
          <div><span style="font-size:24px;font-weight:700;letter-spacing:-.02em;">${esc(release.version_name)}</span>
               <span class="mono" style="font-size:13px;">code ${release.version_code}</span></div>
          <div class="mono">${fmtBytes(release.size)} · 업로드 ${esc(relTime(release.uploaded_at))}</div>
          ${release.notes ? `<div class="small" style="max-width:60ch;margin-top:4px;">${esc(release.notes)}</div>` : ''}
          <div class="mono" style="word-break:break-all;margin-top:4px;">sha256 ${esc(release.sha256)}</div>
        </div>
        <div class="row">
          <a class="btn ghost" href="/download/app.apk">APK 내려받기</a>
          ${outdated > 0 ? `
            <form method="post" action="/release/notify-outdated" onsubmit="return confirm('업데이트가 안 된 기기 ${outdated}대 전체에 업데이트 확인창을 띄우라고 지시할까요?\\n기기 쪽에서 확인을 누르면 바로 설치됩니다.');">
              <button class="btn" type="submit">업데이트 안 된 ${outdated}대에 알림</button>
            </form>` : ''}
        </div>
      </div>
    </div>` : '<div class="empty">아직 배포된 APK가 없습니다. 아래에서 첫 버전을 올리세요.</div>';

  const dist = stats.versionDist.map(v => {
    const pct = stats.total ? Math.round(v.count / stats.total * 100) : 0;
    const latest = release && v.version_code === release.version_code;
    return `<div style="margin-bottom:12px;">
      <div class="row" style="justify-content:space-between;">
        <span>${esc(v.version_name || '?')} <span class="mono">code ${v.version_code == null ? '?' : v.version_code}</span>
          ${latest ? '<span class="chip ok">최신</span>' : ''}</span>
        <span class="mono">${v.count}대 · ${pct}%</span>
      </div>
      <div style="height:6px;border-radius:3px;background:var(--line-2);overflow:hidden;margin-top:6px;">
        <span style="display:block;height:100%;width:${pct}%;background:${latest ? 'var(--brand)' : 'var(--muted)'}"></span>
      </div>
    </div>`;
  }).join('') || '<p class="muted">아직 체크인한 기기가 없습니다.</p>';

  const history = (releases || []).map(r => `<tr>
      <td class="nowrap">${esc(r.version_name)} <span class="mono">code ${r.version_code}</span>
        ${r.active ? '<span class="chip ok">배포중</span>' : ''}</td>
      <td class="mono nowrap">${fmtBytes(r.size)}</td>
      <td class="mono nowrap">${esc(relTime(r.uploaded_at))}</td>
      <td class="small" style="max-width:34ch;">${esc(r.notes || '')}</td>
      <td class="right">${r.active ? '' : `
        <form class="inline" method="post" action="/release/rollback" onsubmit="return confirm('${esc(r.version_name)} (code ${r.version_code}) 버전으로 롤백할까요?\\n기기들이 다음 체크인 때 이 버전으로 자동 다운그레이드/재설치됩니다.');">
          <input type="hidden" name="id" value="${r.id}">
          <button class="btn ghost sm" type="submit">이 버전으로 롤백</button>
        </form>`}</td>
    </tr>`).join('');

  return `
    <div class="card"><div class="card-h"><h2>현재 배포 버전</h2></div>${current}</div>

    <div class="card">
      <div class="card-h"><h2>새 버전 배포</h2></div>
      <div class="card-b">
        <form method="post" action="/release/upload" enctype="multipart/form-data" class="row">
          <input type="file" name="apk" accept=".apk" required>
          <input name="versionCode" type="number" min="1" placeholder="versionCode" required style="width:140px">
          <input name="versionName" placeholder="versionName" required style="width:140px">
          <input name="notes" placeholder="릴리스 메모(선택)" style="flex:1;min-width:180px">
          <button class="btn" type="submit">업로드 · 배포</button>
        </form>
      </div>
      <div class="card-note">versionCode 는 앱 <span class="mono">build.gradle.kts</span> 의 값과 같게, 기존보다 높게 넣으세요.
        기기들은 다음 접속 때 자동으로 업데이트합니다. 릴리스 메모는 이 폼으로 넣어야 한글이 깨지지 않습니다.</div>
    </div>

    <div class="card"><div class="card-h"><h2>버전 분포</h2></div><div class="card-b">${dist}</div></div>

    <div class="card" id="rel">
      <div class="card-h"><h2>배포 이력</h2><span class="sub">전체 ${relPaging ? relPaging.total : (releases || []).length}개</span></div>
      ${history ? `<div class="scroll"><table>
        <thead><tr><th>버전</th><th>크기</th><th>업로드</th><th>메모</th><th></th></tr></thead>
        <tbody>${history}</tbody>
      </table></div>` : '<div class="empty">이력이 없습니다.</div>'}
      ${relPaging && relPaging.pages > 1 ? `
      <div class="card-note" style="display:flex;justify-content:center;gap:10px;align-items:center;">
        ${relPaging.page > 1 ? `<a class="btn ghost sm" href="/dashboard?tab=release&relPage=${relPaging.page - 1}#rel">이전</a>` : ''}
        <span class="mono">${relPaging.page} / ${relPaging.pages}</span>
        ${relPaging.page < relPaging.pages ? `<a class="btn ghost sm" href="/dashboard?tab=release&relPage=${relPaging.page + 1}#rel">다음</a>` : ''}
      </div>` : ''}
    </div>`;
}

// ─────────────────────────────────────────────────────────────
// 영상 자료실
// ─────────────────────────────────────────────────────────────

function mediaTab({ media, mediaDir }) {
  const rows = media.map(m => `<tr>
      <td style="width:56px;">${m.thumb
        ? `<img class="thumb" src="/media/${m.id}/thumb?${m.uploaded_at}" alt="" loading="lazy">`
        : '<div class="thumb none">없음</div>'}</td>
      <td style="word-break:break-all;">${esc(m.original_name)}</td>
      <td class="mono nowrap">${fmtBytes(m.size)}</td>
      <td class="mono nowrap">${esc(relTime(m.uploaded_at))}</td>
      <td class="right nowrap">
        <form class="inline" method="post" action="/media/thumb" enctype="multipart/form-data">
          <input type="hidden" name="mediaId" value="${m.id}">
          <label class="btn ghost sm" style="cursor:pointer;">${m.thumb ? '썸네일 교체' : '썸네일 등록'}<input type="file" name="thumb" accept="image/*" style="display:none" onchange="this.form.submit()"></label>
        </form>
        <form class="inline" method="post" action="/media/delete" onsubmit="return confirm('자료실에서 이 영상을 삭제할까요? (이미 기기에 내려간 사본은 지워지지 않음)');">
          <input type="hidden" name="id" value="${m.id}">
          <button class="btn ghost sm" type="submit">삭제</button>
        </form>
      </td>
    </tr>`).join('');

  return `
    <div class="card">
      <div class="card-h"><h2>영상 넣기</h2></div>
      <div class="card-b">
        <div class="drop">
          <div class="strong" style="margin-bottom:4px;">이 폴더에 영상을 복사해 넣으세요</div>
          <div class="mono" style="word-break:break-all;font-size:12.5px;">${esc(mediaDir || '')}</div>
          <p class="muted small" style="margin:8px 0 0;">넣으면 자동으로 등록됩니다. 복사가 끝난 뒤 <b>새로고침</b> — 큰 파일은 검증에 몇 초 걸립니다.
             폴더에서 지우면 자료실에서도 사라집니다.</p>
        </div>
        <form method="post" action="/media/upload" enctype="multipart/form-data" class="row" style="margin-top:14px;">
          <input type="file" name="video" accept=".mp4,.m4v,.mkv,.webm" required>
          <button class="btn ghost" type="submit">브라우저로 업로드</button>
          <span class="muted small">원격에서 작업할 때</span>
        </form>
      </div>
    </div>

    <div class="card">
      <div class="card-h"><h2>영상 목록</h2><span class="sub">${media.length}개</span></div>
      ${rows ? `<div class="scroll" style="max-height:560px;overflow-y:auto;"><table>
        <thead><tr><th>썸네일</th><th>파일</th><th>크기</th><th>등록</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table></div>` : '<div class="empty">등록된 영상이 없습니다.</div>'}
      <div class="card-note">기기로 보내는 것은 <a href="/dashboard">기기 현황</a> 탭의 “영상” 버튼에서 합니다.
        썸네일은 태블릿 동영상 목록에 표시되며(없으면 영상 첫 장면), 등록·교체하면 그 영상을 가진 기기가 다음 접속 때 내려받습니다.</div>
    </div>`;
}

// ─────────────────────────────────────────────────────────────

const TABS = [
  { id: 'devices', label: '기기 현황' },
  { id: 'release', label: '앱 배포' },
  { id: 'media', label: '영상 자료실' }
];

function dashboardPage(data) {
  const tab = TABS.some(t => t.id === data.tab) ? data.tab : 'devices';
  const body = tab === 'release' ? releaseTab(data)
             : tab === 'media' ? mediaTab(data)
             : devicesTab(data);
  const counts = { devices: data.devices.length, media: data.media.length,
                   release: data.relPaging ? data.relPaging.total : 0 };
  const nav = TABS.map(t =>
    `<a class="tab${t.id === tab ? ' on' : ''}" href="/dashboard${t.id === 'devices' ? '' : '?tab=' + t.id}">${t.label} <span class="count">${counts[t.id]}</span></a>`
  ).join('');

  return page(`${TABS.find(t => t.id === tab).label} · 두비덥 키오스크 관리`, `
    <script>window.FLEET_REV=${Number(data.fleetRev) || 0}</script>
    <header class="top">
      <div class="brandmark">두비덥 키오스크 <span>관리</span></div>
      <div class="top-actions">
        <span class="pop" tabindex="0">
          <span class="pop-badge" aria-label="원격 관리 원리 설명">?</span>
          <span class="pop-tip">
            <h4>넷버드로 원격 관리가 되는 원리</h4>
            <p><b>태블릿과 이 PC는 넷버드(WireGuard VPN)라는 가상 전용망으로 묶여 있습니다.</b>
            태블릿이 어느 도서관 와이파이에 있든, 항상 같은 넷버드 고정 주소로
            이 서버에 <b>10분마다 접속(체크인)</b>합니다.</p>
            <p>관리자에서 누르는 지시(영상 보내기·업데이트·재부팅 등)는 서버에 쌓였다가
            태블릿이 접속할 때 전달되고, 급한 지시는 즉시 깨워 몇 초 안에 반영됩니다.
            화면이 꺼진 태블릿은 절전 때문에 접속이 늦어질 수 있습니다(밤에는 아침까지).</p>
            <p>태블릿의 등록·연결 상태는 <b>넷버드 콘솔</b>에서 봅니다. 기기 행에
            <b>VPN 미지정</b> 경고가 보이면 "재부팅하면 원격이 끊기는 기기"라는 뜻이니
            앱을 최신으로 업데이트하세요.</p>
          </span>
        </span>
        <a class="btn ghost sm" href="https://app.netbird.io/peers" target="_blank" rel="noopener noreferrer">넷버드 콘솔 ↗</a>
        <a class="btn ghost sm" href="">새로고침</a>
        <a class="btn ghost sm" href="/logout">로그아웃</a>
      </div>
    </header>
    <nav class="tabs">${nav}</nav>
    ${body}`);
}

module.exports = { page, loginPage, dashboardPage, esc };
