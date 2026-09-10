package com.msyim.dulssencard.ingest

/**
 * 카드사 판정표.
 *
 * 목적은 두 가지다.
 *  1. 알림 패키지 → 카드사 매핑. 허용 목록에 없는 앱의 알림은 내용을 읽지 않는다.
 *  2. 본문 키워드 → 카드사 매핑. SMS 와 카카오 알림톡은 발신 번호/채팅방 이름이 제각각이라
 *     본문에 박힌 카드사 이름이 가장 안정적인 단서다.
 *
 * 여기서 정한 [Issuer.key] 는 거래에 저장되는 유일한 카드사 식별자다.
 * 발신 번호 원문이나 알림 본문은 저장하지 않는다.
 *
 * 패키지명은 Google Play 스토어 페이지에서 확인한 값이다. 카드사가 앱을 통합·개편하면
 * 바뀔 수 있으므로, 설정 > 알림 소스에서 사용자가 직접 패키지를 켜고 끌 수 있게 해 두었다.
 */
object IssuerRegistry {

    data class Issuer(
        val key: String,
        val displayName: String,
        /** 본문·제목에서 이 문자열이 보이면 해당 카드사로 본다. 긴 것부터 검사한다. */
        val bodyKeywords: List<String>,
        /** 이 카드사가 결제 알림을 띄우는 앱 패키지. */
        val packages: List<String>,
    )

    /** 카카오톡. 카드사가 아니라 알림톡을 실어 나르는 전달자다. */
    const val KAKAO_PACKAGE = "com.kakao.talk"

