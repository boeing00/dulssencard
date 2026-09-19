package com.msyim.dulssencard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 무료 · 유료 경계. 막는 것은 "카드 하나 더 등록"뿐이다(FreeTier 주석). */
class FreeTierTest {

    @Test
    fun `무료로는 두 장까지 등록한다`() {
        assertEquals(2, FreeTier.FREE_CARD_LIMIT)
        assertTrue(FreeTier.canAddCard(existingCards = 0, pro = false))
        assertTrue(FreeTier.canAddCard(existingCards = 1, pro = false))
        assertFalse(FreeTier.canAddCard(existingCards = 2, pro = false))
    }

    @Test
    fun `결제하면 제한이 없다`() {
        assertTrue(FreeTier.canAddCard(existingCards = 2, pro = true))
        assertTrue(FreeTier.canAddCard(existingCards = 30, pro = true))
    }

    @Test
    fun `복원이나 환불로 한도를 넘겨도 추가만 막는다`() {
        // 이미 있는 카드는 FreeTier 가 관여하지 않는다 — 여기서는 '더 추가'만 막히는지 본다.
        assertFalse(FreeTier.canAddCard(existingCards = 5, pro = false))
    }

    @Test
    fun `상품 ID 는 Play Console 과 같아야 한다`() {
        // 바꾸면 이미 산 사용자의 구매가 조회되지 않는다. 콘솔 상품 ID 를 바꾸지 않는 한 건드리지 말 것.
        assertEquals("pro_unlock", FreeTier.PRO_PRODUCT_ID)
    }
}
