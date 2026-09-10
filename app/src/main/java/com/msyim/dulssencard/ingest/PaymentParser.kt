package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.domain.Cycle
import java.time.Instant
import java.time.LocalDateTime
import java.time.Year

/**
 * 수집한 원문 한 건. **저장하지 않는다.** 파싱 결과만 DB 로 넘어간다.
 *
 * @param senderKey SMS 면 발신 번호, 알림이면 패키지명. 지문 계산에도 저장에도 쓰지 않고
 *                  허용 목록 판정과 진단에만 쓴다.
 * @param title     알림 제목. 카카오 알림톡이면 발신 채널명(예: "신한카드")이라 단서로 값지다.
 */
data class RawMessage(
    val source: TxSource,
    val senderKey: String,
    val title: String?,
    val body: String,
    val receivedAt: Long,
)

/**
 * 파싱 결과. 자동 반영 여부는 [Ingestor] 가 카드 매칭까지 본 뒤 정한다.
 */
data class ParsedPayment(
    val issuerKey: String?,
    val direction: TxDirection,
    val amount: Long,
    val currency: String,
    /** 해외 승인의 외화 금액. 원화 환산은 하지 않는다(환율을 알 방법이 없다). */
    val foreignAmount: Double?,
    val occurredAt: Long?,
    /** 문구에 시각이 없어 수신 시각으로 대신했는가. 상세 화면에 '수신 시각 기준'으로 표시한다. */
    val occurredAtEstimated: Boolean,
    val merchant: String?,
    val cardSuffix: String?,
    val installment: Boolean,
    val overseas: Boolean,
    val confidence: Double,
    /** 카드 인식 키워드를 맞춰 볼 대상 문자열. 원문이 아니라 파싱에 쓴 정규화 텍스트다. */
    val matchText: String,
) {
    /**
     * PRD §9: 추출 필수값은 금액, 상태(승인/취소), 시각, 발신 식별자(= 카드사)다.
     * 하나라도 없으면 자동 반영하지 않는다.
     */
    val hasRequiredFields: Boolean
        get() = issuerKey != null && amount > 0L && occurredAt != null
}

/**
 * 한국 카드 결제 통지 파서.
 *
 * SMS·카드사 앱 푸시·카카오 알림톡을 한 함수로 처리한다. 세 경로의 문구가 사실상 같기 때문이다
 * (대개 같은 템플릿을 문자와 푸시로 함께 보낸다). 소스별로 다른 것은 카드사를 어디서 찾느냐뿐이다.
 *
 * 형식이 바뀌면 [VERSION] 을 올리고 회귀 테스트(PaymentParserTest)를 먼저 통과시킨다.
 * 거래에 파서 버전을 남기므로, 나중에 어느 버전이 만든 거래인지 추적할 수 있다.
 */
object PaymentParser {

    const val VERSION = "v1.0 · KR-MULTI"

    /** 자동 반영 신뢰도 하한. 필수 항목 검사와 함께 두 관문 모두 통과해야 한다. */
    const val AUTO_CONFIDENCE_THRESHOLD = 0.80

    private val KNOWN_CURRENCIES = listOf(
        "USD", "JPY", "EUR", "CNY", "GBP", "AUD", "CAD", "HKD", "SGD", "THB", "VND", "TWD", "PHP",
    )

    /**
      * `1,234원` / `1234 원` / `16, 000원`.
      *
      * 쉼표 뒤 공백을 허용하는 이유: OCR 이 `16,000` 을 `16, 000` 으로 뱉는다(실기기 확인).
      * 허용하지 않으면 캡처 이미지에서 금액을 통째로 놓친다.
      */
    private val WON_AMOUNT = Regex("""([0-9]{1,3}(?:,\s?[0-9]{3})+|[0-9]+)\s*원""")

