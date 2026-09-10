# 덜쎈카드 — 작업 인계

이 파일은 **새 세션이 맨 처음 읽는 문서**다. 왜 이렇게 만들었는지는 `README.md`,
무엇을 만들기로 했는지는 `DulSsenCard_PRD_v4_ImplementationReady.md` 에 있다.
여기에는 **바로 손대기 전에 알아야 할 것**만 적는다.

---

## 0. 한 줄 요약

카드별 **그 주기의 사용 총액**이 목표·한도에 얼마나 왔는지 보여 주는 Android 앱.
총액은 두 조각을 더해 만든다:

```
현재 주기 합계 = initialAmount(사용자 입력) + Σ(그 뒤 도착한 결제 통지)
```

통지는 문자 앱 알림 · 카드사 앱 푸시 · 카카오 알림톡 셋에서 온다.
**네트워크 권한이 없다.** 전부 기기 안에서 끝난다.

### 제품 원칙 (PRD)

1. **자동화는 명확한 거래만** — 불확실하면 '확인 필요'로 사용자에게 넘긴다
2. **개인정보 최소화** — 통지 원문·주민번호 저장 금지. 지문(해시)만 남긴다
3. **사용자 통제** — 모든 집계값을 변경·제외·되돌릴 수 있다
4. **권한 투명성** — 권한이 없으면 제한 모드로 동작한다

모든 요약 화면에 고지가 붙는다:
> "사용자 설정과 통지 분류에 따른 추적값이며 카드사 공식 실적과 다를 수 있음"

> **이 앱은 명세서 앱이 아니다.** 개별 거래 하나하나를 정확히 재현하는 게 목적이 아니라,
> 그날까지의 총계를 아는 게 목적이다. 설계 판단이 갈릴 때는 이 기준으로 자른다.
> — 2026-09-08 사용자 결정, 아래 §4

- 패키지: `com.msyim.dulssencard` (디버그는 `.debug` 접미사)
- 버전: `0.1.0` / versionCode 1 · minSdk 26 · compileSdk·targetSdk 37
- APK: debug 67.8MB / release 50.9MB (대부분 ML Kit 한국어 OCR 모델)
- 테스트: **125개 전부 통과** (2026-09-08 기준)
- git: `73a8b1e P0 구현` 이 첫 커밋. 브랜치 `master`

---

## 1. 절대 깨면 안 되는 세 가지

이 셋은 앱의 정체성이자 Play 심사 서사다. 편의를 위해 무심코 어기기 쉬우니 먼저 못 박는다.

### ① 선언 권한은 알림 접근 하나뿐

병합된 릴리스 매니페스트에 **선언된 권한이 0개**다(`BIND_NOTIFICATION_LISTENER_SERVICE` 는
서비스 속성이라 별개). ML Kit 이 끌고 들어오는 `INTERNET` / `ACCESS_NETWORK_STATE` 는
`tools:node="remove"` 로 빼 뒀다.

```bash
# 권한이 새어 들어왔는지 확인 — 출력이 비어 있어야 정상
./gradlew :app:processReleaseManifest && \
  grep 'uses-permission' app/build/intermediates/merged_manifests/release/*/AndroidManifest.xml
```

**네트워크를 붙이는 라이브러리를 추가하면 이 약속이 깨진다.** 광고 SDK·Firebase·
분석 도구 전부 해당한다. 추가 전에 반드시 사용자에게 확인할 것.

### ② 거래 내용을 평문으로 디스크에 쓰지 않는다

DB 는 SQLCipher 로 암호화돼 있고, 암호는 Android Keystore 의 AES-256-GCM 키로 봉인해서
SharedPreferences 에 봉인된 형태로만 둔다. 디버깅한다고 OCR 원문이나 알림 본문을
`filesDir` 에 떨구면 이 방어가 통째로 무의미해진다.

