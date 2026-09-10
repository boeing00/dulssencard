package com.msyim.dulssencard.data.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 내보내기 파일 암호화.
 *
 * ## 왜 [DbPassphrase] 를 그대로 쓰지 않는가
 *
 * DB 암호는 Android Keystore 안의 키로 봉인돼 있어 **이 기기 이 앱 밖으로 나갈 수 없다.**
 * 그래서 백업 파일에는 못 쓴다 — 기기를 바꾸거나 앱을 지우는 순간 파일이 벽돌이 된다.
 * 백업은 사용자가 기억하는 비밀번호에서 키를 유도해야 다른 기기에서도 열린다.
 *
 * ## 형식
 *
 * ```
 * "DSCBK1" | salt(16) | iv(12) | AES-256-GCM 암호문+태그
 * ```
 *
 * 키는 PBKDF2-HMAC-SHA256 으로 [ITERATIONS] 번 늘려 뽑는다. 머리표를 AAD 로 묶어서
 * 헤더만 바꿔치기한 파일도 복호화 단계에서 걸린다.
 *
 * 비밀번호를 잊으면 **복구할 방법이 없다.** 이 앱은 네트워크를 쓰지 않으므로
 * 어디에도 사본이 없다. 화면에서 그렇게 고지한다.
 */
object BackupCrypto {

    /** 파일 머리표 겸 형식 버전. 형식이 바뀌면 끝의 숫자를 올린다. */
    private val MAGIC = "DSCBK1".toByteArray(Charsets.US_ASCII)

    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** OWASP 2023 권고치. 기기에서 1초 남짓 걸리는데, 수동 내보내기라 그만한 값은 한다. */
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private const val HEADER_BYTES = 6 + SALT_BYTES + IV_BYTES

    /** 너무 짧은 비밀번호는 PBKDF2 로도 못 구한다. */
    const val MIN_PASSWORD_LENGTH = 8

    /**
     * 비밀번호가 다르거나 파일이 손상됐다. 둘을 구분하지 않는 이유는 구분할 수 없어서다 —
     * GCM 은 어느 쪽이든 태그 검증에서 똑같이 실패한다.
     */
    class WrongPasswordException : Exception("비밀번호가 다르거나 파일이 손상되었습니다")

    fun seal(plain: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(password, salt),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    /** @throws WrongPasswordException 비밀번호가 다르거나 파일이 우리 형식이 아닐 때. */
    fun open(sealed: ByteArray, password: CharArray): ByteArray {
        if (sealed.size <= HEADER_BYTES) throw WrongPasswordException()
        if (!sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw WrongPasswordException()

        val salt = sealed.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = sealed.copyOfRange(MAGIC.size + SALT_BYTES, HEADER_BYTES)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(password, salt),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(MAGIC)
            cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordException()
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
        } finally {
            // 비밀번호 사본을 메모리에 오래 두지 않는다.
            spec.clearPassword()
        }
    }
}
