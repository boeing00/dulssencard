package com.msyim.dulssencard.backup

import com.msyim.dulssencard.data.DulSsenRepository
import com.msyim.dulssencard.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * 백업의 모든 흐름을 한곳에서 조립한다. 화면은 이 클래스만 부른다.
 *
 * ```
 * 내보내기:  비밀번호 → 현재 데이터 읽기(한 트랜잭션) → JSON → Argon2id 키 유도 → AES-GCM → 바이트
 * 가져오기:  바이트 → 형식 확인 → 비밀번호 → 복호화(무결성 검증) → 내용 검증 → 미리보기
 *            → 모드 선택 → [현재 데이터 자동 백업 → 재계획 → 적용] 한 트랜잭션
 * 되돌리기:  자동 백업 목록 → 기기 키로 복호화 → 내용 검증 → 전체 교체(이것도 먼저 자동 백업)
 * ```
 *
 * 파일을 **어디에 쓰고 어디서 읽는지는 화면이 정한다**(시스템 파일 선택기, 저장소 권한 없음).
 * 이 클래스는 바이트만 다룬다 — 평문이 파일 시스템에 닿을 경로가 없다.
 */
class BackupManager(
    private val repository: DulSsenRepository,
    private val autoBackups: AutoBackupStore,
    private val appVersion: String,
    private val schemaVersion: Int = AppDatabase.SCHEMA_VERSION,
    private val kdfParams: BackupCrypto.KdfParams = BackupCrypto.KdfParams.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface OpenResult {
        data class Opened(val payload: BackupPayload) : OpenResult
        data class Failed(val failure: BackupCrypto.Failure) : OpenResult
        data class NewerApp(val backupSchema: Int, val appSchema: Int) : OpenResult
        data class Invalid(val problems: List<String>) : OpenResult
    }

    /** 사용자가 고른 비밀번호가 쓸 만한가. 화면에서 저장 버튼을 켜기 전에 본다. */
    fun passwordProblem(password: CharArray, confirm: CharArray): String? = when {
        password.size < BackupCrypto.MIN_PASSWORD_LENGTH ->
            "비밀번호는 ${BackupCrypto.MIN_PASSWORD_LENGTH}자 이상이어야 합니다"
        !password.contentEquals(confirm) -> "두 비밀번호가 다릅니다"
        password.all { it == password[0] } -> "같은 글자만 반복한 비밀번호는 쓸 수 없습니다"
        else -> null
    }

    /**
     * 암호화한 백업 바이트를 만든다. 키 유도에 몇 초 걸리므로 [Dispatchers.Default] 에서 돈다.
     * JSON 평문 바이트는 암호화 뒤 0 으로 덮는다(문자열 사본까지 지울 수는 없다 — JVM 의 한계).
     */
    suspend fun export(password: CharArray): ByteArray = withContext(Dispatchers.Default) {
        require(password.size >= BackupCrypto.MIN_PASSWORD_LENGTH) { "password too short" }
        val plaintext = BackupPayload.encode(payloadOf(repository.readForBackup()))
        try {
            BackupCrypto.encryptWithPassword(plaintext, password, kdfParams)
        } finally {
            plaintext.fill(0)
        }
    }

    /** 파일이 비밀번호를 요구하는가. null 이면 덜쎈카드 백업이 아니다. */
    fun requiresPassword(file: ByteArray): Boolean? = BackupCrypto.requiresPassword(file)

    suspend fun open(file: ByteArray, password: CharArray): OpenResult =
        openWith { BackupCrypto.decrypt(file, BackupCrypto.Key.Password(password)) }

    /** 적용하면 무엇이 바뀌는지. 쓰지 않는다. */
    suspend fun preview(payload: BackupPayload, mode: ImportPlanner.Mode): ImportPlanner.Plan =
        withContext(Dispatchers.Default) { ImportPlanner.plan(repository.readForBackup(), payload, mode) }

    /**
     * 적용한다. **먼저 현재 데이터를 자동 백업하고**, 성공해야만 적용한다. 전부 한 트랜잭션이라
     * 자동 백업이 실패하거나 적용 도중 무엇이 깨지면 아무것도 바뀌지 않는다.
     */
    suspend fun apply(payload: BackupPayload, mode: ImportPlanner.Mode): ImportPlanner.Plan =
        repository.importAtomically(payload, mode, clock()) { local ->
            val bytes = BackupPayload.encode(payloadOf(local))
            try {
                autoBackups.save(bytes, clock())
            } finally {
                bytes.fill(0)
            }
        }

    fun autoBackupList(): List<AutoBackupStore.Entry> = autoBackups.list()

    suspend fun openAutoBackup(entry: AutoBackupStore.Entry): OpenResult = openWith { autoBackups.read(entry) }

    /** '로컬 데이터 전체 삭제' 에서 부른다. 자동 백업도 데이터 사본이다. */
    fun deleteAllAutoBackups() {
        autoBackups.deleteAll()
    }

    private suspend fun openWith(decrypt: () -> ByteArray): OpenResult = withContext(Dispatchers.Default) {
        val plaintext = try {
            decrypt()
        } catch (e: BackupCrypto.BackupException) {
            return@withContext OpenResult.Failed(e.failure)
        }
        try {
            val payload = try {
                BackupPayload.decode(plaintext)
            } catch (e: SerializationException) {
                return@withContext OpenResult.Invalid(listOf("백업 내용을 해석할 수 없습니다"))
            } catch (e: IllegalArgumentException) {
                return@withContext OpenResult.Invalid(listOf("백업 내용을 해석할 수 없습니다"))
            }
            when (val result = BackupValidator.validate(payload, schemaVersion)) {
                BackupValidator.Result.Valid -> OpenResult.Opened(payload)
                is BackupValidator.Result.NewerSchema -> OpenResult.NewerApp(result.backupSchema, result.appSchema)
                is BackupValidator.Result.Invalid -> OpenResult.Invalid(result.problems)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun payloadOf(local: ImportPlanner.Local) = BackupPayload.of(
        cards = local.cards,
        txns = local.txns,
        adjustments = local.adjustments,
        settings = local.settings,
        cycleSnapshots = local.cycleSnapshots,
        sourceApps = local.sourceApps,
        appVersion = appVersion,
        schemaVersion = schemaVersion,
        now = clock(),
    )
}

/** 사용자에게 보여 줄 실패 문구. 원인을 모르는 문구("오류")를 쓰지 않는다. */
fun BackupCrypto.Failure.message(): String = when (this) {
    BackupCrypto.Failure.NotABackup -> "덜쎈카드 백업 파일이 아닙니다"
    BackupCrypto.Failure.UnsupportedFormat -> "더 새 버전의 앱에서 만든 백업입니다. 앱을 업데이트해 주세요"
    BackupCrypto.Failure.WrongKeyKind -> "이 기기의 자동 백업 파일은 비밀번호로 열 수 없습니다"
    BackupCrypto.Failure.UnsafeParameters -> "파일 머리 정보가 비정상입니다. 변조된 파일일 수 있어 열지 않았습니다"
    BackupCrypto.Failure.TooLarge -> "파일이 너무 큽니다"
    BackupCrypto.Failure.WrongPasswordOrCorrupted -> "비밀번호가 틀렸거나 파일이 손상되었습니다"
}