> 실제로 그런 진단 코드를 넣었다가 2026-09-08 에 전부 제거했다.
> 지금 남아 있는 `sources_dump.txt` 는 **앱 이름·패키지명뿐**이고 DEBUG 빌드 한정이다.

디버깅이 필요하면 단위 테스트에 실측 문자열을 넣어라 — `DeviceOcrTest` 가 그 방식이다.

### ③ 이미지에서 온 거래는 자동 반영하지 않는다

캡처는 무엇이든 담을 수 있다. `TxSource.IMAGE` 는 언제나 `TxStatus.PENDING` 으로
들어가 사람이 확인해야 합계에 반영된다. 앱 설정 화면을 캡처했더니 한도 입력칸의
`1,000,000원` 이 100만원짜리 결제로 잡힌 적이 있다. (`BankAndForeignTest`)

---

## 2. 빌드 · 설치 · 테스트

Windows 에서 Git Bash 로 돌린다. **`MSYS_NO_PATHCONV=1` 을 안 주면 adb 인자의 슬래시를
Windows 경로로 바꿔 버려서 명령이 조용히 실패한다.**

```bash
cd /c/Users/moons/AndroidStudioProjects/dulssencard
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
ADB="C:/Users/moons/AppData/Local/Android/Sdk/platform-tools/adb.exe"

./gradlew :app:testDebugUnitTest        # 125개, 기기 없이 돈다
./gradlew :app:assembleDebug
"$ADB" -s R3CX50262WD install -r app/build/outputs/apk/debug/app-debug.apk
```

- 실기기: 갤럭시 S24+ (SM-S926N, Android 16), 시리얼 `R3CX50262WD`
- 에뮬레이터: AVD `Medium_Phone_API_36.1` (WHPX 로 돈다 — 한때 안 된다고 적어 뒀던 건 오기)
- 릴리스 lint 는 fatal 로 잡히니 `assembleRelease` 까지 돌려 볼 것

알림 접근은 앱이 요청할 수 없다. 설치 후 한 번:

```bash
adb shell cmd notification allow_listener com.msyim.dulssencard.debug/com.msyim.dulssencard.notification.PaymentNotificationListener
```

### 툴체인 함정 (한 번씩 다 밟았다)

| 증상 | 원인 | 대응 |
|---|---|---|
| `kotlin.android` 플러그인 적용 시 `newDsl` 오류 | AGP 9.2.1 이 Kotlin 2.3.21 을 내장. 명시 적용 금지 | 플러그인 블록에 넣지 말 것 |
| KSP 가 생성 소스를 등록 못 함 | 내장 Kotlin 이 `kotlin.sourceSets` 를 거부 | `gradle.properties` 의 `android.disallowKotlinSourceSets=false` (이미 있음) |
| 릴리스 lint `InvalidFragmentVersionForActivityResult` | ML Kit 이 fragment 1.0.0 을 끌고 옴 | `build.gradle.kts` 의 `constraints { fragment 1.8.9 }` (이미 있음) |

---

## 3. 수집 파이프라인 — 어디를 고칠지 찾는 법

```
알림 ─┐
      ├─→ PaymentNotificationListener (패키지 허용 목록으로 먼저 거른다)
푸시 ─┘        │
              ↓
캡처 ─→ ImageOcrHelper ─→ OcrLayout.rebuildRows  (좌표로 시각적 행 복원)
                                │
                    ┌───────────┴───────────┐
              목록 화면인가?            통지 한 건인가?
          LedgerScreenParser         OcrText.blocks → PaymentParser
                    └───────────┬───────────┘
                                ↓
                    Ingestor (지문 대조 · 중복 판정)
                                ↓
                   DulSsenRepository → SQLCipher DB
```

**증상별로 볼 파일:**