    /**
     * 문자 앱. 카드사가 아니라 결제 SMS 를 실어 나르는 전달자다.
     *
     * 이 앱은 SMS 권한을 선언하지 않는다. 결제 문자는 문자 앱이 띄운 **알림**을 통해 읽는다.
     * 기본 문자 앱은 런타임에 `Telephony.Sms.getDefaultSmsPackage()` 로 정확히 알아내고,
     * 그게 실패할 때를 위해 흔한 패키지를 여기에 둔다.
     */
    val MESSAGING_PACKAGES: List<String> = listOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.messaging",
        "com.android.mms",
    )

    fun isMessagingApp(packageName: String, defaultSmsPackage: String?): Boolean =
        packageName == defaultSmsPackage || packageName in MESSAGING_PACKAGES

    val ISSUERS: List<Issuer> = listOf(
        Issuer(
            key = "SHINHAN",
            displayName = "신한카드",
            bodyKeywords = listOf("신한카드", "신한체크", "SOL페이", "신한"),
            packages = listOf("com.shcard.smartpay"),
        ),
        Issuer(
            key = "HYUNDAI",
            displayName = "현대카드",
            bodyKeywords = listOf("현대카드", "현대"),
            packages = listOf("com.hyundaicard.appcard"),
        ),
        Issuer(
            key = "SAMSUNG",
            displayName = "삼성카드",
            bodyKeywords = listOf("삼성카드", "삼성"),
            // kr.co.samsungcard.mpocket 는 모니모(net.ib.android.smcard)로 통합됐다. 둘 다 둔다.
            packages = listOf("kr.co.samsungcard.mpocket", "net.ib.android.smcard"),
        ),
        Issuer(
            key = "KB",
            displayName = "KB국민카드",
            bodyKeywords = listOf("KB국민카드", "국민카드", "KB Pay", "KB페이", "KB국민", "KB"),
            packages = listOf("com.kbcard.cxh.appcard", "com.kbcard.kbbusinesscard"),
        ),
        Issuer(
            key = "LOTTE",
            displayName = "롯데카드",
            bodyKeywords = listOf("롯데카드", "디지로카", "롯데"),
            packages = listOf("com.lcacApp"),
        ),
        Issuer(
            key = "WOORI",
            displayName = "우리카드",
            bodyKeywords = listOf("우리카드", "우리WON", "우리"),
            packages = listOf("com.wooricard.smartapp"),
        ),
        Issuer(
            key = "HANA",
            displayName = "하나카드",
            bodyKeywords = listOf("하나카드", "하나Pay", "하나"),
            packages = listOf("com.hanaskcard.paycla"),
        ),
        Issuer(
            key = "BC",
            displayName = "BC카드",
            bodyKeywords = listOf("BC카드", "비씨카드", "페이북", "BC"),
            packages = listOf("com.bccard.mobilecard", "com.bccard.bcsmartapp", "kvp.jjy.MispAndroid320"),
        ),
        Issuer(
            key = "NH",
            displayName = "NH농협카드",
            bodyKeywords = listOf("NH농협카드", "농협카드", "NH올원", "농협"),
            // nh.smart.banking 은 사용자 기기(갤럭시 S24+)에서 실제로 확인한 값이다.
            // 나머지 둘은 NH 앱 개편 이력에 따른 후보라 함께 둔다.
            packages = listOf("nh.smart.banking", "nh.smart.nhallonepay", "nh.smart.card"),
        ),
        Issuer(
            key = "KAKAOPAY",
            displayName = "카카오페이",
            bodyKeywords = listOf("카카오페이"),
            packages = listOf("com.kakaopay.app"),
        ),
        Issuer(
            key = "TOSS",
            displayName = "토스",
            bodyKeywords = listOf("토스뱅크", "토스"),
            packages = listOf("viva.republica.toss"),
        ),
    )

    private val byPackage: Map<String, Issuer> =
        ISSUERS.flatMap { issuer -> issuer.packages.map { it to issuer } }.toMap()

    private val byKey: Map<String, Issuer> = ISSUERS.associateBy { it.key }

    /** 카드사 앱 패키지 전부 + 카카오톡. 온보딩에서 기본으로 켜자고 제안하는 목록이다. */
    val SUGGESTED_PACKAGES: List<String> = byPackage.keys.toList() + KAKAO_PACKAGE

    fun byPackage(packageName: String): Issuer? = byPackage[packageName]

    fun byKey(key: String?): Issuer? = key?.let { byKey[it] }

    fun displayName(key: String?): String? = byKey(key)?.displayName

    /**
     * 본문/제목에서 카드사를 찾는다.
     *
     * 두 단계로 고른다:
     *  1. 더 긴 키워드로 맞은 쪽 ("신한카드"가 "현대"보다 구체적이다).
     *  2. 길이가 같으면 **먼저 나온 쪽**.
     *
     * 2번이 없으면 실제 문구에서 두 가지가 깨진다:
     *  - `농협BC(4*8*)...` → 농협(2) 과 BC(2) 가 동점이라 판정을 포기해 버린다.
     *  - `KB*카드 ... 우리사랑동물병원 취소` → 가맹점 이름의 "우리" 가 카드사를 이겨
     *    우리카드 거래로 둔갑한다. 카드사 이름은 문구 맨 앞에 오고 가맹점은 뒤에 오므로,
     *    위치가 곧 신뢰도다.
     */
    fun detect(text: String): Issuer? {
        data class Hit(val issuer: Issuer, val length: Int, val position: Int)

        val hits = ISSUERS.mapNotNull { issuer ->
            val matched = issuer.bodyKeywords.filter { text.contains(it, ignoreCase = true) }
            if (matched.isEmpty()) return@mapNotNull null
            val longest = matched.maxBy { it.length }
            Hit(
                issuer = issuer,
                length = longest.length,
                position = matched.minOf { text.indexOf(it, ignoreCase = true) },
            )
        }
        if (hits.isEmpty()) return null
        val best = hits.maxOf { it.length }
        return hits.filter { it.length == best }.minByOrNull { it.position }?.issuer
    }

    /** [detect] 가 고른 카드사의 키워드만 돌려준다. 문구에서 카드사 이름을 지울 때 쓴다. */
    fun keywordsOf(issuer: Issuer?): List<String> = issuer?.bodyKeywords.orEmpty()

    /**
     * 카카오 알림톡처럼 전달자가 따로 있는 경우, 제목(발신 채널명)과 본문을 함께 보고 판정한다.
     * 제목이 더 신뢰도가 높아 먼저 본다.
     */
    fun detect(title: String?, body: String): Issuer? {
        if (!title.isNullOrBlank()) detect(title)?.let { return it }
        return detect(body)
    }
}