    /** `USD 12.00` 또는 `12.00 USD`. */
    private val FOREIGN_AMOUNT = Regex(
        """(?:(${KNOWN_CURRENCIES.joinToString("|")})\s*([0-9][0-9,]*\.?[0-9]*)""" +
            """|([0-9][0-9,]*\.?[0-9]*)\s*(${KNOWN_CURRENCIES.joinToString("|")}))""",
    )

    /** `09/06 19:42`, `09/06 19:42:11`, `2026/09/06 19:42`. */
    private val DATE_TIME = Regex(
        """(?:(\d{4})[/.\-])?(\d{1,2})[/.\-](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""",
    )

    /**
     * 카드 뒷 4자리. 카드사마다 표기가 갈린다:
     * `우리(4321)승인` 처럼 괄호로 감싸거나, `삼성가족6839승인` 처럼 그냥 붙여 쓴다.
     */
    private val CARD_SUFFIX = Regex("""[(\[](\d{4})[)\]]|(\d{4})\s*(?:승인|취소)""")

    /** `3개월`, `03 개월`, `일시불`. */
    private val INSTALLMENT_MONTHS = Regex("""(\d{1,2})\s*개월""")

    /**
     * 금액으로 읽으면 안 되는 줄. 누적 실적·잔액·한도는 결제 금액이 아니다.
     * 이걸 놓치면 "누적1,234,567원"을 결제로 읽어 집계가 통째로 망가진다.
     */
    private val AMOUNT_NOISE = listOf(
        "누적", "잔액", "한도", "포인트", "적립", "할인", "잔여", "총", "합계", "이용가능", "가용",
    )

    /** 가맹점 후보에서 빼야 할 줄. */
    private val MERCHANT_NOISE = listOf(
        "web발신", "web 발신", "국외발신", "국제발신", "광고", "무료수신거부",
        "승인", "취소", "일시불", "할부", "개월", "누적", "잔액", "한도", "포인트", "적립",
        "체크카드", "신용카드", "결제", "출금", "입금", "알림", "안내", "님",
        // 카카오 알림톡 하단 정형 문구. 길이가 길어 가맹점 후보를 이겨 버린다.
        "채널", "마케팅", "메시지", "수신거부", "바로가기", "이용내역", "조회",
    )

    /**
     * **카드 결제가 아닌** 은행 거래.
     *
     * 실기기에서 은행 앱 캡처를 넣었더니 `환전주머니 6,696,200원` 이 우리카드 결제로
     * **자동 반영**됐다. 같은 필터가 [LedgerScreenParser] 에만 있었고, 통지로 인식되는
     * 경로는 그대로 통과시켰기 때문이다. 카드 실적이 계좌 이체액만큼 부풀어 오른다.
     *
     * `출금` 은 단독으로는 은행 출금이지만 `체크카드출금` 은 카드 결제라서 예외로 되살린다.
     */
    private val NON_CARD_WORDS = listOf(
        "환전", "송금", "이체", "예금", "적금", "대출", "상환", "현금서비스", "카드론",
        "ATM", "충전", "급여", "배당", "가상계좌", "자동납부", "펌뱅킹",
    )

    private val CARD_PAYMENT_OVERRIDE = listOf("체크카드출금", "카드출금")

    private fun isNonCardTransaction(text: String): Boolean {
        if (CARD_PAYMENT_OVERRIDE.any { text.contains(it) }) return false
        // `누적금액` 안에 `적금` 이 들어 있다. 한국어는 이런 부분문자열 충돌이 흔해서,
        // 카드 문구에 정상적으로 등장하는 합성어를 먼저 걷어내고 검사한다.
        val cleaned = SAFE_COMPOUNDS.fold(text) { acc, word -> acc.replace(word, " ") }
        return NON_CARD_WORDS.any { cleaned.contains(it) }
    }

    /** 카드 문구에 정상적으로 나오는 말. 은행 거래 판정에서 제외한다. */
    private val SAFE_COMPOUNDS = listOf("누적금액", "누적", "실적", "결제금액", "승인금액")

