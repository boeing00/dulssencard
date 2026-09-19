package com.msyim.dulssencard.domain

/**
 * 무료 · 유료 경계. **한 번 결제(Play 인앱 상품 [PRO_PRODUCT_ID])로 카드 등록 제한만 푼다.**
 *
 * 원칙: **이미 가진 데이터는 절대 잠그지 않는다.** 막는 것은 "카드를 새로 하나 더 등록하기" 하나뿐이다.
 * - 수집 · 집계 · 결과 처리 · 백업 · 복원은 전부 무료다.
 * - 백업을 복원하거나 환불해서 카드가 한도보다 많아져도 이미 있는 카드는 그대로 동작한다.
 *   사용자가 모아 둔 숫자를 인질로 잡으면 이 앱을 믿고 알림 접근을 맡길 이유가 없어진다.
 */
object FreeTier {

    /** 무료로 등록할 수 있는 카드 수. 2026-09-19 사용자 결정. */
    const val FREE_CARD_LIMIT = 2

    /**
     * Play Console 에 만드는 인앱 상품(일회성, 소모되지 않음) ID. **콘솔의 상품 ID 와 글자 하나까지 같아야 한다.**
     * 가격은 여기 두지 않는다 — 콘솔 값(2,900원)을 상품 정보에서 받아 그대로 보여 준다.
     */
    const val PRO_PRODUCT_ID = "pro_unlock"

    /** 카드를 하나 더 등록할 수 있는가. 편집(기존 카드 저장)은 여기에 묻지 않는다. */
    fun canAddCard(existingCards: Int, pro: Boolean): Boolean = pro || existingCards < FREE_CARD_LIMIT
}
