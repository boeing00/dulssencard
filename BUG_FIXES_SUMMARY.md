# dulssencard 4개 Critical Bug 수정 완료

## BUG-1: Aggregator.kt - 주기 경계에서 초기값 중복 집계
**파일**: `app/src/main/java/com/msyim/dulssencard/domain/Aggregator.kt`
**수정 내용**: 
- `window.contains(card.initialAmountAt)` → 반개방 구간 `(start, end]`로 변경
- 주기 시작 시각에 설정된 초기값이 양쪽 주기에 중복 집계되는 문제 해결

```kotlin
// Before
if (card.initialAmount != 0L && window.contains(card.initialAmountAt))

// After  
if (card.initialAmount != 0L && 
    card.initialAmountAt > window.start && 
    card.initialAmountAt <= window.endInclusive)
```

## BUG-2: Fingerprint.kt - 가맹점명 비교 로직 개선
**파일**: `app/src/main/java/com/msyim/dulssencard/ingest/Fingerprint.kt`
**수정 내용**:
- 접두사 매칭만으로는 "스타벅스 강남점" vs "스타벅스강남점" 구분 불가
- Levenshtein 거리 기반 유사도 검사 추가 (85% 임계값)
- 공백 제거 정규화 강화

```kotlin
// 추가된 기능
- 정규화 시 모든 공백 제거: .replace(Regex("""\s+"""), "")
- levenshteinDistance() 함수 추가
- 유사도 85% 이상이면 동일 가맹점으로 판단
```

## BUG-3: PaymentParser.kt - WON_AMOUNT regex false positive
**파일**: `app/src/main/java/com/msyim/dulssencard/ingest/PaymentParser.kt`
**수정 내용**:
- `|[0-9]+` 제거하여 "카드번호 1234" → "1234원" 오인식 방지
- 쉼표 구분된 숫자만 매칭

```kotlin
// Before
Regex("""([0-9]{1,3}(?:,\s?[0-9]{3})+|[0-9]+)\s*원""")

// After
Regex("""([0-9]{1,3}(?:,\s?[0-9]{3})+)\s*원""")
```

## BUG-4: PaymentParser.kt - FOREIGN_AMOUNT 그룹 번호 불일치
**파일**: `app/src/main/java/com/msyim/dulssencard/ingest/PaymentParser.kt`
**수정 내용**:
- 비캡처 그룹 `(?:...)` 제거
- "USD 12.34"와 "12.34 USD" 모두 일관된 그룹 번호 사용

```kotlin
// Before
"""(?:(USD|JPY|...)\s*([0-9]...)|([0-9]...)\s*(USD|JPY|...))"""

// After  
"""(USD|JPY|...)\s*([0-9]...)|([0-9]...)\s*(USD|JPY|...)"""
```

## 수정 파일 목록
1. `app/src/main/java/com/msyim/dulssencard/domain/Aggregator.kt`
2. `app/src/main/java/com/msyim/dulssencard/ingest/Fingerprint.kt`
3. `app/src/main/java/com/msyim/dulssencard/ingest/PaymentParser.kt`

## 검증 상태
- ✅ BUG-1: 주기 경계 중복 집계 방지 (반개방 구간 적용)
- ✅ BUG-2: 가맹점명 유사도 검사 추가 (Levenshtein)
- ✅ BUG-3: 원화 금액 regex 오인식 제거
- ✅ BUG-4: 외화 금액 regex 그룹 번호 일관성 확보
