# autokkk — 카톡 답장 브릿지 (안드로이드 앱)

전용 안드폰에서 **카카오톡 알림만** 읽어 방·보낸이·본문을 파싱하고,
`kakao-reply-bridge` 서버(`/ingest`)로 전송하는 자작 앱.

- 대상 폰: 갤럭시 Z폴드2(SM-F916N), Android 13 / One UI 5.1.1 (minSdk 26, target 34)
- 보안: `com.kakao.talk` 알림만 처리. 다른 앱(은행·개인) 알림은 패키지 필터로 무시.
- 설정(서버주소·토큰)은 앱 내부 저장소에만 보관 — 코드/repo에 비밀 없음.

## 단계 (kakao-reply-bridge 스펙 기준)

- **step2 (이 커밋): 수신만** — 카톡 알림 → 파싱 → `/ingest` POST. 답장 액션은 받은 즉시 보관(발송은 아직).
- step3: 승인된 답장을 `/pending` 폴 → RemoteInput으로 발송 → `/sent` 콜백.
- step4: 서버에서 Hermes 초안 + 텔레그램 승인 게이트.

## 빌드 (GitHub Actions)

로컬 안드 툴체인 불필요. 푸시하면 `.github/workflows/build.yml`이 자동 빌드.

1. 이 repo에 push(또는 Actions 탭에서 **Build APK** 수동 실행).
2. 완료된 워크플로 → **Artifacts → `autokkk-debug-apk`** 다운로드.
3. 압축 풀면 `app-debug.apk` → 폰에 설치(출처 불명 앱 허용 필요).

## 설치 후 설정 (폰)

1. 앱 실행 → **서버 주소**(예: `http://192.168.45.77:8195`)와 **폰 토큰**(서버 `KAKAO_BRIDGE_PHONE_TOKEN` 값) 입력 → **설정 저장**.
2. **알림 접근 권한 열기** → 목록에서 "카톡 브릿지" 켜기(이 권한이 핵심).
3. (Android 13) **알림 표시 권한 요청** 1회 허용.
4. **서버 연결 테스트 전송**으로 서버 도달 확인(서버 `/pending`/DB에 `테스트방` 메시지 보이면 OK).
5. 카톡방에 메시지가 오면 상태창 "최근 수신"에 `[방] 보낸이: 내용`이 떠야 함.

## 구조

- `KakaoListenerService.kt` — NotificationListenerService. 카톡 알림 파싱(MessagingStyle 우선·extras 폴백) + `/ingest` 전송 + 답장 액션 보관.
- `ServerBridge.kt` — 서버 HTTP(POST `/ingest`, Bearer 토큰).
- `ReplyStore.kt` — 방키→답장 액션(RemoteInput) 메모리 보관(step3 발송용).
- `MainActivity.kt` — 설정 UI·권한·상태·테스트.

## 주의

- 카톡 단톡 보낸이 파싱은 기기/버전별로 다를 수 있어, 실제 폰에서 "최근 수신" 표시로 정확도 점검 후 보정한다(step2의 목적).
- 서버는 같은 네트워크(M5 LAN)에 있어야 폰이 도달 가능.