| 증상 | 파일 |
|---|---|
| 특정 카드사를 못 알아본다 | `ingest/IssuerRegistry.kt` |
| 금액·시각·가맹점을 잘못 읽는다 | `ingest/PaymentParser.kt` |
| 이용내역 캡처에서 건수가 모자라거나 넘친다 | `ingest/LedgerScreenParser.kt` |
| 가맹점과 금액의 짝이 어긋난다 | `ingest/OcrLayout.kt` (2단 레이아웃 문제) |
| 같은 결제가 두 번 잡힌다 / 멀쩡한 게 사라진다 | `ingest/Fingerprint.kt` + `ingest/Ingestor.kt` |
| 불러온 거래가 전부 '미분류' | `data/DulSsenRepository.importLedgerRows` 의 카드 키워드 매칭 |
| 수집 소스 목록이 비어 있다 | `DulSsenRepository.seedKnownSourceApps` |

### 반드시 알아야 할 설계 결정 네 개

1. **지문에서 전송 경로를 뺐다.** `SHA-256(카드사 | 분 단위 시각 | 금액 | 승인/취소)`.
   발신 번호·카드 뒷4자리·가맹점명은 경로마다 달라서 넣으면 같은 결제가 두 번 집계된다.
   대신 같은 분·같은 금액 충돌은 `Ingestor` 가 가맹점 표기로 갈라 `확인 필요`로 넘긴다.
   → 자세한 근거는 `README.md` §1.3. **여기를 고치기 전에 반드시 읽을 것.**

2. **`Text.getText()` 평문을 쓰면 안 된다.** ML Kit 은 2단 레이아웃의 오른쪽 열을
   문자열 끝으로 몰아 준다. 반드시 `OcrLayout.rebuildRows` 로 좌표 기반 재구성을 거친다.

3. **`원` 글자를 못 읽는 게 정상이다.** 실기기에서 `12,820원` → `12,820l`,
   `5,600원` → `5,6002!` 로 나왔다. 금액 정규식이 `원` 을 요구하면 한 건도 못 읽는다.
   지금은 **쉼표 자릿수 구분**을 근거로 삼는다.

4. **한국어 부분 문자열 충돌.** `누적금액` 에 `적금` 이 들어 있어서 은행 필터가
   정상 결제를 잡아먹은 적이 있다. `PaymentParser.SAFE_COMPOUNDS` 로 먼저 벗겨 낸다.

---

## 4. 초기 사용액(`initialAmount`) — **확정 사양, 구현 대기** (2026-09-08)

### 왜 이렇게 정했나

캡처 OCR 로 과거 내역을 복원하는 길은 **정확하지도 않고, 정확하게 만들려면 너무 복잡하며,
사용자 허들도 높다.** 실기기에서만 해도 `원` 글자 오인식, 2단 레이아웃 행 어긋남,
화면 종류마다 다른 구조, 은행 거래 혼입이 나왔고 전부 특수 처리해야 했다.

그런데 **이 앱에 필요한 건 개별 명세가 아니라 그날까지의 총계다.** 그러면 훨씬 단순한 길이 있다 —
카드앱이 이미 계산해 둔 **"이번 달 이용금액"** 을 사용자가 옮겨 적고, 그 뒤로는 통지를 더한다.
PRD §P0 도 이에 맞춰 고쳤다(`initialAmount` 명명은 PRD 를 따른다).

### 데이터 모델

`Card` 에 두 필드를 더한다 (Room `version = 3` → `4`, 마이그레이션 필요):

```kotlin
/** 주기 시작일부터 [initialAmountAt] 까지 쓴 금액. 사용자가 카드앱에서 보고 직접 입력한다. */
val initialAmount: Long = 0L,
/** 입력 기준 시각. 이 시각 **이전** 거래는 이미 initialAmount 에 들어 있어 세지 않는다. */
val initialAmountAt: Long = 0L,
```

`Aggregator.cardProgress` 의 `spent`:

```
spent = initialAmount(이번 주기 것일 때만) + Σ(이번 주기 && occurredAt > initialAmountAt)
```

### 반드시 지켜야 할 규칙 넷

**① `initialAmount` 는 입력한 그 주기에만 유효하다. 주기가 넘어가면 0으로 읽는다.**

