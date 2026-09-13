package com.msyim.dulssencard.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale

/**
 * 외화 금액은 **최소 통화 단위 정수**로 다룬다. `USD 42.50` → `4250`(센트), `JPY 3,000` → `3000`.
 *
 * Double 로 두면 `0.1 + 0.2 = 0.30000000000000004` 같은 오차가 통화별 누적 합계에 쌓인다.
 * 금액 표시는 소수 둘째 자리에서 끊겨 보여도, 합계를 비교·검증할 때 조용히 틀린다.
 *
 * 통화별 소수 자릿수는 손으로 표를 만들지 않고 JDK 의 ISO 4217 데이터([Currency])를 쓴다.
 * 해석할 수 없는 코드는 2자리로 본다 — 대부분의 통화가 2자리다.
 */
object ForeignMoney {

    fun fractionDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency.trim().uppercase()).defaultFractionDigits }
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?: 2

    /**
     * 통지 문구의 숫자 → 최소 단위. `"1,234.5"`, USD → `123450`.
     *
     * 통화 자릿수보다 소수가 길면 반올림한다(HALF_UP). 0 이하·해석 불가·Long 범위 초과는 null.
     * **Double 을 거치지 않는다** — 문자열에서 바로 BigDecimal 로 읽어야 오차가 생기지 않는다.
     */
    fun parseMinor(raw: String, currency: String): Long? {
        val cleaned = raw.replace(",", "").replace(" ", "").trimEnd('.')
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0) return null
        return toMinor(value, currency)
    }

    /**
     * 옛 스키마(v4 이하)에 REAL 로 저장된 값 → 최소 단위. 마이그레이션 전용.
     *
     * `BigDecimal.valueOf(double)` 은 `Double.toString` 표기를 거쳐 `42.5` 를 `42.5` 로 읽는다.
     * `BigDecimal(double)` 생성자를 쓰면 `42.4999999…` 가 되어 반올림 경계에서 1센트가 틀어진다.
     */
    fun fromLegacyDouble(value: Double, currency: String): Long? {
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return null
        return toMinor(BigDecimal.valueOf(value), currency)
    }

    /** `4250`, USD → `"42.50"` / `3000`, JPY → `"3,000"`. */
    fun format(minor: Long, currency: String): String {
        val digits = fractionDigits(currency)
        val pattern = if (digits == 0) "#,##0" else "#,##0." + "0".repeat(digits)
        val format = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
        return format.format(BigDecimal.valueOf(minor, digits))
    }

    private fun toMinor(value: BigDecimal, currency: String): Long? {
        val digits = fractionDigits(currency)
        return runCatching {
            value.setScale(digits, RoundingMode.HALF_UP).movePointRight(digits).longValueExact()
        }.getOrNull()?.takeIf { it > 0L }
    }
}