    fun parse(raw: RawMessage): ParsedPayment? {
        val body = raw.body.trim()
        if (body.isEmpty()) return null

        val haystack = listOfNotNull(raw.title, body).joinToString("\n")

        // 카드사 앱 푸시는 **패키지명이 가장 강한 단서**다. 본문 키워드를 먼저 보면
        // 현대카드 앱이 띄운 `하나로마트 성수점 12,000원 승인` 이 가맹점의 '하나' 때문에
        // 하나카드 거래로 둔갑한다 — 본문에 '현대'가 없으니 길이 규칙에서 '하나'가 이긴다.
        // 문자·알림톡은 전달자(문자 앱·카카오톡)가 발신자라 패키지에 카드사 정보가 없으므로
        // 지금까지처럼 본문·제목을 먼저 본다.
        val issuer = if (raw.source == TxSource.PUSH) {
            IssuerRegistry.byPackage(raw.senderKey) ?: IssuerRegistry.detect(raw.title, body)
        } else {
            IssuerRegistry.detect(raw.title, body) ?: IssuerRegistry.byPackage(raw.senderKey)
        }
        val declaredDirection = detectDirection(haystack)
        val strongSignal = STRONG_DIRECTION_WORDS.any { haystack.contains(it) }

        val foreign = FOREIGN_AMOUNT.find(haystack)
        val overseas = foreign != null || haystack.contains("해외")
        val wonAmount = extractWonAmount(body)

        val amount = wonAmount ?: 0L
        val currency = if (wonAmount != null) "KRW" else foreignCurrency(foreign) ?: ""
        val foreignAmount = if (wonAmount == null) foreignValue(foreign) else null

        val parsedTime = extractOccurredAt(haystack, raw.receivedAt)
        // 현대카드 문구처럼 날짜·시각이 아예 없는 형식이 있다. 결제 통지는 결제 직후에 오므로
        // 수신 시각이 충분히 좋은 근사값이다. 이걸 안 하면 그 카드사 거래가 전부 확인 필요로 쌓인다.
        val occurredAt = parsedTime ?: raw.receivedAt
        val occurredAtEstimated = parsedTime == null
        val installmentMonths = INSTALLMENT_MONTHS.find(haystack)?.groupValues?.get(1)?.toIntOrNull()
        val installment = (installmentMonths != null && installmentMonths > 1) ||
            (haystack.contains("할부") && !haystack.contains("일시불"))

        // 삼성법인카드처럼 '승인' 같은 방향어가 아예 없는 문구가 있다.
        // 카드사·금액·시각이 모두 갖춰졌으면 결제로 보고 승인으로 추정한다.
        // 셋을 다 요구하므로 광고·안내 문구가 여기로 새지 않는다.
        val direction = declaredDirection
            ?: if (issuer != null && amount > 0L && parsedTime != null) {
                TxDirection.APPROVAL
            } else {
                return null
            }

        // 환전·송금·이체는 카드로 쓴 돈이 아니다. 카드사 이름이 같이 있어도 마찬가지다
        // (은행 앱은 카드사 이름과 계좌 거래를 한 화면에 같이 보여 준다).
        if (isNonCardTransaction(haystack)) return null

        // "결제일 안내", "카드 사용 통계" 같은 안내 문구가 확인 결과함을 채우지 않게 한다.
        // 강한 신호가 없으면 금액이나 시각 중 하나는 있어야 결제로 본다.
        if (!strongSignal && amount <= 0L && parsedTime == null) return null

        // 카드사를 못 찾았는데 '승인/취소' 같은 강한 신호도 없으면 결제 통지가 아니다.
        //
        // 실기기에서 앱 설정 화면 캡처를 넣었더니 한도 입력칸의 `1,000,000원` 을 결제로 읽어
        // 100만원짜리 거래를 만들었다. `결제 문자 수집` 이라는 라벨의 '결제'가 약한 신호로
        // 잡힌 탓이다. 캡처 이미지는 무엇이든 담을 수 있으므로 이 관문이 특히 중요하다.
        if (issuer == null && !strongSignal) return null

        // 이미지 OCR 은 카드사 이름을 못 찾으면 아예 받지 않는다.
        // 화면 부스러기에 '승인' 같은 단어가 섞여 있을 수 있어 강한 신호만으로는 부족하다.
        if (raw.source == TxSource.IMAGE && issuer == null) return null

        val merchant = extractMerchant(body, IssuerRegistry.keywordsOf(issuer))
        val cardSuffix = CARD_SUFFIX.find(haystack)?.groupValues
            ?.drop(1)?.firstOrNull { it.isNotEmpty() }

        var confidence = 0.0
        if (issuer != null) confidence += 0.30
        if (amount > 0L) confidence += 0.30
        if (parsedTime != null) confidence += 0.20 else confidence += 0.12
        confidence += 0.10 // 승인/취소를 읽어 냈다는 뜻. 못 읽으면 여기까지 오지 않는다.
        if (merchant != null) confidence += 0.10

        return ParsedPayment(
            issuerKey = issuer?.key,
            direction = direction,
            amount = amount,
            currency = currency,
            foreignAmount = foreignAmount,
            occurredAt = occurredAt,
            occurredAtEstimated = occurredAtEstimated,
            merchant = merchant,
            cardSuffix = cardSuffix,
            installment = installment,
            overseas = overseas,
            confidence = minOf(confidence, 1.0),
            matchText = haystack,
        )
    }

