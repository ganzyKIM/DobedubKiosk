'use strict';
// 서버 렌더링 HTML (프레임워크 없이 템플릿 문자열). 라임그린 브랜드 톤, 다크/라이트 대응.

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
header.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:24px;}
.logo{font-weight:800;font-size:20px;} .logo b{color:var(--brand-ink);}
@media (prefers-color-scheme:dark){.logo b{color:var(--brand);}}
a.btn,button.btn{display:inline-block;background:var(--brand);color:#1a2b00;border:0;border-radius:10px;padding:9px 16px;font-weight:700;font-size:14px;cursor:pointer;text-decoration:none;}
a.btn.ghost,button.btn.ghost{background:transparent;border:1px solid var(--line);color:var(--fg);}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:14px;margin-bottom:22px;}
.kpi{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px 18px;}
.kpi .n{font-size:30px;font-weight:800;line-height:1.1;} .kpi .l{color:var(--muted);font-size:13px;margin-top:4px;}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:18px 20px;margin-bottom:20px;}
.card h2{margin:0 0 14px;font-size:16px;}
table{width:100%;border-collapse:collapse;font-size:14px;}
th,td{text-align:left;padding:10px 8px;border-bottom:1px solid var(--line);vertical-align:middle;}
th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.02em;}
.badge{display:inline-block;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:700;}
.b-ok{background:color-mix(in srgb,var(--ok) 18%,transparent);color:var(--ok);}
.b-warn{background:color-mix(in srgb,var(--warn) 18%,transparent);color:var(--warn);}
.b-bad{background:color-mix(in srgb,var(--bad) 18%,transparent);color:var(--bad);}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;vertical-align:middle;}
.mono{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;color:var(--muted);}
.verbar{height:8px;border-radius:6px;background:var(--line);overflow:hidden;margin-top:8px;}
.verbar>span{display:block;height:100%;background:var(--brand);}
form.inline{display:inline;} input,select{font:inherit;padding:8px 10px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg);}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;}
.muted{color:var(--muted);} .small{font-size:13px;}
.right{text-align:right;} .center{text-align:center;}
.overflow{overflow-x:auto;}
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