```kotlin
private fun initialFor(card: Card, cycle: Cycle.Window): Long =
    if (card.initialAmountAt in cycle.start until cycle.end) card.initialAmount else 0L
```

빠뜨리면 **지난달 사용액이 이번 달에 영원히 얹힌다.** 이 작업에서 가장 위험한 버그이고
숫자가 그럴듯하게 크기만 해서 사용자가 알아채기 어렵다. **테스트로 먼저 잠글 것.**
필드를 지우는 게 아니라 **읽을 때 주기를 확인**하는 방식이어야 한다 — 지난 주기 기록은
남겨 두고, 다음 주기는 통지만으로 온전히 집계된다.

**② 기준 시각 이전 거래는 더하지 않는다.** 이미 `initialAmount` 에 포함돼 있다. 안 그러면 이중 집계.

**③ 기준 시각 이후의 취소는 그대로 차감한다.** 원 거래가 기준 이전이어도 마찬가지다 —
카드사 누적액도 같이 줄어들기 때문이다.

**④ 외화는 손대지 않는다.** `initialAmount` 는 원화 총계다. `foreignSpend` 는 지금 구조 그대로.

### 화면

- 카드 등록·편집에 입력칸. 안내 문구는 PRD 확정본을 그대로 쓴다:
  > "이번 달 {시작일}일부터 오늘까지 사용한 금액을 카드사 앱이나 문자에서 확인해서
  > 입력해주세요. 앞으로 도착하는 결제 문자는 자동으로 집계됩니다."
- 홈·상세에 출처를 밝힌다: `기준 9월 8일 15:00 · 320,000원 + 이후 결제 3건`.
  자기 숫자가 어디서 왔는지 알아야 다시 맞출 수 있다.
- **언제든 다시 입력해 덮어쓸 수 있게 한다.** 통지를 놓쳐 총계가 어긋났을 때
  카드앱 숫자를 다시 넣는 것이 가장 빠르고 확실한 복구 경로다. 이게 이 설계의 진짜 장점이다.

### 테스트로 먼저 잠글 것

```kotlin
@Test fun `초기값과 이후 통지를 더해 합계를 낸다`()
@Test fun `주기가 넘어가면 초기값을 세지 않는다`()      // ← 가장 중요
@Test fun `기준 시각 이전 거래는 초기값에 더하지 않는다`()
@Test fun `기준 이후 취소는 차감한다`()
@Test fun `초기값은 외화 합계에 섞이지 않는다`()
```

### OCR 코드는 **지금 지우지 않는다**

`initialAmount` 를 실기기에서 한 주기 써 보기 전에는 OCR 이 정말 불필요한지 확정할 수 없다.
그때 지우기로 하면 얻는 게 크다 — **APK 50.9MB → 약 11.8MB**(ML Kit 모델 39MB),
`tools:node="remove"` 술수 불필요, `ocr/`·`OcrLayout`·`OcrText`·`LedgerScreenParser` 와
테스트 4개가 통째로 사라진다.

그동안은 **홈의 주 경로를 `initialAmount` 로 바꾸고 캡처 불러오기는 설정 구석으로 옮긴다.**
`TxSource.IMAGE` 가 언제나 `PENDING` 이라는 §1③ 규칙은 그대로 유효하다.

> 문서에 "OCR 제거됨"이라고 적힌 것을 본다면 **아직 사실이 아니다.** 코드는 그대로 있다.

---

## 5. AI 인식은 보류다 (2026-09-08 결정)

OCR 오인식이 잦아 AI 로 대체하는 안을 검토했고, **셋 다 지금은 안 쓴다**고 정했다.