    /**
     * 승인인지 취소인지.
     *
     * "승인취소"·"취소승인" 같은 조합이 있으므로 취소를 먼저 본다.
     * 둘 다 없으면 결제 통지가 아니라고 보고 null 을 돌려 아예 버린다
     * (광고·인증번호·잔액 안내 문자가 집계에 들어오지 않게 하는 1차 관문이다).
     */
    private fun detectDirection(text: String): TxDirection? = when {
        text.contains("취소") || text.contains("환불") -> TxDirection.CANCEL
        text.contains("승인") ||
            text.contains("결제") ||
            text.contains("사용") ||
            // 체크카드는 '승인' 대신 '출금'으로 적는 카드사가 있다(KB 등).
            // 계좌이체·ATM 출금과 섞이지 않게 '카드출금' 형태만 인정한다.
            text.contains("카드출금") -> TxDirection.APPROVAL
        else -> null
    }

    /** 이 단어들이 있으면 결제 통지라고 확신한다. "결제"·"사용"은 안내 문구에도 흔해 약한 신호다. */
    private val STRONG_DIRECTION_WORDS = listOf("승인", "취소", "환불", "카드출금")

    /**
     * 결제 금액을 뽑는다.
     *
     * 줄 단위로 보면서 누적·잔액·한도가 붙은 줄은 건너뛴다.
     * 남은 것 중 **첫 번째** 금액이 결제 금액이다 — 카드사 문구는 결제 금액을 항상 앞에 둔다.
     */
    private fun extractWonAmount(body: String): Long? {
        for (line in body.lines()) {
            val lower = line.lowercase()
            if (AMOUNT_NOISE.any { lower.contains(it) }) continue
            val match = WON_AMOUNT.find(line) ?: continue
            val value = match.groupValues[1].replace(",", "").replace(" ", "").toLongOrNull() ?: continue
            if (value > 0L) return value
        }
        // 줄바꿈 없이 한 줄로 오는 푸시를 위한 폴백:
        // 누적 뒤에 붙은 금액을 피하려고 '누적' 앞부분에서만 다시 찾는다.
        val head = body.substringBefore("누적").substringBefore("잔액")
        WON_AMOUNT.find(head)?.groupValues?.get(1)?.replace(",", "")?.replace(" ", "")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }

