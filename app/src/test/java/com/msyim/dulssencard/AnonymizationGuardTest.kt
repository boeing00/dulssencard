package com.msyim.dulssencard

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * **공개 저장소에 실제 개인정보가 들어가는 것을 막는다.**
 *
 * 2026-09-12, 실기기에서 채집한 알림을 회귀 테스트에 넣으면서 실명·가족카드 뒷자리·가린 이름·
 * 실제 가맹점·누적액이 공개 저장소에 올라갔다. 공개 전에 한 번 걸러 냈지만 사람이 목록을 만들어
 * 찾다 보니 빠진 것이 있었다. 규칙을 문서로만 두면 또 빠진다. 그래서 테스트가 강제한다.
 *
 * 검사하는 것(허용 목록 밖이면 실패):
 *  - 가린 이름 `○*○`
 *  - 카드 뒷자리 — `(NNNN)`, `본인 NNNN`, `삼성가족NNNN승인` 처럼 카드 번호로 쓰인 네 자리
 *  - 휴대전화 번호, 주민등록번호 모양
 *
 * **이 검사가 못 잡는 것**: 가리지 않은 실명, 실제 가맹점 이름. 모양으로는 구분할 수 없다.
 * 코퍼스를 더할 때 `src/test/resources/corpus/README.md` 의 표대로 **눈으로** 바꿔야 한다.
 */
class AnonymizationGuardTest {

    /** 테스트에서 쓰는 가린 이름. 홍*동 은 한국의 'John Doe', 나머지는 공개 오픈소스 코퍼스에서 온 값이다. */
    private val allowedMaskedNames = setOf(
        "홍*동",
        // kakao/credit-card-sms-parser(공개, deprecated) 테스트 코퍼스 — RealCorpusTest
        "재*님", "오*름", "김*호", "솔*님", "우*님", "정*욱", "김*정", "강*혜",
    )

    /** 테스트에서 쓰는 가짜 카드 뒷자리. */
    private val allowedCardSuffixes = setOf("0000", "1234", "4321", "8821", "2468", "5678")

    private val maskedName = Regex("""[가-힣]\*[가-힣]""")
    private val cardSuffix = Regex("""[(\[](\d{4})[)\]]|본인\s*(\d{4})|[가-힣A-Za-z](\d{4})\s*(?:승인|취소)""")
    private val mobilePhone = Regex("""(?<!\d)01[016789]-?\d{3,4}-?\d{4}(?!\d)""")
    private val residentNumber = Regex("""(?<!\d)\d{6}-[1-4]\d{6}(?!\d)""")

    /** 검사 대상. Gradle 단위 테스트의 작업 디렉터리는 모듈 루트(app/)다. */
    private fun targets(): List<File> {
        val roots = listOf(File("src/test"), File("src/main/java"))
        val docs = listOf(File("../README.md"), File("../CLAUDE.md")).filter { it.exists() }
        return roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "txt", "md", "json") }.toList()
        } + docs
    }

    @Test
    fun `저장소에 허용 목록 밖의 개인정보 모양이 없다`() {
        val files = targets()
        assertTrue("검사할 파일을 찾지 못했다 — 작업 디렉터리가 바뀌었나?", files.size > 20)

        val problems = mutableListOf<String>()
        files.forEach { file ->
            if (file.name == "AnonymizationGuardTest.kt") return@forEach
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                val where = "${file.path}:${index + 1}"
                maskedName.findAll(line).map { it.value }.filter { it !in allowedMaskedNames }.forEach {
                    problems += "$where 가린 이름 '$it' — 홍*동 으로 바꾸세요"
                }
                cardSuffix.findAll(line)
                    .mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
                    .filter { it !in allowedCardSuffixes }
                    .forEach { problems += "$where 카드 뒷자리 '$it' — $allowedCardSuffixes 중 하나로 바꾸세요" }
                mobilePhone.find(line)?.let { problems += "$where 전화번호 모양 '${it.value}'" }
                residentNumber.find(line)?.let { problems += "$where 주민등록번호 모양" }
            }
        }
        if (problems.isNotEmpty()) {
            fail("공개 저장소에 들어가면 안 되는 값 ${problems.size}건\n" + problems.joinToString("\n"))
        }
    }
}