| 안 | 왜 안 썼나 |
|---|---|
| ML Kit GenAI Prompt API (온디바이스 Gemini Nano) | **기기가 지원하지 않는다.** S24+ 에서 `checkStatus()` 가 `0 = UNAVAILABLE`. AICore 는 깔려 있지만 ML Kit 의 기기 허용 목록에 아직 없다 (`genai-common-1.0.0-beta4.aar` 를 `javap` 로 열어 상수 확인) |
| Firebase AI Logic (클라우드 Gemini) | 키는 서버에 남아 안전하지만 **`INTERNET` 권한이 생긴다** — §1① 위반 |
| 사용자가 직접 키 입력 | 위와 같음 + 사용자에게 키 발급을 떠넘김 |

**지원 기기가 늘면 ①을 다시 볼 것.** 그때까지는 규칙 기반 파서를 계속 다듬는다.
같은 내용이 `app/build.gradle.kts` 주석에도 박혀 있다.

---

## 6. 테스트 — 고치기 전에 케이스를 먼저 넣는다

125개 전부 기기 없이 돈다. **새 문구·새 화면을 만나면 실측 문자열을 테스트에 먼저
박아 넣고 파서를 고친다.** 지어낸 샘플로는 실제 실패 양상이 재현되지 않는다.

| 파일 | 무엇을 잠그나 |
|---|---|
| `PaymentParserTest` | 카드사별 문구 회귀. 실제 채집한 우리·삼성 알림톡 포함 |
| `RealCorpusTest` | 공개 코퍼스 8개 카드사분 (kakao/credit-card-sms-parser, deprecated) |
| `DeviceOcrTest` | **갤럭시 S24+ 실측 OCR 출력 그대로.** 오타처럼 보이는 게 실제 출력이다 |
| `LedgerScreenParserTest` · `OcrLayoutTest` · `OcrImportTest` | 목록 화면·좌표 복원·이미지 반입 |
| `BankAndForeignTest` | 환전·송금 제외, 외화 분리, 이미지 자동반영 금지 |
| `IngestorTest` | 문자·푸시·알림톡으로 같은 결제가 와도 **한 번만** 집계 |
| `CycleAndAggregatorTest` | 주기(Asia/Seoul 고정, 시작일 1~28) · 집계 |

---

## 7. 다음에 할 일

### 순서대로

1. [x] `git init` + 첫 커밋 (`73a8b1e P0 구현`) — 완료
2. [ ] **`initialAmount` 구현** (§4). 테스트 → 모델·마이그레이션 → `Aggregator` → 화면 순서.
       **주기 넘어갈 때 0이 되는 것**을 테스트로 먼저 잠글 것.
3. [ ] 홈의 주 경로를 `initialAmount` 로 바꾸고 캡처 불러오기는 설정으로 물린다.
4. [ ] 실기기에서 한 주기 써 보고, OCR 제거 여부를 그때 판단한다.

### 아직 확인 못 한 것

- [ ] 카드사 **앱 푸시**의 실제 문구 — 알림톡·문자는 확보했지만 푸시는 아직 못 봤다.
      개시 잔액으로 바꿔도 **통지 파싱은 그대로 핵심 경로**라 이건 여전히 중요하다.
- [ ] 롯데·BC 등 코퍼스에 없는 카드사의 최신 문구
- [ ] `IssuerRegistry` 패키지명 기기 재확인 (NH농협은 `nh.smart.banking` 만 실측)
- [ ] 카드 등록 후 자동 반영·취소 차감이 실제 문자로 도는지
- [ ] (OCR 을 남기기로 할 경우에만) 우리카드 실적 내역 조회 화면 반입

### 출시 전

- [ ] `keystore.properties` + 서명 키 — **위임 금지, 사용자와 직접**
- [x] 런처 아이콘 — 완료. **다시 만들 때 Image Asset 마법사를 쓰지 말 것.**
      아트가 가장자리까지 꽉 차 있어 마법사 결과물은 adaptive icon 가시영역 밖으로
      글자 양끝과 고양이 얼굴이 잘린다. `python store/make_icons.py` 로 다시 뽑는다
      (배경 키잉 + 36dp 원 안에 들어가도록 스케일 계산). Play 512px 은 `store/ic_playstore_512.png`