        // '원'을 안 붙이는 형식(KB 체크카드출금)을 위한 마지막 폴백.
        // 카드번호·계좌번호를 금액으로 읽지 않도록 **줄 전체가 금액**인 경우만 인정한다.
        body.lines()
            .map { it.trim() }
            .filter { line ->
                val lower = line.lowercase()
                AMOUNT_NOISE.none { lower.contains(it) } && BARE_AMOUNT.matches(line)
            }
            .firstNotNullOfOrNull { it.replace(",", "").replace(" ", "").toLongOrNull() }
            ?.takeIf { it > 0L }
            ?.let { return it }

        // 그 형식이 한 줄로 뭉쳐 오는 경우(앱 푸시가 대개 한 줄이다).
        // 시각과 마스킹된 카드번호를 먼저 지워야 `14:27` 이나 `078501**554` 를 금액으로 읽지 않는다.
        val stripped = MASKED_NAME.replace(DATE_TIME.replace(head, " "), " ")
        return BARE_AMOUNT.find(stripped)?.value?.replace(",", "")?.replace(" ", "")?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    /**
     * 줄 전체가 금액인 경우. `13,000` 처럼 단위 없이 오는 형식을 잡는다.
     * 마스킹된 카드번호(`078501**554`)나 4자리 숫자는 제외되도록 자릿수·형태를 좁게 잡았다.
     */
    private val BARE_AMOUNT = Regex("""[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{5,9}""")

