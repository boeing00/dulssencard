package com.msyim.dulssencard.backup

import com.msyim.dulssencard.backup.BackupCrypto.BackupException
import com.msyim.dulssencard.backup.BackupCrypto.Failure
import com.msyim.dulssencard.backup.BackupCrypto.KdfParams
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.text.Normalizer
import javax.crypto.KeyGenerator

/**
 * 백업 암호 형식 테스트. 순수 JVM 에서 돈다.
 *
 * 속도를 위해 대부분 작은 Argon2 파라미터를 쓰고, 운영 파라미터([KdfParams.DEFAULT])는 한 번만 돌린다.
 */
class BackupCryptoTest {

    private val fast = KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val plaintext = """{"cards":[{"nickname":"신한 딥드림"}],"txns":[]}""".toByteArray()

    private fun pw(s: String) = s.toCharArray()

    private fun expectFailure(expected: Failure, block: () -> Unit) {
        try {
            block()
            fail("$expected 로 실패했어야 한다")
        } catch (e: BackupException) {
            assertEquals(expected, e.failure)
        }
    }

    // ------------------------------------------------------- 왕복

    @Test
    fun `비밀번호로 암호화한 것을 같은 비밀번호로 연다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("correct horse"), fast)
        assertArrayEquals(plaintext, BackupCrypto.decrypt(file, BackupCrypto.Key.Password(pw("correct horse"))))
    }

    @Test
    fun `운영 파라미터로도 왕복한다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("운영 비밀번호 1234"))
        assertArrayEquals(plaintext, BackupCrypto.decrypt(file, BackupCrypto.Key.Password(pw("운영 비밀번호 1234"))))
    }

    @Test
    fun `기기 키로 암호화한 것을 같은 키로 연다`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val file = BackupCrypto.encryptWithDeviceKey(plaintext, key)
        assertArrayEquals(plaintext, BackupCrypto.decrypt(file, BackupCrypto.Key.Device(key)))
        assertEquals(false, BackupCrypto.requiresPassword(file))
    }

    @Test
    fun `평문이 파일에 그대로 드러나지 않는다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val asText = String(file, Charsets.ISO_8859_1)
        assertFalse(asText.contains("cards"))
        assertFalse(String(file, Charsets.UTF_8).contains("신한"))
    }

    @Test
    fun `같은 내용도 매번 다른 파일이 된다`() {
        // 솔트·IV 가 무작위여야 한다. 같으면 두 백업을 비교해 내용 변화를 추측할 수 있다.
        val a = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val b = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        assertFalse(a.contentEquals(b))
    }

    // ------------------------------------------------------- 틀린 열쇠

    @Test
    fun `틀린 비밀번호는 복호화하지 못한다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("correct horse"), fast)
        expectFailure(Failure.WrongPasswordOrCorrupted) {
            BackupCrypto.decrypt(file, BackupCrypto.Key.Password(pw("correct hors")))
        }
    }

    @Test
    fun `비밀번호 파일에 기기 키를 넣으면 종류가 다르다고 알린다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        expectFailure(Failure.WrongKeyKind) { BackupCrypto.decrypt(file, BackupCrypto.Key.Device(key)) }
        assertEquals(true, BackupCrypto.requiresPassword(file))
    }

    @Test
    fun `한글 비밀번호는 조합형과 분해형이 같은 열쇠다`() {
        // 입력기에 따라 '덜쎈' 이 조합형(NFC)이나 분해형(NFD)으로 들어온다. 정규화 안 하면 다른 기기에서 안 열린다.
        val composed = Normalizer.normalize("덜쎈카드 비밀", Normalizer.Form.NFC)
        val decomposed = Normalizer.normalize("덜쎈카드 비밀", Normalizer.Form.NFD)
        assertFalse("테스트 전제: 두 표기는 문자 단위로 다르다", composed == decomposed)
        val file = BackupCrypto.encryptWithPassword(plaintext, composed.toCharArray(), fast)
        assertArrayEquals(plaintext, BackupCrypto.decrypt(file, BackupCrypto.Key.Password(decomposed.toCharArray())))
    }

    // ------------------------------------------------------- 무결성

    @Test
    fun `암호문 한 비트만 바꿔도 연다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val tampered = file.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 0x01).toByte() }
        expectFailure(Failure.WrongPasswordOrCorrupted) {
            BackupCrypto.decrypt(tampered, BackupCrypto.Key.Password(pw("password1")))
        }
    }

    @Test
    fun `헤더의 반복 횟수를 바꿔치기하면 무결성 검사에 걸린다`() {
        // 헤더는 AAD 로 인증된다. 파라미터를 몰래 바꾸면 복호화가 실패해야 한다.
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val tampered = file.copyOf()
        ByteBuffer.wrap(tampered).putInt(10, 2) // iterations 1 → 2 (여전히 '안전한' 범위)
        expectFailure(Failure.WrongPasswordOrCorrupted) {
            BackupCrypto.decrypt(tampered, BackupCrypto.Key.Password(pw("password1")))
        }
    }

    @Test
    fun `솔트를 바꿔치기하면 무결성 검사에 걸린다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val tampered = file.copyOf().also { it[16] = (it[16].toInt() xor 0xFF).toByte() }
        expectFailure(Failure.WrongPasswordOrCorrupted) {
            BackupCrypto.decrypt(tampered, BackupCrypto.Key.Password(pw("password1")))
        }
    }

    @Test
    fun `파일 끝이 잘리면 연다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        listOf(file.size - 1, file.size - 17, 40, 20).forEach { length ->
            try {
                BackupCrypto.decrypt(file.copyOf(length), BackupCrypto.Key.Password(pw("password1")))
                fail("${length}바이트로 잘린 파일이 열렸다")
            } catch (e: BackupException) {
                assertTrue(
                    "${length}바이트: ${e.failure}",
                    e.failure == Failure.WrongPasswordOrCorrupted || e.failure == Failure.NotABackup,
                )
            }
        }
    }

    // ------------------------------------------------------- 조작된 파일

    @Test
    fun `헤더가 터무니없는 메모리를 요구하면 키 유도 전에 거절한다`() {
        // 4GB 를 적은 파일 하나로 앱을 죽이지 못하게. 이 테스트가 빨리 끝나는 것 자체가 증거다.
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val tampered = file.copyOf()
        ByteBuffer.wrap(tampered).putInt(6, Int.MAX_VALUE)
        val started = System.nanoTime()
        expectFailure(Failure.UnsafeParameters) {
            BackupCrypto.decrypt(tampered, BackupCrypto.Key.Password(pw("password1")))
        }
        assertTrue("키 유도를 시도한 것 같다", (System.nanoTime() - started) < 2_000_000_000L)
    }

    @Test
    fun `반복 횟수 상한을 넘으면 거절한다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val tampered = file.copyOf().also { ByteBuffer.wrap(it).putInt(10, 1_000_000) }
        expectFailure(Failure.UnsafeParameters) {
            BackupCrypto.decrypt(tampered, BackupCrypto.Key.Password(pw("password1")))
        }
    }

    @Test
    fun `백업 파일이 아니면 알아본다`() {
        expectFailure(Failure.NotABackup) {
            BackupCrypto.decrypt("""{"version":1,"cards":[]}""".toByteArray(), BackupCrypto.Key.Password(pw("x")))
        }
        expectFailure(Failure.NotABackup) { BackupCrypto.decrypt(ByteArray(3), BackupCrypto.Key.Password(pw("x"))) }
        assertEquals(null, BackupCrypto.requiresPassword("hello".toByteArray()))
    }

    @Test
    fun `더 새 형식 버전은 열지 않는다`() {
        val file = BackupCrypto.encryptWithPassword(plaintext, pw("password1"), fast)
        val future = file.copyOf().also { it[4] = 2 }
        expectFailure(Failure.UnsupportedFormat) {
            BackupCrypto.decrypt(future, BackupCrypto.Key.Password(pw("password1")))
        }
    }

    @Test
    fun `너무 큰 파일은 열지 않는다`() {
        expectFailure(Failure.TooLarge) {
            BackupCrypto.decrypt(ByteArray(BackupCrypto.MAX_FILE_BYTES + 1), BackupCrypto.Key.Password(pw("x")))
        }
    }

    // ------------------------------------------------------- Argon2id 배선

    @Test
    fun `BouncyCastle Argon2id 가 RFC 9106 테스트 벡터와 일치한다`() {
        // RFC 9106 §5.3. 우리가 ARGON2_id · 버전 0x13 을 제대로 고르고 있는지 확인한다.
        // 틀리게 배선되면 앱끼리는 왕복이 되지만(같은 실수를 반복하므로) 표준 도구로는 검증할 수 없다.
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(32)
            .withIterations(3)
            .withParallelism(4)
            .withSalt(ByteArray(16) { 0x02 })
            .withSecret(ByteArray(8) { 0x03 })
            .withAdditional(ByteArray(12) { 0x04 })
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(ByteArray(32) { 0x01 }, out)
        val expected = "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"
        assertEquals(expected, out.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `키 유도는 결정적이고 솔트가 바뀌면 달라진다`() {
        val salt = ByteArray(16) { it.toByte() }
        val a = BackupCrypto.deriveKey(pw("password1"), salt, fast)
        val b = BackupCrypto.deriveKey(pw("password1"), salt, fast)
        val c = BackupCrypto.deriveKey(pw("password1"), ByteArray(16) { (it + 1).toByte() }, fast)
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(32, a.size)
    }
}