- [ ] Play 심사 자료: 알림 접근의 핵심 기능성 · 허용 목록 · 미전송 (`README.md` §5)
- [ ] 개인정보처리방침 · 데이터 안전성 섹션

### P1 (PRD 에 있으나 미착수)

암호화 내보내기/가져오기, Drive 백업, 카드사별 파서 템플릿.
(PRD 의 '최근 14일 재스캔'은 `READ_SMS` 가 필요해 폐기했다 — 그 자리를 §4 개시 잔액이 대신한다.)

---

## 8. 되돌리지 말 것 — 한 번씩 "고쳐졌던" 것들 (2026-09-10)

아래 셋은 겉보기에 버그처럼 보여 수정됐다가 되돌린 것이다. **전부 `DesignIntentTest` 로
잠가 두었다.** 다시 손대기 전에 그 파일의 주석을 읽어라.

| "고친" 내용 | 왜 되돌렸나 |
|---|---|
| `window.contains()` → `t > start` 반개방 구간 | 주장("경계에서 중복 집계")이 사실이 아니다. `contains` 는 `start <= t < end` 라 한 시각은 한 주기에만 속한다. 바꾼 코드는 **주기 시작 정각의 초기값을 어느 주기에도 넣지 않아** 조용히 잃는다 |
| 가맹점 비교에 Levenshtein 85% 유사도 | 공백은 정규화가 이미 지우므로 `스타벅스 강남점`/`스타벅스강남점` 은 원래도 처리됐다. 유사도를 넣으면 **이름이 비슷한 다른 지점의 결제를 조용히 버린다.** 이 앱 규칙은 반대다 — 확신 없으면 버리지 말고 사용자에게 넘긴다 |
| `WON_AMOUNT` 에서 `\|[0-9]+` 제거 | 쉼표 있는 금액만 인식하게 돼 **1,000원 미만 결제가 통째로 안 읽힌다.** 코퍼스에 소액이 없어 테스트는 전부 통과했다 |

셋 다 테스트 150개를 통과한 채로 들어왔다. **테스트가 초록이라는 게 의도대로라는 뜻은 아니다** —
코퍼스에 없는 입력은 아무도 지켜 주지 않는다. 파서·집계를 고칠 때는 그 입력을 먼저 추가해라.

---

## 9. 이 문서를 고칠 때

**추측을 사실처럼 적지 말 것.** 한 번 이 문서가 통째로 재작성되면서 `DI: Hilt (예상)`,
`OCR 제거됨`, `PRD v5_ManualEntry.md`, 그리고 **PRD 판 지문 정의**(§3-1 에서 일부러 버린 것)가
들어온 적이 있다. 지문을 저대로 되돌리면 이 앱 최악의 버그가 되살아난다.

고치기 전에 확인하는 습관:

```bash
grep -rn "hilt" app/build.gradle.kts          # 의존성이 진짜 있나
ls app/src/main/java/com/msyim/dulssencard/   # 모듈 구조가 진짜 저런가
git log --oneline -3                          # 커밋이 진짜 있나
```

PRD 와 이 문서가 어긋나면 **PRD 가 무엇을·이 문서가 어떻게**다. 다만 §1 과 §3 의
설계 결정은 PRD 를 의도적으로 벗어난 것이므로, 그쪽이 이긴다(이유는 `README.md` §1).

---

## 10. 사용자에 대해

한국어로 대화한다. 실기기(S24+)로 직접 캡처해 가며 테스트하고, **"이 파일은 내용을 하나도
못 읽어" 같은 실패 제보가 가장 정확한 버그 리포트**다. 제보가 오면 추측하지 말고
그 캡처의 실제 OCR 출력을 확보해 테스트에 박아 넣는 순서로 간다 — 그렇게 해서 잡은 버그가
이 프로젝트 버그의 대부분이다.

항공 계산 로직·릴리스 서명·API 키 관련은 다른 프로젝트 포함해 **위임 금지**다.