    /** `USD 12.00` / `12.00 USD` 의 숫자 부분. */
    private fun foreignValue(match: MatchResult?): Double? {
        if (match == null) return null
        val groups = match.groupValues
        val raw = groups[2].ifEmpty { groups[3] }
        return raw.replace(",", "").replace(" ", "").toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun foreignCurrency(match: MatchResult?): String? {
        if (match == null) return null
        val groups = match.groupValues
        return groups[1].ifEmpty { groups[4] }.ifEmpty { null }
    }

    /**
     * `MM/dd HH:mm` 을 epoch millis 로. 연도는 메시지에 거의 없으므로 수신 시각에서 추론한다.
     *
     * 연말연시 함정: 12/31 결제 문자가 1/1 에 처리되면 올해로 읽어 1년 뒤가 된다.
     * 그래서 추론한 시각이 수신 시각보다 하루 이상 미래면 한 해 뒤로 물린다.
     */
    private fun extractOccurredAt(text: String, receivedAt: Long): Long? {
        val match = DATE_TIME.find(text) ?: return null
        val (yearRaw, monthRaw, dayRaw, hourRaw, minuteRaw, secondRaw) = match.destructured

        val month = monthRaw.toIntOrNull() ?: return null
        val day = dayRaw.toIntOrNull() ?: return null
        val hour = hourRaw.toIntOrNull() ?: return null
        val minute = minuteRaw.toIntOrNull() ?: return null
        val second = secondRaw.toIntOrNull() ?: 0
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null

        val receivedZoned = Instant.ofEpochMilli(receivedAt).atZone(Cycle.ZONE)
        val year = yearRaw.toIntOrNull() ?: receivedZoned.year
        if (!Year.isLeap(year.toLong()) && month == 2 && day > 28) return null

        val candidate = runCatching {
            LocalDateTime.of(year, month, day, hour, minute, second).atZone(Cycle.ZONE)
        }.getOrNull() ?: return null

        val adjusted = if (
            yearRaw.isEmpty() && candidate.toInstant().toEpochMilli() > receivedAt + DAY_MILLIS
        ) {
            candidate.minusYears(1)
        } else {
            candidate
        }
        return adjusted.toInstant().toEpochMilli()
    }

    /**
     * 가맹점 이름을 고른다.
     *
     * 확정적인 규칙이 없어 소거법을 쓴다. 금액·시각·카드사 이름·정형 문구를 걷어 내고
     * 남은 줄 중 가장 긴 것을 고른다. 못 고르면 null 이고, 화면에는 "미확인 가맹점"으로 나온다.
     * 가맹점은 필수 항목이 아니라서 못 찾아도 자동 반영을 막지는 않는다.
     */
    private fun extractMerchant(body: String, issuerKeywords: List<String>): String? {
        val lines = body.lines().map { stripEnclosingBrackets(it.trim()) }

        val surviving = lines.withIndex().filter { (_, line) ->
            line.isNotBlank() && isMerchantCandidate(line, issuerKeywords)
        }

        // 카드사 문구는 예외 없이 `시각` 다음 줄에 가맹점을 둔다.
        // 이 위치 단서가 "가장 긴 줄"보다 훨씬 정확하다 — 알림톡 하단의 마케팅 문구
        // ("채널 추가하고 이 채널의 마케팅 메시지 등을...")가 길이로는 가맹점을 이기기 때문이다.
        val timeLineIndex = lines.indexOfFirst { DATE_TIME.containsMatchIn(it) }
        if (timeLineIndex >= 0) {
            surviving.firstOrNull { it.index > timeLineIndex }?.let { return it.value }
        }

        return surviving.maxByOrNull { it.value.length }?.value
            ?: extractMerchantFromFlatText(body, issuerKeywords)
    }

    private fun isMerchantCandidate(line: String, issuerKeywords: List<String>): Boolean {
        val lower = line.lowercase()
        return MERCHANT_NOISE.none { lower.contains(it) } &&
            !WON_AMOUNT.containsMatchIn(line) &&
            !DATE_TIME.containsMatchIn(line) &&
            !FOREIGN_AMOUNT.containsMatchIn(line) &&
            !isIssuerHeaderLine(line, issuerKeywords) &&
            // 이름 마스킹(홍*동)이나 숫자만 있는 줄은 가맹점이 아니다.
            !line.contains('*') &&
            !line.all { it.isDigit() || it == '-' || it == ' ' } &&
            line.length in 2..40
    }

    /**
     * 양쪽을 감싼 괄호만 벗긴다.
     *
     * 무턱대고 `trim('(', ')')` 하면 `(주)한빛상사` 가 `주)한빛상사` 로 망가진다.
     * 한국 상호에 `(주)` 접두가 흔해서 실제로 자주 터진다.
     */
    private fun stripEnclosingBrackets(line: String): String = when {
        line.startsWith('[') && line.endsWith(']') -> line.drop(1).dropLast(1).trim()
        line.startsWith('(') && line.endsWith(')') -> line.drop(1).dropLast(1).trim()
        else -> line
    }

    /**
     * 줄바꿈 없이 한 줄로 오는 통지에서 가맹점을 건진다.
     *
     * 카드사 앱 푸시와 카카오 알림톡은 대개 한 줄이라, 줄 단위 소거법이 통째로 실패한다
     * (그 한 줄에 금액도 시각도 '승인'도 다 들어 있어 후보에서 탈락한다).
     *
     * 그래서 줄이 아니라 **조각**을 지운다. 금액·시각·카드사·정형 문구를 구분자로 치환한 뒤
     * 남은 조각 중 가장 긴 것을 가맹점으로 본다.
     * `신한카드(1234)승인 홍*동 84,300원 일시불 09/08 09:25 이마트 성수 누적1,234,567원`
     * → 남는 조각은 `이마트 성수`.
     */
    private fun extractMerchantFromFlatText(body: String, issuerKeywords: List<String>): String? {
        // 구분자는 **NUL** 이다. 공백을 쓰면 안 된다 — 아래에서 `split(cut)` 을 하는데,
        // 공백으로 자르면 `이마트 성수` 가 두 조각으로 쪼개져 가맹점이 반토막 난다.
        // NUL 은 카드사 문구에 나올 수 없어 안전한 경계 표시가 된다.
        // 다만 리터럴 NUL 을 소스에 그대로 박으면 git 이 이 파일을 바이너리로 보아
        // diff 를 못 보여 주고, 포매터가 공백으로 바꿔 놓으면 위 증상이 조용히 살아난다.
        val cut = "\u0000"
        var text = body
        listOf(WON_AMOUNT, FOREIGN_AMOUNT, DATE_TIME, CARD_SUFFIX, MASKED_NAME, BRACKETED)
            .forEach { text = it.replace(text, cut) }

        // 카드사 이름을 지울 때 **감지된 카드사의 키워드만** 지운다.
        // 전체 카드사 키워드를 지우면 `롯데쇼핑` 이 `쇼핑` 으로, `우리사랑동물병원` 이
        // `동물메디컬` 로 잘린다 — 가맹점에 다른 카드사 이름이 들어가는 일이 흔하다.
        val words = MERCHANT_NOISE + issuerKeywords
        words.forEach { word ->
            text = text.replace(word, cut, ignoreCase = true)
        }

        return text.split(cut)
            .map { trimPunctuation(it) }
            .filter { chunk ->
                chunk.length in 2..40 &&
                    !chunk.all { it.isDigit() || it.isWhitespace() } &&
                    chunk.any { it.isLetter() }
            }
            .maxByOrNull { it.length }
    }

    /**
     * 조각 앞뒤의 문장부호를 벗긴다.
     *
     * 조각을 잘라 내고 나면 `) 마노핀익스프레스신림`, `. 버거킹 판교유스페` 처럼
     * 짝 잃은 부호가 남는다. 다만 짝이 맞는 괄호는 상호의 일부이므로 지키다:
     * `(주)이니`, `BSP대한항공(￦)` 의 괄호는 남겨야 한다.
     */
    private fun trimPunctuation(chunk: String): String {
        var t = chunk.trim()
        while (t.isNotEmpty() && t.last() in TRIM_CHARS) {
            if (t.last() == ')' && t.contains('(')) break
            t = t.dropLast(1).trimEnd()
        }
        while (t.isNotEmpty() && t.first() in TRIM_CHARS) {
            if (t.first() == '(' && t.contains(')')) break
            t = t.drop(1).trimStart()
        }
        return t
    }

    private val TRIM_CHARS = charArrayOf(
        '·', '-', ',', '/', ':', '.', ';', '|', '(', ')', '[', ']', '~', '_',
    )

    /** `홍*동` 처럼 마스킹된 이름. 가맹점이 아니다. */
    private val MASKED_NAME = Regex("""\S*\*\S*""")

    /**
     * `[Web발신]`, `(광고)` 같은 머리표.
     *
     * 괄호 안을 무조건 지우면 `(주)한빛상사` 의 `(주)` 까지 날아간다.
     * 한국 상호에 `(주)`·`(유)`·`(사)` 접두가 흔해서, 아는 머리표와 숫자만 지운다.
     */
    private val BRACKETED = Regex(
        """[\[(](?:[^\])]{0,10}발신|광고|AD|\d{1,6})[\])]""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 이 줄이 가맹점이 아니라 카드사 이름을 적은 머리글인가.
     *
     * "카드사 이름이 들어 있으면 가맹점이 아니다"로 판정하면 `롯데백화점 잠실`,
     * `하나로마트 성수`, `삼성전자서비스` 같은 멀쩡한 가맹점이 통째로 버려진다.
     * 그래서 카드사 이름을 빼고 났을 때 **남는 게 거의 없는 줄만** 머리글로 본다.
     * `신한카드(1234)` → 남는 것 없음(머리글), `롯데백화점 잠실` → `백화점잠실` 남음(가맹점).
     */
    private fun isIssuerHeaderLine(line: String, issuerKeywords: List<String>): Boolean {
        val compact = line.replace(IGNORABLE_IN_HEADER, "")
        if (compact.isEmpty()) return true
        val keywords = issuerKeywords
        return keywords.any { keyword ->
            compact.contains(keyword, ignoreCase = true) &&
                compact.replace(keyword, "", ignoreCase = true).length <= 2
        }
    }

    // 전각 공백(U+3000)은 \s 에 안 잡힌다. 카드사 문구에 실제로 섞여 온다.
    private val IGNORABLE_IN_HEADER = Regex("""[\s　()\[\]0-9*·:\-]""")

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
