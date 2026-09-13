package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.domain.Cycle
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * 결제 알림 회귀 코퍼스. `src/test/resources/corpus/notifications/` 의 **파일 하나가 알림 한 건**이다.
 *
 * 새 카드사 문구를 만나면 코드가 아니라 **파일을 하나 더한다**. 형식과 익명화 규칙은
 * `src/test/resources/corpus/README.md` 에 있다.
 *
 * 모든 파일을 돈 뒤 실패를 한꺼번에 보여 준다 — 파일 하나에서 멈추면 형식 변경이 몇 건에
 * 영향을 줬는지 알 수 없다.
 */
class CorpusTest {

    private val directory = File("src/test/resources/corpus/notifications")

    private data class Sample(val file: String, val header: Map<String, String>, val body: String)

    private fun load(): List<Sample> {
        assertTrue("코퍼스 폴더가 없다: ${directory.absolutePath}", directory.isDirectory)
        return directory.listFiles { f -> f.extension == "txt" }.orEmpty().sortedBy { it.name }.map { file ->
            val text = file.readText(Charsets.UTF_8).replace("\r\n", "\n")
            val split = text.indexOf("\n---\n")
            require(split > 0) { "${file.name}: 머리와 본문을 가르는 '---' 줄이 없다" }
            val header = text.substring(0, split).lines()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val colon = line.indexOf(':')
                    require(colon > 0) { "${file.name}: 머리 줄 형식이 틀렸다 — $line" }
                    line.substring(0, colon).trim() to line.substring(colon + 1).trim()
                }
            Sample(file.name, header, text.substring(split + 5).trimEnd('\n'))
        }
    }

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(Cycle.ZONE).toInstant().toEpochMilli()

    @Test
    fun `코퍼스의 모든 알림이 기대대로 읽힌다`() {
        val samples = load()
        assertTrue("코퍼스가 비어 있다", samples.isNotEmpty())
        val failures = mutableListOf<String>()

        samples.forEach { sample ->
            val h = sample.header
            fun problem(message: String) {
                failures += "${sample.file}: $message"
            }

            val raw = RawMessage(
                source = TxSource.valueOf(h.getValue("source")),
                senderKey = h.getValue("sender"),
                title = h["title"],
                body = sample.body,
                receivedAt = epoch(h.getValue("receivedAt")),
            )
            val parsed = PaymentParser.parse(raw)

            if (h["expect.reject"] == "true") {
                if (parsed != null) problem("결제가 아닌데 읽혔다 (금액 ${parsed.amount})")
                return@forEach
            }
            if (parsed == null) {
                problem("결제로 읽히지 않았다")
                return@forEach
            }
            h["expect.issuer"]?.let { if (parsed.issuerKey != it) problem("카드사 ${parsed.issuerKey} ≠ $it") }
            h["expect.direction"]?.let { if (parsed.direction.name != it) problem("방향 ${parsed.direction} ≠ $it") }
            h["expect.amount"]?.let { if (parsed.amount != it.toLong()) problem("금액 ${parsed.amount} ≠ $it") }
            h["expect.occurredAt"]?.let { if (parsed.occurredAt != epoch(it)) problem("시각이 다르다") }
            h["expect.merchant"]?.let { if (parsed.merchant != it) problem("가맹점 '${parsed.merchant}' ≠ '$it'") }
            h["expect.cardSuffix"]?.let { if (parsed.cardSuffix != it) problem("카드 뒷자리 ${parsed.cardSuffix} ≠ $it") }
        }

        if (failures.isNotEmpty()) {
            fail("코퍼스 ${samples.size}건 중 ${failures.size}건 실패\n" + failures.joinToString("\n"))
        }
    }
}
