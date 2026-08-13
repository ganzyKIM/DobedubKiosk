'use strict';
// 서버 렌더링 HTML (프레임워크 없이 템플릿 문자열). 라임그린 브랜드 톤, 다크/라이트 대응.
//
// 대시보드는 3개 탭으로 나뉜다: 기기 현황 / 앱 배포 / 영상 자료실.
// 예전엔 전부 한 페이지에 쌓여 있어서(배포 카드 → 이력 → 자료실 31행 → 버전 분포 → 기기 표)
// 정작 매일 보는 기기 현황이 스크롤 맨 밑에 있었다. 폼 POST 후에는 서버가 referer 로
// 돌려보내므로 탭이 유지된다(server.js 참조).

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
  const day = Math.floor(h / 24);
  return `${day}일 전`;
}

function fmtBytes(n) {
  if (!n) return '-';
  const mb = n / (1024 * 1024);
  return `${mb.toFixed(1)} MB`;
}

const STYLE = `
:root{--bg:#f6f7f5;--card:#fff;--fg:#1a1c19;--muted:#6b7280;--line:#e5e7eb;--brand:#8fd613;--brand-ink:#3d5c00;--ok:#16a34a;--warn:#d97706;--bad:#dc2626;}
@media (prefers-color-scheme:dark){:root{--bg:#14160f;--card:#1e2118;--fg:#eef0e6;--muted:#9aa08c;--line:#2c3024;--brand:#a6e22e;--brand-ink:#d7f59a;}}
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans KR",sans-serif;background:var(--bg);color:var(--fg);}
.wrap{max-width:1100px;margin:0 auto;padding:24px 20px 64px;}
header.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px;}
.logo{font-weight:800;font-size:20px;} .logo b{color:var(--brand-ink);}
@media (prefers-color-scheme:dark){.logo b{color:var(--brand);}}
a.btn,button.btn{display:inline-block;background:var(--brand);color:#1a2b00;border:0;border-radius:10px;padding:9px 16px;font-weight:700;font-size:14px;cursor:pointer;text-decoration:none;}
a.btn.ghost,button.btn.ghost{background:transparent;border:1px solid var(--line);color:var(--fg);}
.btn.sm{padding:3px 10px;font-size:13px;}
.tabs{display:flex;gap:8px;margin-bottom:20px;flex-wrap:wrap;}
.tab{padding:9px 18px;border-radius:999px;border:1px solid var(--line);text-decoration:none;color:var(--fg);font-weight:700;font-size:14px;background:var(--card);}
.tab.on{background:var(--brand);color:#1a2b00;border-color:transparent;}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:14px;margin-bottom:22px;}
.kpi{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px 18px;}
.kpi .n{font-size:30px;font-weight:800;line-height:1.1;} .kpi .l{color:var(--muted);font-size:13px;margin-top:4px;}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:18px 20px;margin-bottom:20px;}
.card h2{margin:0 0 14px;font-size:16px;}
table{width:100%;border-collapse:collapse;font-size:14px;}
th,td{text-align:left;padding:10px 8px;border-bottom:1px solid var(--line);vertical-align:top;}
th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.02em;}
.badge{display:inline-block;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:700;}
.b-ok{background:color-mix(in srgb,var(--ok) 18%,transparent);color:var(--ok);}
.b-warn{background:color-mix(in srgb,var(--warn) 18%,transparent);color:var(--warn);}
.b-bad{background:color-mix(in srgb,var(--bad) 18%,transparent);color:var(--bad);}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:6px;}
.mono{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;color:var(--muted);}
.verbar{height:8px;border-radius:6px;background:var(--line);overflow:hidden;margin-top:8px;}
.verbar>span{display:block;height:100%;background:var(--brand);}
form.inline{display:inline;} input,select{font:inherit;padding:8px 10px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg);}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;}
.muted{color:var(--muted);} .small{font-size:13px;}
.right{text-align:right;} .center{text-align:center;}
.overflow{overflow-x:auto;}
dialog{border:1px solid var(--line);border-radius:14px;background:var(--card);color:var(--fg);padding:18px 20px;min-width:340px;max-width:540px;}
dialog::backdrop{background:rgba(0,0,0,.45);}
.picklist{max-height:320px;overflow-y:auto;}
.pick{display:flex;align-items:center;gap:10px;padding:8px 6px;border-bottom:1px solid var(--line);cursor:pointer;}
.pick.off{opacity:.55;cursor:default;}
.thumb{width:44px;height:66px;object-fit:cover;border-radius:8px;display:block;background:var(--line);}
.thumb-none{display:flex;align-items:center;justify-content:center;color:var(--muted);font-size:11px;}
details.fold>summary{cursor:pointer;font-weight:600;color:var(--muted);font-size:13px;list-style:none;}
details.fold>summary::before{content:'▸ ';} details.fold[open]>summary::before{content:'▾ ';}
details.fold>div{margin-top:8px;padding:10px;border:1px solid var(--line);border-radius:10px;}
.statline{font-weight:700;white-space:nowrap;}
`;

