package com.msyim.dulssencard.data.db

import androidx.room.TypeConverter
import com.msyim.dulssencard.data.model.ChangeType
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus

/**
 * 열거형은 이름으로, 문자열 리스트는 개행으로 이어 저장한다.
 *
 * 개행을 구분자로 쓰는 이유: 인식 키워드에 쉼표가 들어갈 수 있고(예: "신한, 체크"),
 * 사용자 입력에서 개행은 제거되기 때문이다(CardEditor 에서 걸러낸다).
 *
 * 알 수 없는 이름은 null 로 떨군다. 앱을 다운그레이드해도 DB 를 못 열지는 않게 하려는 것이다.
 */
object Converters {

    private const val SEP = "\n"

    @TypeConverter
    fun stringListToDb(value: List<String>?): String =
        value.orEmpty().joinToString(SEP)

    @TypeConverter
    fun dbToStringList(value: String?): List<String> =
        value?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()

    @TypeConverter
    fun sourceToDb(value: TxSource): String = value.name

    @TypeConverter
    fun dbToSource(value: String): TxSource =
        runCatching { TxSource.valueOf(value) }.getOrDefault(TxSource.MANUAL)

    @TypeConverter
    fun directionToDb(value: TxDirection): String = value.name

    @TypeConverter
    fun dbToDirection(value: String): TxDirection =
        runCatching { TxDirection.valueOf(value) }.getOrDefault(TxDirection.APPROVAL)

    @TypeConverter
    fun statusToDb(value: TxStatus): String = value.name

    @TypeConverter
    fun dbToStatus(value: String): TxStatus =
        runCatching { TxStatus.valueOf(value) }.getOrDefault(TxStatus.PENDING)

    @TypeConverter
    fun pendingReasonToDb(value: PendingReason?): String? = value?.name

    @TypeConverter
    fun dbToPendingReason(value: String?): PendingReason? =
        value?.let { runCatching { PendingReason.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun changeTypeToDb(value: ChangeType): String = value.name

    @TypeConverter
    fun dbToChangeType(value: String): ChangeType =
        runCatching { ChangeType.valueOf(value) }.getOrDefault(ChangeType.CONFIRM)
}