function dashboardPage({ devices, release, stats }) {
  const online = 15 * 60 * 1000;     // 15분 이내 = 온라인
  const stale = 24 * 60 * 60 * 1000; // 24시간 초과 = 장기 미접속

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

  const rows = devices.map(d => {
    const age = Date.now() - d.last_seen;
    let dotColor = 'var(--ok)', statusText = '온라인';
    if (age > stale) { dotColor = 'var(--bad)'; statusText = '오프라인'; }
    else if (age > online) { dotColor = 'var(--warn)'; statusText = '대기'; }
    const isLatest = release && d.version_code === release.version_code;
    const verBadge = release
      ? (isLatest ? '<span class="badge b-ok">최신</span>' : '<span class="badge b-warn">업데이트 필요</span>')
      : '';
    const batt = Number.isFinite(d.battery) ? `${d.battery}%` : '-';
    const lock = d.kiosk_locked ? '🔒' : '🔓';

    // 영상 목록 + 원격 삭제
    const vids = Array.isArray(d.videoList) ? d.videoList : [];
    const pending = new Set(d.pendingDeletes || []);
    const vidRows = vids.map(v => `<div class="row" style="justify-content:space-between;gap:8px;padding:5px 0;border-bottom:1px solid var(--line);">
        <span class="small" style="word-break:break-all;">${esc(v.name)} <span class="muted">${fmtBytes(v.size)}</span></span>
        ${pending.has(v.name)
          ? '<span class="badge b-warn">삭제 대기</span>'
          : `<form class="inline" method="post" action="/device/video/delete" onsubmit="return confirm('이 영상을 이 태블릿에서 삭제할까요?\\n다음 접속 시 기기에서 실제로 지워집니다.');">
               <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
               <input type="hidden" name="filename" value="${esc(v.name)}">
               <button class="btn ghost" type="submit" style="padding:3px 10px;">삭제</button>
             </form>`}
      </div>`).join('');
    const videoCell = vids.length === 0
      ? '<span class="muted small">없음</span>'
      : `<details><summary style="cursor:pointer;">${vids.length}개${pending.size ? ` <span class="badge b-warn">대기 ${pending.size}</span>` : ''}</summary>
           <div style="min-width:260px;margin-top:6px;">${vidRows}</div></details>`;

    return `<tr>
      <td><span class="dot" style="background:${dotColor}"></span>${esc(statusText)}</td>
      <td>
        <form class="inline" method="post" action="/device/label">
          <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
          <input name="label" value="${esc(d.app_label || '')}" placeholder="기관명" size="12" onchange="this.form.submit()">
        </form>
        <div class="mono">${esc(d.device_id)}</div>
      </td>
      <td>${esc(d.model || '-')}</td>
      <td>${esc(d.version_name || '?')} <span class="mono">${d.version_code == null ? '' : 'code ' + d.version_code}</span><br>${verBadge}</td>
      <td>${lock} ${batt}</td>
      <td>${videoCell}</td>
      <td>${esc(relTime(d.last_seen))}<div class="mono">${d.checkin_count}회</div></td>
      <td class="right">
        <form class="inline" method="post" action="/device/delete" onsubmit="return confirm('이 기기 기록을 삭제할까요? (기기가 다시 체크인하면 재등록됩니다)');">
          <input type="hidden" name="deviceId" value="${esc(d.device_id)}">
          <button class="btn ghost" type="submit">삭제</button>
        </form>
      </td>
    </tr>`;
  }).join('');

  const rel = release ? `
    <div class="row" style="justify-content:space-between;">
      <div>
        <div><b>${esc(release.version_name)}</b> <span class="mono">code ${release.version_code}</span></div>
        <div class="muted small">${fmtBytes(release.size)} · 업로드 ${esc(relTime(release.uploaded_at))}</div>
        <div class="mono" style="word-break:break-all;">sha256: ${esc(release.sha256)}</div>
        ${release.notes ? `<div class="small" style="margin-top:6px;">${esc(release.notes)}</div>` : ''}
      </div>
      <a class="btn ghost" href="/download/app.apk">APK 내려받기</a>
    </div>` : '<p class="muted">아직 배포된 APK가 없습니다. 아래에서 첫 버전을 업로드하세요.</p>';

  return page('기기 현황 · 두비덥 키오스크 관리', `
    <header class="top">
      <div class="logo">두비덥 <b>키오스크</b> 관리</div>
      <div class="row">
        <a class="btn ghost" href="/dashboard">새로고침</a>
        <a class="btn ghost" href="/logout">로그아웃</a>
      </div>
    </header>

    <div class="kpis">
      <div class="kpi"><div class="n">${stats.total}</div><div class="l">전체 기기</div></div>
      <div class="kpi"><div class="n" style="color:var(--ok)">${stats.online}</div><div class="l">온라인 (15분 내)</div></div>
      <div class="kpi"><div class="n" style="color:var(--bad)">${stats.offline}</div><div class="l">오프라인 (24시간+)</div></div>
      <div class="kpi"><div class="n">${stats.onLatest}/${stats.total}</div><div class="l">최신 버전</div></div>
    </div>

    <div class="card"><h2>현재 배포 버전</h2>${rel}
      <hr style="border:0;border-top:1px solid var(--line);margin:16px 0;">
      <form method="post" action="/release/upload" enctype="multipart/form-data" class="row">
        <input type="file" name="apk" accept=".apk" required>
        <input name="versionCode" type="number" min="1" placeholder="versionCode (예: 2)" required style="width:170px">
        <input name="versionName" placeholder="versionName (예: 1.1)" required style="width:170px">
        <input name="notes" placeholder="릴리스 메모(선택)" size="24">
        <button class="btn" type="submit">새 APK 업로드 · 배포</button>
      </form>
      <p class="muted small" style="margin:8px 0 0;">versionCode 는 앱 <span class="mono">build.gradle.kts</span> 의 값과 동일하게 입력하세요. 기존보다 높아야 기기들이 자동 업데이트합니다.</p>
    </div>

    <div class="card"><h2>버전 분포</h2>${verDist}</div>

    <div class="card"><h2>기기 목록 <span class="muted small">(${devices.length}대)</span></h2>
      <div class="overflow"><table>
        <thead><tr><th>상태</th><th>기기 / 기관</th><th>모델</th><th>버전</th><th>잠금·배터리</th><th>영상</th><th>마지막 접속</th><th></th></tr></thead>
        <tbody>${rows || '<tr><td colspan="8" class="center muted">아직 체크인한 기기가 없습니다.</td></tr>'}</tbody>
      </table></div>
    </div>
  `);
}

module.exports = { page, loginPage, dashboardPage, esc };
