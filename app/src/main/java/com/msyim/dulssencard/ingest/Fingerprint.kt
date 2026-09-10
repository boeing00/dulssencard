package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.domain.Cycle
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 거래 지문.
 *
 * ## 왜 PRD 정의를 그대로 쓰지 않는가
 *
 * PRD §8 은 "발신 식별자·정규화 시각·금액·상태를 일방향 해시한 값"이라고 정의했다.
 * 수집 소스가 SMS 하나였을 때는 맞는 정의였다. 지금은 같은 결제 한 건이
 * 문자로도 오고 카드사 앱 푸시로도 오고 카카오 알림톡으로도 온다.
 *
 * 발신 식별자(전화번호 vs 패키지명)를 지문에 넣으면 같은 결제의 지문이 갈라져
 * **한 번 쓴 돈이 두 번 집계된다.** 이 앱이 낼 수 있는 가장 나쁜 오류다.
 *
 * ## 무엇을 넣고 무엇을 뺐나
 *
 * 넣는 것 — 카드사 · 분 단위 거래 시각 · 금액 · 승인/취소.
 * 이 넷은 같은 승인 건이면 어느 경로로 오든 똑같다. 카드사 승인 원장에서 나온 값이기 때문이다.
 *
 * 뺀 것 — 발신 식별자, 카드 뒷4자리, 가맹점명.
 * 문자에는 `(1234)`가 있는데 푸시에는 없고, 가맹점도 `이마트 성수` / `이마트성수점` 처럼
 * 경로마다 표기가 다르다. 지문에 넣으면 같은 결제가 다른 지문을 갖게 된다.
 *
 * ## 남는 위험과 처리
 *
 * 같은 카드사에서 같은 분에 같은 금액을 두 번 긁으면 지문이 겹친다.
 * 조용히 버리면 결제를 잃으므로, [Ingestor] 가 가맹점 표기와 수신 시각을 함께 보고
 * 판단이 서지 않으면 '중복 의심'으로 남겨 사용자에게 넘긴다.
 */
object Fingerprint {

    private val MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(Cycle.ZONE)

    fun of(
        issuerKey: String?,
        occurredAt: Long?,
        amount: Long,
        direction: TxDirection,
    ): String {
        val stamp = occurredAt?.let { MINUTE.format(Instant.ofEpochMilli(it)) } ?: "unknown"
        val payload = listOf(
            issuerKey ?: "unknown",
            stamp,
            amount.toString(),
            direction.name,
        ).joinToString("|")
        return sha256(payload)
    }

    /** 지문이 겹쳤지만 별개 거래로 남겨야 할 때. 뒤에 구분자를 붙여 다시 해시한다. */
    fun disambiguate(fingerprint: String, discriminator: Long): String =
        sha256("$fingerprint#$discriminator")

    /**
     * 두 가맹점 표기가 서로 다른 가게를 가리키는가.
     *
     * 경로마다 표기가 조금씩 다른 것(`이마트 성수` / `이마트성수점`)은 같은 가게로 본다.
     * 한쪽이 비어 있어도 판단하지 않는다 — 푸시에 가맹점이 없는 경우가 흔하다.
     * 둘 다 있고 서로 접두사도 아니면 다른 가게다.
     */
    fun merchantsConflict(a: String?, b: String?): Boolean {
        val left = normalizeMerchant(a)
        val right = normalizeMerchant(b)
        if (left.isEmpty() || right.isEmpty()) return false
        return !left.startsWith(right) && !right.startsWith(left)
    }

    private fun normalizeMerchant(merchant: String?): String =
        merchant.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
