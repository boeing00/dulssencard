package com.msyim.dulssencard.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * **복원 전 자동 백업.** 가져오기·복원을 적용하기 직전의 데이터를 이 기기 안에 암호화해 둔다.
 * 잘못 가져왔을 때 비밀번호 없이 되돌리는 안전망이다.
 *
 * - 형식은 내보내기와 같은 [BackupCrypto] 다. 열쇠만 사용자 비밀번호 대신 **이 기기의 Keystore 키**다.
 *   Keystore 키는 기기 밖으로 꺼낼 수 없으므로 이 파일은 다른 기기에서 열리지 않는다 — 의도한 성질이다.
 * - 앱 내부 저장소(`filesDir`)에 둔다. 매니페스트가 시스템 클라우드 백업·기기 이전을 전부 막고 있어
 *   (`allowBackup=false`, data_extraction_rules) 기기 밖으로 나가지 않는다.
 * - 최근 [keep] 개만 남긴다.
 * - **전체 삭제 때 함께 지우고 키도 버린다.** 안 그러면 "전체 삭제" 뒤에도 데이터 사본이 남는다.
 */
class AutoBackupStore(
    private val directory: File,
    private val keyProvider: () -> SecretKey,
    private val keep: Int = 3,
) {

    data class Entry(val file: File, val createdAt: Long, val sizeBytes: Long)

    /** 저장에 실패하면 예외를 던진다. 호출자는 그때 가져오기를 **중단해야** 한다 — 안전망 없이 덮어쓰지 않는다. */
    fun save(plaintext: ByteArray, now: Long): Entry {
        check(directory.isDirectory || directory.mkdirs()) { "자동 백업 폴더를 만들 수 없다" }
        val sealed = BackupCrypto.encryptWithDeviceKey(plaintext, keyProvider())
        val target = File(directory, "$PREFIX$now$SUFFIX")
        val temp = File(directory, "${target.name}.tmp")
        // 임시 파일에 다 쓴 뒤 이름을 바꾼다. 쓰다 죽으면 반쪽짜리 백업이 '최근 백업'으로 보이는 일이 없다.
        temp.writeBytes(sealed)
        check(temp.renameTo(target)) { "자동 백업을 확정하지 못했다" }
        prune()
        return Entry(target, now, target.length())
    }

    fun list(): List<Entry> =
        directory.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { file ->
                val stamp = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: return@mapNotNull null
                Entry(file, stamp, file.length())
            }
            .sortedByDescending { it.createdAt }

    fun read(entry: Entry): ByteArray =
        BackupCrypto.decrypt(entry.file.readBytes(), BackupCrypto.Key.Device(keyProvider()))

    /** 자동 백업 파일을 모두 지운다. 남은 임시 파일도. */
    fun deleteAll() {
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun prune() {
        list().drop(keep).forEach { it.file.delete() }
        directory.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty().forEach { it.delete() }
    }

    private companion object {
        const val PREFIX = "auto-"
        const val SUFFIX = ".dscb"
    }
}

/** 자동 백업 전용 Android Keystore AES-256-GCM 키. DB 암호를 봉인하는 키와 **따로 둔다.** */
object DeviceBackupKey {

    private const val ALIAS = "dulssencard_auto_backup_v1"
    private const val KEYSTORE = "AndroidKeyStore"

    fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    fun destroy() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}
