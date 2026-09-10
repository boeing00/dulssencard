package com.msyim.dulssencard.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 로컬 DB 암호(SQLCipher passphrase)를 만들고 보관한다.
 *
 * 구조
 *  1. 최초 실행 때 32바이트 난수를 만든다. 이게 SQLCipher 에 넘길 실제 암호다.
 *  2. Android Keystore 안의 AES-256-GCM 키로 그 난수를 봉인한다.
 *     Keystore 키는 앱 밖으로 나올 수 없고, 이 앱만 쓸 수 있다.
 *  3. 봉인된 결과(IV + 암호문)만 SharedPreferences 에 Base64 로 남긴다.
 *
 * 따라서 디스크 어디에도 평문 암호가 없고, 기기를 초기화하거나 앱을 지우면
 * Keystore 키가 사라져 DB 도 영구히 못 연다(= PRD §10 의 "복구 불가" 고지와 일치).
 */
object DbPassphrase {

    private const val PREFS = "dulssencard_key"
    private const val PREF_SEALED = "sealed_passphrase"
    private const val KEY_ALIAS = "dulssencard_db_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PASSPHRASE_BYTES = 32

    @Volatile
    private var cached: ByteArray? = null

    /**
     * SQLCipher 에 넘길 암호를 돌려준다.
     *
     * 반환한 배열은 SQLCipher 가 내부에서 지워 버리므로(zeroize) 호출할 때마다 사본을 만든다.
     * 프로세스 안에서는 [cached] 에 한 번만 풀어 두고 재사용한다.
     */
    fun getOrCreate(context: Context): ByteArray {
        cached?.let { return it.copyOf() }
        synchronized(this) {
            cached?.let { return it.copyOf() }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(PREF_SEALED, null)
            val passphrase = if (stored != null) {
                unseal(Base64.decode(stored, Base64.NO_WRAP))
            } else {
                val fresh = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
                prefs.edit()
                    .putString(PREF_SEALED, Base64.encodeToString(seal(fresh), Base64.NO_WRAP))
                    .commit()
                fresh
            }
            cached = passphrase
            return passphrase.copyOf()
        }
    }

    /**
     * 봉인된 암호와 Keystore 키를 함께 버린다.
     * '로컬 데이터 전체 삭제'에서 DB 파일을 지운 뒤 호출하면, 남은 파일 조각도 다시 열리지 않는다.
     */
    fun destroy(context: Context) {
        synchronized(this) {
            cached?.fill(0)
            cached = null
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PREF_SEALED).commit()
            runCatching {
                KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected GCM IV size: ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    private fun unseal(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, sealed, 0, IV_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), spec)
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 화면 잠금 없이도 열려야 한다. 결제 알림은 잠금 화면 상태에서 도착한다.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
