package com.msyim.dulssencard.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 덜쎈카드 백업 파일 형식 `DSCB` v1. **내보내기와 복원 전 자동 백업이 같은 형식을 쓴다.**
 *
 * ```
 * 오프셋  크기  내용
 * 0       4     매직 "DSCB"
 * 4       1     형식 버전 = 1
 * 5       1     키 종류   1 = 비밀번호(Argon2id)   2 = 기기 키(Android Keystore)
 * ── 키 종류 1 일 때만 ──
 * 6       4     Argon2 메모리(KiB, big-endian)
 * 10      4     Argon2 반복 횟수
 * 14      1     Argon2 병렬도
 * 15      1     솔트 길이(16)
 * 16      16    솔트
 * ── 공통 ──
 * …       1     IV 길이(12)
 * …       12    IV
 * …       나머지 AES-256-GCM 암호문 + 128비트 인증 태그
 * ```
 *
 * ## 무결성
 *
 * **헤더 전체(암호문 앞의 모든 바이트)를 GCM 의 AAD 로 인증한다.** 그래서
 *  - 암호문을 한 비트만 바꿔도,
 *  - 헤더의 KDF 파라미터를 약하게 바꿔치기해도,
 *  - 파일 끝을 잘라내도
 * 복호화가 [Failure.WrongPasswordOrCorrupted] 로 실패한다. 틀린 비밀번호와 손상은 구분할 수 없다 —
 * GCM 의 성질이고, 구분할 수 있다면 그 자체가 공격자에게 주는 정보다.
 *
 * ## 조작된 파일 방어
 *
 * 헤더의 Argon2 메모리·반복 횟수는 파일이 정한다. 상한이 없으면 `메모리 = 4GB` 로 적은 파일 하나로
 * 앱을 죽일 수 있다. 그래서 [KdfParams.isSafe] 로 **복호화 전에** 막는다. 파일 크기도 제한한다.
 *
 * ## 평문
 *
 * 평문은 호출자가 넘기는 바이트 배열이다(JSON). **디스크에 평문을 쓰지 않는다** — 메모리에서 암호화해
 * 암호문만 파일로 나간다. 파생 키와 비밀번호 바이트는 쓰고 나서 0 으로 덮는다.
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte())
    const val FORMAT_VERSION: Int = 1

    private const val KIND_PASSWORD: Byte = 1
    private const val KIND_DEVICE: Byte = 2

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    /** 이보다 큰 파일은 열지 않는다. 몇 년치 거래도 수 MB 이므로 넉넉하다. */
    const val MAX_FILE_BYTES: Int = 64 * 1024 * 1024

    /** 비밀번호 최소 길이. Argon2id 로 늘려도 짧은 비밀번호는 사전 공격에 버티지 못한다. */
    const val MIN_PASSWORD_LENGTH: Int = 8

    /**
     * Argon2id 파라미터.
     *
     * 기본값은 메모리 64MiB · 반복 3 · 병렬 1 이다. OWASP 권고 최소치(19MiB·2회)보다 강하게 잡았다 —
     * 백업은 드물게 하는 일이라 몇 초 기다리는 대가로 오프라인 대입 공격 비용을 크게 올린다.
     * 파일에 파라미터를 적으므로 나중에 올려도 옛 백업은 그대로 열린다.
     */
    data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
        /** 파일이 정한 값이 앱을 멈추게 할 만큼 크지 않은가. */
        fun isSafe(): Boolean =
            memoryKiB in 8 * parallelism..MAX_MEMORY_KIB &&
                iterations in 1..MAX_ITERATIONS &&
                parallelism in 1..MAX_PARALLELISM

        companion object {
            val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)
            const val MAX_MEMORY_KIB = 256 * 1024
            const val MAX_ITERATIONS = 10
            const val MAX_PARALLELISM = 4
        }
    }

    /** 복호화에 쓸 열쇠. */
    sealed interface Key {
        /** 사용자가 입력한 비밀번호. 호출자가 다 쓰고 나서 [CharArray.fill] 로 지운다. */
        class Password(val chars: CharArray) : Key

        /** 이 기기의 Keystore 키. 자동 백업 전용 — 다른 기기에서는 열리지 않는다. */
        class Device(val secretKey: SecretKey) : Key
    }

    sealed interface Failure {
        /** 덜쎈카드 백업 파일이 아니다. */
        data object NotABackup : Failure

        /** 더 새 앱이 만든 형식이다. */
        data object UnsupportedFormat : Failure

        /** 비밀번호용 파일에 기기 키를 넣었거나 그 반대. */
        data object WrongKeyKind : Failure

        /** 헤더의 KDF 파라미터가 비정상적으로 크다(조작 의심). 복호화를 시도하지 않았다. */
        data object UnsafeParameters : Failure

        /** 파일이 너무 크다. */
        data object TooLarge : Failure

        /** 비밀번호가 틀렸거나, 파일이 손상·변조됐다. 둘은 구분할 수 없다. */
        data object WrongPasswordOrCorrupted : Failure
    }

    class BackupException(val failure: Failure) : Exception(failure.toString())

    // ------------------------------------------------------------------ 암호화

    fun encryptWithPassword(
        plaintext: ByteArray,
        password: CharArray,
        params: KdfParams = KdfParams.DEFAULT,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(params.isSafe()) { "unsafe KDF params: $params" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = deriveKey(password, salt, params)
        try {
            val key = SecretKeySpec(derived, "AES")
            val headerPrefix = ByteArrayOutputStream().apply {
                write(MAGIC)
                write(FORMAT_VERSION)
                write(KIND_PASSWORD.toInt())
                write(ByteBuffer.allocate(4).putInt(params.memoryKiB).array())
                write(ByteBuffer.allocate(4).putInt(params.iterations).array())
                write(params.parallelism)
                write(SALT_BYTES)
                write(salt)
            }.toByteArray()
            return seal(key, headerPrefix, plaintext, random)
        } finally {
            derived.fill(0)
        }
    }

    fun encryptWithDeviceKey(plaintext: ByteArray, key: SecretKey): ByteArray {
        val headerPrefix = ByteArrayOutputStream().apply {
            write(MAGIC)
            write(FORMAT_VERSION)
            write(KIND_DEVICE.toInt())
        }.toByteArray()
        return seal(key, headerPrefix, plaintext, random = null)
    }

    /**
     * IV 는 **직접 만들지 않는다.** 암호 제공자가 init 때 무작위로 만든 것을 읽어 헤더에 적는다.
     * Android Keystore 는 호출자가 IV 를 주는 것을 기본으로 거부하고, 직접 만들면 재사용 실수의 여지가 생긴다.
     */
    private fun seal(key: SecretKey, headerPrefix: ByteArray, plaintext: ByteArray, random: SecureRandom?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (random != null) cipher.init(Cipher.ENCRYPT_MODE, key, random) else cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "unexpected IV" }

        val header = headerPrefix + byteArrayOf(IV_BYTES.toByte()) + iv
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    // ------------------------------------------------------------------ 복호화

    /** 파일이 요구하는 열쇠 종류. 비밀번호를 물을지 정하는 데 쓴다. 형식이 아니면 null. */
    fun requiresPassword(file: ByteArray): Boolean? = runCatching { parseHeader(file).kind == KIND_PASSWORD }.getOrNull()

    fun decrypt(file: ByteArray, key: Key): ByteArray {
        if (file.size > MAX_FILE_BYTES) throw BackupException(Failure.TooLarge)
        val header = parseHeader(file)

        val secret: SecretKey
        var derived: ByteArray? = null
        when (key) {
            is Key.Password -> {
                if (header.kind != KIND_PASSWORD) throw BackupException(Failure.WrongKeyKind)
                val params = requireNotNull(header.params)
                if (!params.isSafe()) throw BackupException(Failure.UnsafeParameters)
                derived = deriveKey(key.chars, requireNotNull(header.salt), params)
                secret = SecretKeySpec(derived, "AES")
            }
            is Key.Device -> {
                if (header.kind != KIND_DEVICE) throw BackupException(Failure.WrongKeyKind)
                secret = key.secretKey
            }
        }

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, header.iv))
            cipher.updateAAD(file, 0, header.length)
            return cipher.doFinal(file, header.length, file.size - header.length)
        } catch (e: AEADBadTagException) {
            throw BackupException(Failure.WrongPasswordOrCorrupted)
        } catch (e: javax.crypto.BadPaddingException) {
            throw BackupException(Failure.WrongPasswordOrCorrupted)
        } catch (e: IllegalArgumentException) {
            throw BackupException(Failure.WrongPasswordOrCorrupted)
        } finally {
            derived?.fill(0)
        }
    }

    private class Header(
        val kind: Byte,
        val params: KdfParams?,
        val salt: ByteArray?,
        val iv: ByteArray,
        /** 헤더 바이트 수 = 암호문 시작 위치 = AAD 길이. */
        val length: Int,
    )

    private fun parseHeader(file: ByteArray): Header {
        val buffer = ByteBuffer.wrap(file)
        fun need(bytes: Int) {
            if (buffer.remaining() < bytes) throw BackupException(Failure.NotABackup)
        }

        need(MAGIC.size + 2)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupException(Failure.NotABackup)
        val version = buffer.get().toInt() and 0xFF
        if (version != FORMAT_VERSION) throw BackupException(Failure.UnsupportedFormat)

        val kind = buffer.get()
        var params: KdfParams? = null
        var salt: ByteArray? = null
        when (kind) {
            KIND_PASSWORD -> {
                need(4 + 4 + 1 + 1)
                val memory = buffer.int
                val iterations = buffer.int
                val parallelism = buffer.get().toInt() and 0xFF
                params = KdfParams(memory, iterations, parallelism)
                val saltLength = buffer.get().toInt() and 0xFF
                if (saltLength != SALT_BYTES) throw BackupException(Failure.NotABackup)
                need(saltLength)
                salt = ByteArray(saltLength).also { buffer.get(it) }
            }
            KIND_DEVICE -> Unit
            else -> throw BackupException(Failure.UnsupportedFormat)
        }

        need(1)
        val ivLength = buffer.get().toInt() and 0xFF
        if (ivLength != IV_BYTES) throw BackupException(Failure.NotABackup)
        need(ivLength)
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        // 암호문은 적어도 인증 태그 길이만큼은 있어야 한다. 잘린 파일을 여기서 거른다.
        if (buffer.remaining() < TAG_BITS / 8) throw BackupException(Failure.WrongPasswordOrCorrupted)
        return Header(kind, params, salt, iv, buffer.position())
    }

    // ------------------------------------------------------------------ 키 유도

    /**
     * 비밀번호 → 256비트 키 (Argon2id v1.3).
     *
     * 한글 비밀번호는 입력기에 따라 조합형(NFC)·분해형(NFD)으로 다르게 들어올 수 있다.
     * 정규화하지 않으면 **같은 비밀번호로 만든 백업이 다른 기기에서 안 열린다.** 그래서 NFC 로 맞춘다.
     */
    internal fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val normalized = Normalizer.normalize(java.nio.CharBuffer.wrap(password), Normalizer.Form.NFC)
        val passwordBytes = normalized.toByteArray(Charsets.UTF_8)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(params.memoryKiB)
                    .withIterations(params.iterations)
                    .withParallelism(params.parallelism)
                    .withSalt(salt)
                    .build(),
            )
            val out = ByteArray(KEY_BYTES)
            generator.generateBytes(passwordBytes, out)
            return out
        } finally {
            passwordBytes.fill(0)
        }
    }
}