function page(title, body) {
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>${STYLE}</style></head><body><div class="wrap">${body}</div></body></html>`;
}

function loginPage(error) {
  return page('로그인 · 두비덥 키오스크 관리', `
    <header class="top"><div class="logo">두비덥 <b>키오스크</b> 관리</div></header>
    <div class="card" style="max-width:380px;margin:40px auto;">
      <h2>관리자 로그인</h2>
      ${error ? `<p class="badge b-bad" style="display:block;padding:8px 12px;margin-bottom:12px;">${esc(error)}</p>` : ''}
      <form method="post" action="/login">
        <p><input name="password" type="password" placeholder="관리자 비밀번호" style="width:100%" autofocus></p>
        <button class="btn" type="submit" style="width:100%">로그인</button>
      </form>
    </div>`);
}

// ---------- 기기 현황 탭 ----------

function devicesTab({ devices, release, media, stats, thresholds }) {
  const rows = devices.map(d => {
    const age = Date.now() - d.last_seen;
    // 상태 표현: 태블릿은 화면이 꺼지면(밤, 미사용) 체크인이 늦어지는 게 정상 동작이다.
    // "오프라인"이라고 쓰면 고장처럼 읽혀서, 마지막 접속 시각을 그대로 헤드라인으로 쓴다.
    const st = age <= thresholds.onlineMs ? { c: 'var(--ok)', t: '접속 중' }
             : age <= thresholds.staleMs ? { c: 'var(--warn)', t: '미접속' }
             : { c: 'var(--bad)', t: '연락 두절' };

    const isLatest = release && d.version_code === release.version_code;
    const verCell = `
      ${esc(d.version_name || '?')} <span class="mono">${d.version_code == null ? '' : 'code ' + d.version_code}</span>
      <div style="margin-top:4px;">${!release ? '' : isLatest
        ? '<span class="badge b-ok">최신</span>'
        : `<span class="badge b-warn">업데이트 필요</span>
           <div style="margin-top:6px;">${d.update_prompt
             ? '<span class="badge b-warn">🔔 알림 대기중</span>'
             : `<form class="inline" method="post" action="/device/update-prompt">
                  <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
                  <button class="btn ghost sm" type="submit">알림 보내기</button>
                </form>`}</div>`}</div>`;

    // 기기 식별: 라벨(수정 가능)이 주인공, 모델/AP/좌표/ID 는 보조 정보로 작게.
    const locBits = [];
    if (d.ap_ssid) locBits.push(`📶 ${esc(d.ap_ssid)}`);
    if (d.lat != null && d.lng != null) {
      locBits.push(`<a href="https://www.google.com/maps?q=${d.lat},${d.lng}" target="_blank" rel="noopener noreferrer">📍 지도</a>`);
    }
    const idCell = `
      <form class="inline" method="post" action="/device/label">
        <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
        <input name="label" value="${esc(d.app_label || '')}" placeholder="기관명" size="12" onchange="this.form.submit()">
      </form>
      <div class="mono">${esc(d.model || '')}${locBits.length ? ' · ' + locBits.join(' · ') : ''}</div>
      <div class="mono">${esc(d.device_id)}</div>`;

    // 영상: 개수 요약 + 펼치면 목록(삭제) / 수신 대기(취소) / 보내기 모달.
    const vids = Array.isArray(d.videoList) ? d.videoList : [];
    const pendingDel = new Set(d.pendingDeletes || []);
    const pendingPush = d.pendingPushes || [];
    const vidRows = vids.map(v => `<div class="row" style="justify-content:space-between;gap:8px;padding:5px 0;border-bottom:1px solid var(--line);">
        <span class="small" style="word-break:break-all;">${esc(v.name)} <span class="muted">${fmtBytes(v.size)}</span></span>
        ${pendingDel.has(v.name)
          ? '<span class="badge b-warn">삭제 대기</span>'
          : `<form class="inline" method="post" action="/device/video/delete" onsubmit="return confirm('이 영상을 이 태블릿에서 삭제할까요?\\n다음 접속 시 기기에서 실제로 지워집니다.');">
               <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
               <input type="hidden" name="filename" value="${esc(v.name)}">
               <button class="btn ghost sm" type="submit">삭제</button>
             </form>`}
      </div>`).join('');
    const pushRows = pendingPush.map(p => `<div class="row" style="justify-content:space-between;gap:8px;padding:5px 0;border-bottom:1px solid var(--line);">
        <span class="small" style="word-break:break-all;">📥 ${esc(p.original_name)} <span class="muted">${fmtBytes(p.size)}</span></span>
        <span class="row" style="gap:6px;">
          <span class="badge b-warn">전송 대기</span>
          <form class="inline" method="post" action="/media/push/cancel">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <input type="hidden" name="mediaId" value="${p.media_id}">
            <button class="btn ghost sm" type="submit">취소</button>
          </form>
        </span>
      </div>`).join('');

    // 보내기 모달: 이미 보유(이름 기준)/전송 대기 항목은 골라봐야 무시되므로 체크 자체를 막는다.
    const onDeviceNames = new Set(vids.map(v => v && v.name));
    const pendingPushIds = new Set(pendingPush.map(p => p.media_id));
    const pickRows = media.map(m => {
      const off = onDeviceNames.has(m.original_name) ? '<span class="badge b-ok">보유 중</span>'
                : pendingPushIds.has(m.id) ? '<span class="badge b-warn">전송 대기</span>'
                : null;
      return `<label class="pick${off ? ' off' : ''}">
          <input type="checkbox" name="mediaId" value="${m.id}" ${off ? 'disabled' : ''}>
          <span class="small" style="flex:1;word-break:break-all;">${esc(m.original_name)} <span class="muted">${fmtBytes(m.size)}</span></span>
          ${off || ''}
        </label>`;
    }).join('');
    const pushBadges = [];
    if (pendingPush.length) pushBadges.push(`<span class="badge b-warn">수신대기 ${pendingPush.length}</span>`);
    if (pendingDel.size) pushBadges.push(`<span class="badge b-warn">삭제대기 ${pendingDel.size}</span>`);
    const videoCell = `
      ${media.length === 0 ? '' : `<button class="btn ghost sm" type="button"
        onclick="document.getElementById('push-${esc(d.device_id)}').showModal()">영상 보내기…</button>`}
      <dialog id="push-${esc(d.device_id)}">
        <form method="post" action="/media/push"
              onsubmit="return this.querySelector('input[name=mediaId]:checked') ? true : (alert('보낼 영상을 선택하세요.'), false)">
          <h3 style="margin:0 0 4px;">영상 보내기 — ${esc(d.app_label || d.device_id)}</h3>
          <p class="muted small" style="margin:0 0 10px;">선택한 영상을 다음 접속 때 기기가 내려받습니다.</p>
          <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
          <div class="picklist">${pickRows}</div>
          <div class="row" style="justify-content:flex-end;margin-top:12px;">
            <button class="btn ghost" type="button" onclick="this.closest('dialog').close()">취소</button>
            <button class="btn" type="submit">선택한 영상 보내기</button>
          </div>
        </form>
      </dialog>
      <details class="fold" style="margin-top:8px;"><summary>보유 ${vids.length}개 ${pushBadges.join(' ')}</summary>
        <div style="min-width:280px;">${vidRows}${pushRows || ''}</div>
      </details>`;

    // 자주 안 쓰는 것(연락처/PIN/기록 삭제)은 접어둔다 — 표가 좁아지고 실수 여지도 줄어든다.
    const contactPending = d.contact_override && d.contact_override !== d.contact;
    const manageCell = `<details class="fold"><summary>관리</summary><div>
        <div class="small" style="margin-bottom:4px;">문의 연락처</div>
        <form class="inline" method="post" action="/device/contact">
          <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
          <input name="contact" value="${esc(d.contact_override || d.contact || '')}" placeholder="02-334-2227" size="12" onchange="this.form.submit()">
        </form>
        ${contactPending ? '<div style="margin-top:4px;"><span class="badge b-warn">적용 대기</span></div>' : ''}
        <div style="margin-top:10px;">${d.pin_reset
          ? '<span class="badge b-warn">PIN 초기화 대기중</span>'
          : `<form class="inline" method="post" action="/device/pin-reset" onsubmit="return confirm('이 기기의 관리자 PIN을 0000 으로 초기화할까요?\\n다음 접속 시 적용됩니다.');">
               <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
               <button class="btn ghost sm" type="submit">PIN 초기화</button>
             </form>`}</div>
        <div style="margin-top:10px;">
          <form class="inline" method="post" action="/device/delete" onsubmit="return confirm('이 기기 기록을 삭제할까요? (기기가 다시 체크인하면 재등록됩니다)');">
            <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
            <button class="btn ghost sm" type="submit">기기 기록 삭제</button>
          </form>
        </div>
      </div></details>`;

    return `<tr>
      <td><div class="statline"><span class="dot" style="background:${st.c}"></span>${esc(relTime(d.last_seen))}</div>
          <div class="mono">${esc(st.t)} · ${d.checkin_count}회</div></td>
      <td>${idCell}</td>
      <td>${verCell}</td>
      <td style="white-space:nowrap;">${d.kiosk_locked ? '🔒' : '🔓'} ${Number.isFinite(d.battery) ? d.battery + '%' : '-'}</td>
      <td>${videoCell}</td>
      <td>${manageCell}</td>
    </tr>`;
  }).join('');

  return `
    <div class="kpis">
      <div class="kpi"><div class="n">${stats.total}</div><div class="l">전체 기기</div></div>
      <div class="kpi"><div class="n" style="color:var(--ok)">${stats.online}</div><div class="l">접속 중 (${Math.round(thresholds.onlineMs / 60000)}분 내)</div></div>
      <div class="kpi"><div class="n" style="color:var(--bad)">${stats.offline}</div><div class="l">연락 두절 (${Math.round(thresholds.staleMs / 3600000)}시간+)</div></div>
      <div class="kpi"><div class="n">${stats.onLatest}/${stats.total}</div><div class="l">최신 버전</div></div>
    </div>

    <div class="card"><h2>기기 목록 <span class="muted small">(${devices.length}대)</span></h2>
      <div class="overflow"><table>
        <thead><tr><th>마지막 접속</th><th>기기</th><th>버전</th><th>전원</th><th>영상</th><th></th></tr></thead>
        <tbody>${rows || '<tr><td colspan="6" class="center muted">아직 체크인한 기기가 없습니다.</td></tr>'}</tbody>
      </table></div>
      <p class="muted small" style="margin:10px 0 0;">태블릿은 사용 중일 때 30분마다 접속합니다.
      화면이 꺼져 있으면 접속이 1~2시간까지 늦어지고, 밤에는 절전으로 아침까지 끊기는 것이 정상입니다 —
      "미접속"은 고장이 아니라 쉬고 있다는 뜻입니다.</p>
    </div>`;
}

// ---------- 앱 배포 탭 ----------

function releaseTab({ devices, release, releases, relPaging, stats }) {
  const outdatedCount = release
    ? devices.filter(d => d.version_code !== release.version_code).length
    : 0;

  const rel = release ? `
    <div class="row" style="justify-content:space-between;">
      <div>
        <div style="font-size:22px;font-weight:800;">${esc(release.version_name)} <span class="mono">code ${release.version_code}</span></div>
        <div class="muted small">${fmtBytes(release.size)} · 업로드 ${esc(relTime(release.uploaded_at))}</div>
        <div class="mono" style="word-break:break-all;">sha256: ${esc(release.sha256)}</div>
        ${release.notes ? `<div class="small" style="margin-top:6px;max-width:640px;">${esc(release.notes)}</div>` : ''}
      </div>
      <div class="row" style="align-items:flex-start;">
        <a class="btn ghost" href="/download/app.apk">APK 내려받기</a>
        ${outdatedCount > 0 ? `
          <form method="post" action="/release/notify-outdated" onsubmit="return confirm('업데이트가 안 된 기기 ${outdatedCount}대 전체에 업데이트 확인창을 띄우라고 지시할까요?\\n기기 쪽에서 확인을 누르면 바로 설치됩니다.');">
            <button class="btn" type="submit">업데이트 필요 기기(${outdatedCount}대)에 알림 보내기</button>
          </form>` : ''}
      </div>
    </div>` : '<p class="muted">아직 배포된 APK가 없습니다. 아래에서 첫 버전을 업로드하세요.</p>';

  const verDist = stats.versionDist.map(v => {
    const pct = stats.total ? Math.round(v.count / stats.total * 100) : 0;
    const latest = release && v.version_code === release.version_code;
    return `<div style="margin-bottom:10px;">
      <div class="row" style="justify-content:space-between;">
        <span>${esc(v.version_name || '?')} <span class="mono">(code ${v.version_code == null ? '?' : v.version_code})</span>
          ${latest ? '<span class="badge b-ok">최신</span>' : ''}</span>
        <span class="muted small">${v.count}대 · ${pct}%</span>
      </div>
      <div class="verbar"><span style="width:${pct}%"></span></div>
    </div>`;
  }).join('') || '<p class="muted">아직 체크인한 기기가 없습니다.</p>';

  const historyRows = (releases || []).map(r => `<tr>
      <td>${esc(r.version_name)} <span class="mono">code ${r.version_code}</span> ${r.active ? '<span class="badge b-ok">배포중</span>' : ''}</td>
      <td class="small">${fmtBytes(r.size)}</td>
      <td class="small">${esc(relTime(r.uploaded_at))}</td>
      <td class="small" style="max-width:280px;">${esc(r.notes || '')}</td>
      <td class="right">${r.active ? '' : `
        <form class="inline" method="post" action="/release/rollback" onsubmit="return confirm('${esc(r.version_name)} (code ${r.version_code}) 버전으로 롤백할까요?\\n기기들이 다음 체크인 때 이 버전으로 자동 다운그레이드/재설치됩니다.');">
          <input type="hidden" name="id" value="${r.id}">
          <button class="btn ghost sm" type="submit">이 버전으로 롤백</button>
        </form>`}</td>
    </tr>`).join('');

  return `
    <div class="card"><h2>현재 배포 버전</h2>${rel}</div>

    <div class="card"><h2>새 버전 배포</h2>
      <form method="post" action="/release/upload" enctype="multipart/form-data" class="row">
        <input type="file" name="apk" accept=".apk" required>
        <input name="versionCode" type="number" min="1" placeholder="versionCode (예: 2)" required style="width:170px">
        <input name="versionName" placeholder="versionName (예: 1.1)" required style="width:170px">
        <input name="notes" placeholder="릴리스 메모(선택)" size="24">
        <button class="btn" type="submit">업로드 · 배포</button>
      </form>
      <p class="muted small" style="margin:8px 0 0;">versionCode 는 앱 <span class="mono">build.gradle.kts</span> 의 값과 동일하게,
      기존보다 높게. 기기들은 다음 접속 때 자동 업데이트합니다. 릴리스 메모는 이 폼에서 입력해야 한글이 안 깨집니다.</p>
    </div>

    <div class="card"><h2>버전 분포</h2>${verDist}</div>

    <div class="card" id="rel"><h2>배포 이력 <span class="muted small">(전체 ${relPaging ? relPaging.total : (releases || []).length}개)</span></h2>
      <div class="overflow"><table>
        <thead><tr><th>버전</th><th>크기</th><th>업로드</th><th>메모</th><th></th></tr></thead>
        <tbody>${historyRows || '<tr><td colspan="5" class="center muted">이력이 없습니다.</td></tr>'}</tbody>
      </table></div>
      ${relPaging && relPaging.pages > 1 ? `
      <div class="row" style="justify-content:center;gap:6px;margin-top:12px;">
        ${relPaging.page > 1 ? `<a class="btn ghost" href="/dashboard?tab=release&relPage=${relPaging.page - 1}#rel">이전</a>` : ''}
        <span class="muted small">${relPaging.page} / ${relPaging.pages} 페이지</span>
        ${relPaging.page < relPaging.pages ? `<a class="btn ghost" href="/dashboard?tab=release&relPage=${relPaging.page + 1}#rel">다음</a>` : ''}
      </div>` : ''}
      <p class="muted small" style="margin:8px 0 0;">롤백은 올려둔 APK 를 그대로 다시 배포판으로 지정한다(재업로드 불필요).</p>
    </div>`;
}

// ---------- 영상 자료실 탭 ----------

function mediaTab({ media, mediaDir }) {
  const rows = media.map(m => `<tr>
      <td>
        ${m.thumb
          ? `<img class="thumb" src="/media/${m.id}/thumb?${m.uploaded_at}" alt="" loading="lazy">`
          : '<div class="thumb thumb-none">없음</div>'}
      </td>
      <td class="small" style="word-break:break-all;">${esc(m.original_name)}</td>
      <td class="small">${fmtBytes(m.size)}</td>
      <td class="small">${esc(relTime(m.uploaded_at))}</td>
      <td class="right" style="white-space:nowrap;">
        <form class="inline" method="post" action="/media/thumb" enctype="multipart/form-data">
          <input type="hidden" name="mediaId" value="${m.id}">
          <label class="btn ghost sm" style="cursor:pointer;">${m.thumb ? '썸네일 교체' : '썸네일 등록'}<input type="file" name="thumb" accept="image/*" style="display:none" onchange="this.form.submit()"></label>
        </form>
        <form class="inline" method="post" action="/media/delete" onsubmit="return confirm('자료실에서 이 영상을 삭제할까요? (이미 기기에 내려간 사본은 지워지지 않음)');">
          <input type="hidden" name="id" value="${m.id}">
          <button class="btn ghost sm" type="submit">삭제</button>
        </form>
      </td>
    </tr>`).join('') || '<tr><td colspan="5" class="center muted">등록된 영상이 없습니다.</td></tr>';

  return `
    <div class="card"><h2>영상 넣기</h2>
      <div style="background:color-mix(in srgb,var(--brand) 12%,transparent);border-radius:10px;padding:12px 14px;margin-bottom:14px;">
        <div style="font-weight:700;margin-bottom:4px;">📂 이 폴더에 영상을 복사해 넣으세요</div>
        <div class="mono" style="word-break:break-all;font-size:13px;">${esc(mediaDir || '')}</div>
        <div class="muted small" style="margin-top:6px;">
          넣으면 자동으로 등록된다(복사가 끝난 뒤 <b>새로고침</b> — 큰 파일은 해시 계산에 몇 초 걸린다).
          폴더에서 파일을 지우면 자료실에서도 사라진다.
        </div>
      </div>
      <form method="post" action="/media/upload" enctype="multipart/form-data" class="row">
        <input type="file" name="video" accept=".mp4,.m4v,.mkv,.webm" required>
        <button class="btn" type="submit">브라우저로 업로드</button>
        <span class="muted small">원격에서 작업할 때</span>
      </form>
    </div>

    <div class="card"><h2>영상 목록 <span class="muted small">(${media.length}개)</span></h2>
      <div class="overflow" style="max-height:560px;overflow-y:auto;"><table>
        <thead><tr><th>썸네일</th><th>파일</th><th>크기</th><th>업로드</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table></div>
      <p class="muted small" style="margin:8px 0 0;">기기로 보내는 것은 <a href="/dashboard">기기 현황</a> 탭의
      "영상 보내기" 에서. 썸네일은 태블릿 동영상 목록에 표시되며(없으면 영상 첫 장면),
      등록/교체하면 그 영상을 보유한 기기가 다음 접속 때 내려받는다.</p>
    </div>`;
}

// ---------- 조립 ----------

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
  const nav = TABS.map(t =>
    `<a class="tab${t.id === tab ? ' on' : ''}" href="/dashboard${t.id === 'devices' ? '' : '?tab=' + t.id}">${t.label}</a>`
  ).join('');

  return page(`${TABS.find(t => t.id === tab).label} · 두비덥 키오스크 관리`, `
    <header class="top">
      <div class="logo">두비덥 <b>키오스크</b> 관리</div>
      <div class="row">
        <a class="btn ghost" href="">새로고침</a>
        <a class="btn ghost" href="/logout">로그아웃</a>
      </div>
    </header>
    <nav class="tabs">${nav}</nav>
    ${body}`);
}

module.exports = { page, loginPage, dashboardPage, esc };
