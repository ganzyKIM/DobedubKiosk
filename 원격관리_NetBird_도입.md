# NetBird 원격 관리 — 도입 완료 (2026-08-15)

다른 망(도서관)의 태블릿을 **고정 주소**로 원격 관리하기 위한 연결 계층.
2026-08-15 맥 개발 환경에서 실기기(HA1EKRNR) 스모크 테스트까지 완료했다.

## 0. 결정 경위 (Tailscale 기획을 뒤집은 이유)

8/12 `원격관리_Tailscale_기획.md`는 "무료 100대, 도메인 불필요"를 근거로 Tailscale을
선택했다. **그 전제가 2026-08 현재 사실이 아니다:**

- Tailscale 무료(Personal)는 **비상업 용도 한정**, tagged 기기 50대 한도. 도서관 납품
  함대는 상업 용도 → 약관 위반. 유료 전환 시 $8/인/월(연 12만원+).
- 재검토 대안: Cloudflare 명명 터널(도메인 필요), NetBird(무료 5인/100대, 상업 금지
  조항 없음), ZeroTier(무료 10대로 축소 — 탈락), VPS/포트포워딩(관리·보안 부담).
- 개발자 권고는 "명명 터널 + 저가 도메인"이었으나 **사용자 결정으로 NetBird 확정**
  (도메인 없이 진행).

## 1. 현재 구성 (실측 완료)

```
[맥 (임시 함대 서버)]                     [태블릿 HA1EKRNR]
 fleet server :8090                       키오스크 앱 ── 체크인: http://100.109.70.91:8090
 NetBird 데몬 100.109.70.91  ←─ WireGuard P2P ─→  NetBird 앱 100.109.230.149
        └───────────── NetBird Cloud (api.netbird.io) ─────────────┘
```

- **계정**: devops@dobedub.com (회사 공용). 무료 플랜 5사용자/100피어.
- **그룹**: 태블릿 = `kiosk` (setup key가 자동 배정), 서버 = `fleet-server`.
- **Setup key**: `kiosk-fleet` (reusable·무만료·무제한). 대시보드 Settings > Setup Keys.
  ⚠ 유출 시 대시보드에서 즉시 revoke — 기존 등록 기기는 영향 없음.
- **정책(ACL)**: `kiosk → fleet-server TCP 8090`만 허용, Default(전부 허용) **비활성**.
  태블릿끼리·태블릿→서버의 다른 포트 전부 차단됨.
- **맥 데몬**: brew `netbirdio/tap/netbird`, `netbird service install`로 상시 구동.
  Interface type **Userspace(netstack)** — 맥 로컬에서 자기 넷버드 IP를 curl하면
  타임아웃이 정상이다(원격 피어發 인바운드는 로컬 서비스로 프록시됨).

## 2. 실측 결과 (2026-08-15, 같은 LAN)

| 항목 | 결과 |
|---|---|
| 태블릿 체크인 (넷버드 경유) | ✅ 서버가 소스 IP `100.109.230.149`로 수신 |
| 연결 유형 | **P2P 직결** (LAN 후보로 직결, 레이턴시 6ms) |
| 즉시 깨우기(long-poll wake) | ✅ **1초** (넷버드 경유) |
| 좁힌 정책 하 통신 | ✅ 정상 (8090 외 차단) |
| Lock Task(키오스크 잠금) 공존 | ✅ 잠금 상태에서 1초 체크인, VPN 유지 |
| 배터리 최적화 예외 | `dumpsys deviceidle whitelist +io.netbird.client` 적용됨 |

## 3. 태블릿 등록 절차 (신규 기기 세팅 시)

앱이 **무인 등록(managed config)을 지원하지 않는다** — 세팅 중 화면 조작이 필요하다.
adb로 자동화 가능한 절차 (이번에 실제 사용한 방법):

1. `netbird-v0.5.0.apk` 설치 (netbirdio/android-client GitHub 릴리스, 208MB)
2. 앱 실행 → Continue → 메뉴(☰) → **Change Server** → Yes(초기화 동의)
3. Server: `https://api.netbird.io` / **+ Add this device with a setup key** → 키 입력 → Change
   - ⚠ **한글 IME가 켜져 있으면 `adb input text`가 자모로 깨진다.**
     ADBKeyboard(브로드캐스트 IME)를 설치해 `ADB_INPUT_TEXT`로 주입하고 끝나면 Gboard 복원.
4. 메인 화면 로고 탭 → 시스템 VPN 동의창 **확인**
5. 대시보드 Peers에서 등록 확인(그룹 `kiosk` 자동) → 키오스크 앱 원격 관리 화면에서
   서버 주소를 `http://100.109.70.91:8090`으로
6. `dumpsys deviceidle whitelist +io.netbird.client` (doze 보호)

> 세팅 스크립트(`setup-tablet.sh`/`.ps1`)에 이 절차를 넣는 작업은 미착수 — 다음 기기
> 세팅 전에 스크립트화할 것 (ADBKeyboard 동봉 포함).

## 4. 남은 검증·작업

- [ ] **다른 망 테스트**: 핫스팟/타 와이파이에서 direct/relay 여부 + 1GB 영상 push 속도
      (기획 §5.2/5.5 승계. relay로 떨어지면 속도 실측 필수)
- [ ] **재부팅 생존**: 태블릿 재부팅 후 VPN 자동 복귀 확인. 안 되면 Device Owner의
      `setAlwaysOnVpnPackage("io.netbird.client", false)` 앱 코드 추가 (lockdown=false)
- [ ] **세팅 스크립트 확장**: §3 절차 자동화
- [ ] **서버 주소 변경 원격 지시**(`setFleetUrl`): 기존 3대(마스터 PC 함대)를 넷버드로
      갈아태우거나, 향후 보고팡 AWS 이전 시 무통증 전환용
- [ ] 관리자 PC(윈도우 마스터)로 서버가 돌아갈 때: 그 PC에 NetBird 설치 + `fleet-server`
      그룹 배정 + 태블릿 주소를 새 피어 IP로 (또는 setFleetUrl로 일괄 변경)

## 5. 리스크 (알고 있을 것)

- **무료 플랜 의존**: 5사용자/100피어. 상업 금지 조항은 없으나 약관·한도 변경 리스크는
  남는다(ZeroTier 전례). 연 1회 재확인 권장. 탈출구: setFleetUrl 기능이 생기면 어떤
  주소 체계로든 원격 일괄 이전 가능.
- **NetBird 계정 = 함대 연결성의 단일 장애점**: devops@dobedub.com 복구 수단을 회사
  차원에서 관리할 것.
- **앱 버전 고정**: 검증본은 v0.5.0. 태블릿 쪽 자동 업데이트 경로가 없으므로(스토어
  미사용) 주요 버그픽스 시 수동 갱신 필요.
