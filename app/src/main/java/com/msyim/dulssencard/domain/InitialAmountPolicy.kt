package com.msyim.dulssencard.domain

import java.time.Instant

/**
 * 초기 사용액을 저장할 때 **기준 시각을 어떻게 정할지**의 규칙.
 *
 * 화면 코드에서 떼어 낸 이유는 하나다 — 여기가 틀리면 사용자가 입력한 금액이 조용히 0으로
 * 읽히는데, 숫자가 그럴듯해서 아무도 못 알아챈다. 기기 없이 테스트로 잠근다.
 *
 * ## 규칙 넷
 *
 * 1. 초기값은 **입력한 그 주기에만** 유효하다([Aggregator]가 그렇게 읽는다).
 *    그래서 기존 기준 시각이 이번 주기 밖이면 금액이 지난번과 같더라도 **지금으로 다시 찍는다.**
 *    이걸 "금액이 바뀌었는가"로만 판단하면, 10월에 9월과 같은 30만원을 옮겨 적었을 때
 *    기준 시각이 9월에 머물러 **입력값이 통째로 무시된다.** 통지를 놓쳐 총계가 어긋났을 때
 *    카드앱 숫자를 다시 넣는 것이 가장 확실한 복구 경로인데, 그 길이 막히는 것이다.
 * 2. 같은 주기 안에서 금액을 고치면 기준 시각도 지금으로 옮긴다.
 *    그 사이 들어온 통지는 이미 새 금액에 포함돼 있기 때문이다.
 * 3. 같은 주기, 같은 금액이면 기준 시각을 **유지한다.** 저장만 눌렀다고 해서
 *    그 사이 도착한 거래가 합계에서 사라지면 안 된다.
 * 4. 입력칸이 비어 있는데 기존 기준이 **지난 주기**면 그 기록을 지우지 않는다.
 *    화면은 지난 주기 값을 비워서 보여 준다(이번 주기에 유효하지 않으니까). 그 상태에서
 *    별명만 고치고 저장한 것을 "초기값을 지우겠다"로 읽으면 입력 기록이 날아간다.
 */
object InitialAmountPolicy {

    /** 저장할 (금액, 기준 시각) 짝. */
    data class Stamped(val amount: Long, val at: Long)

    /**
     * @param input 입력칸의 값. **비어 있으면 null** 이다 — 0 을 명시적으로 넣은 것과 구분한다.
     * @param now   저장하는 지금. 이 시각 이전 거래는 이미 [input] 에 들어 있다.
     */
    fun resolve(
        existingAmount: Long,
        existingAt: Long,
        input: Long?,
        cycleStartDay: Int,
        now: Long,
    ): Stamped {
        val window = Cycle.windowFor(cycleStartDay, Instant.ofEpochMilli(now))
        val existingIsCurrent = existingAmount != 0L && window.contains(existingAt)

        return when {
            // 규칙 4 — 지난 주기 기록은 건드리지 않는다.
            input == null -> if (existingIsCurrent) Stamped(0L, 0L) else Stamped(existingAmount, existingAt)
            // 0 을 직접 넣은 것은 "초기값을 쓰지 않겠다"는 뜻이다.
            input <= 0L -> Stamped(0L, 0L)
            // 규칙 1·2
            !existingIsCurrent || input != existingAmount -> Stamped(input, now)
            // 규칙 3
            else -> Stamped(input, existingAt)
        }
    }
}
