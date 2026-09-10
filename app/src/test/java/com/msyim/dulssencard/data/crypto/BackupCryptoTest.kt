package com.msyim.dulssencard.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 백업 파일 암호화 회귀 테스트.
 *
 * 예전에는 "암호화 내보내기"라는 이름으로 **평문 JSON** 을 외부 저장소에 썼다.
 * SQLCipher 와 Keystore 로 쌓아 올린 방어가 그 한 줄로 통째로 우회됐고,
 * 사용자에게는 암호화한다고 말하고 있었다. 그래서 이 파일이 있다.
 */
class BackupCryptoTest {

    private val plain = """{"cards":[{"nickname":"우리카드"}],"txns":[]}""".toByteArray(Charsets.UTF_8)
    private val password = "correct horse battery".toCharArray()

    @Test
    fun `봉인한 뒤 같은 비밀번호로 열면 원본이 그대로 나온다`() {
        val sealed = BackupCrypto.seal(plain, password)

        assertArrayEquals(plain, BackupCrypto.open(sealed, password))
    }

    @Test
    fun `봉인한 파일에 평문 조각이 남지 않는다`() {
        val sealed = BackupCrypto.seal(plain, password)
        val asText = String(sealed, Charsets.ISO_8859_1)

        assertEquals(false, asText.contains("우리카드"))
        assertEquals(false, asText.contains("cards"))
    }

    @Test
    fun `비밀번호가 다르면 열리지 않는다`() {
        val sealed = BackupCrypto.seal(plain, password)

        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.open(sealed, "wrong password".toCharArray())
        }
    }

    @Test
    fun `한 바이트만 바뀌어도 열리지 않는다`() {
        // GCM 태그가 위변조를 잡는다. 백업 파일이 조용히 망가진 채 복원되면 안 된다.
        val sealed = BackupCrypto.seal(plain, password)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()

        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.open(sealed, password)
        }
    }

    @Test
    fun `머리표를 바꾸면 열리지 않는다`() {
        val sealed = BackupCrypto.seal(plain, password)
        sealed[0] = 'X'.code.toByte()

        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.open(sealed, password)
        }
    }

    @Test
    fun `우리 형식이 아닌 파일은 열지 않는다`() {
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.open("그냥 텍스트 파일입니다".toByteArray(Charsets.UTF_8), password)
        }
    }

    @Test
    fun `빈 파일도 예외로 처리한다`() {
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.open(ByteArray(0), password)
        }
    }

    @Test
    fun `같은 내용을 두 번 봉인해도 결과가 다르다`() {
        // salt 와 iv 를 매번 새로 뽑는지 확인한다. 같으면 두 백업을 비교해 변화를 읽을 수 있다.
        val a = BackupCrypto.seal(plain, password)
        val b = BackupCrypto.seal(plain, password)

        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(plain, BackupCrypto.open(a, password))
        assertArrayEquals(plain, BackupCrypto.open(b, password))
    }

    @Test
    fun `큰 백업도 왕복한다`() {
        val big = ByteArray(512 * 1024) { (it % 251).toByte() }

        assertArrayEquals(big, BackupCrypto.open(BackupCrypto.seal(big, password), password))
    }

    @Test
    fun `비밀번호 최소 길이가 정해져 있다`() {
        assertTrue(BackupCrypto.MIN_PASSWORD_LENGTH >= 8)
    }
}
