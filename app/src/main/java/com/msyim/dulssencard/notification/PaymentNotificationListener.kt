package com.msyim.dulssencard.notification

import android.app.Notification
import android.content.pm.PackageManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.msyim.dulssencard.data.DulSsenRepository
import com.msyim.dulssencard.data.Settings
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ingest.RawMessage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 카드사 앱 푸시와 카카오톡 알림톡에서 결제 통지를 읽는다.
 *
 * ## 이 클래스가 지키는 선
 *
 * 알림 접근 권한은 기기의 **모든** 알림을 볼 수 있는 매우 넓은 권한이다.
 * 그래서 내용을 읽기 전에 패키지 허용 목록을 먼저 본다:
 *
 *  1. [onNotificationPosted] 진입 즉시 패키지명을 확인한다.
 *  2. 사용자가 켜지 않은 패키지면 **본문을 꺼내지 않고** 곧바로 반환한다.
 *     이 앱이 알림을 띄웠다는 사실(패키지명·앱 이름·시각)만 설정 화면 목록용으로 남긴다.
 *  3. 켜진 패키지일 때만 제목·본문을 읽어 파서에 넘긴다. 본문은 파싱 뒤 버려진다.
 *
 * 즉 메신저·메일·사진 앱의 알림 내용은 이 프로세스 안에서도 읽히지 않는다.
 *
 * ## 이 서비스가 유일한 수집 경로다
 *
 * 이 앱은 SMS 권한을 선언하지 않는다. 결제 문자·카드사 앱 푸시·카카오 알림톡을 모두
 * 알림 접근 하나로 읽는다. 문자 앱이 띄운 알림은 [TxSource.SMS] 로 분류한다.
 * Play 심사 자료에는 알림 접근의 핵심 기능성·허용 목록 방식·미전송을 기술한다.
 */
class PaymentNotificationListener : NotificationListenerService() {

    /**
     * 알림 한 건을 처리하다 실패해도 **앱 프로세스를 죽이지 않는다.** 이 서비스는 모든 알림마다 불리므로,
     * 처리되지 않은 예외 하나가 앱 전체(열려 있는 화면 포함)를 반복해서 죽인다.
     * 실패 내용은 기록하지 않는다 — 예외 메시지에 알림 조각이 섞일 수 있다. 개수는 소스 진단이 센다.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> },
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return

        val notification = sbn.notification ?: return
        // 그룹 요약과 진행 중 알림(다운로드·음악)은 결제 통지가 아니다.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val repository = DulSsenRepository.get(applicationContext)
        val label = appLabel(packageName)
        val postedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()

        scope.launch {
            // 목록에 이름만 남긴다. 내용은 아직 건드리지 않았다.
            repository.noteSourceAppSeen(packageName, label)

            if (repository.getSetting(Settings.AUTO_COLLECT_ENABLED) == "false") return@launch
            if (!repository.isSourceAppEnabled(packageName)) return@launch

            // 여기서부터만 내용을 읽는다.
            val extras = notification.extras ?: return@launch
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = buildBody(extras) ?: return@launch
            if (body.isBlank()) return@launch

            repository.ingest(
                RawMessage(
                    source = classifySource(packageName),
                    senderKey = packageName,
                    title = title,
                    body = body,
                    receivedAt = postedAt,
                ),
            )
        }
    }

    /**
     * 알림을 띄운 앱으로 거래 경로를 판정한다.
     *
     * 문자 앱이 띄운 알림은 실체가 결제 SMS 이므로 [TxSource.SMS] 로 남긴다.
     * 사용자가 상세 화면에서 "이게 문자로 온 건지 앱 푸시로 온 건지"를 구분할 수 있어야 하고,
     * 나중에 SMS 권한 기반 수집을 되살리더라도 과거 거래의 의미가 바뀌지 않는다.
     */
    private fun classifySource(packageName: String): TxSource = when {
        packageName == IssuerRegistry.KAKAO_PACKAGE -> TxSource.KAKAO
        IssuerRegistry.isMessagingApp(packageName, defaultSmsPackage()) -> TxSource.SMS
        else -> TxSource.PUSH
    }

    private fun defaultSmsPackage(): String? = runCatching {
        Telephony.Sms.getDefaultSmsPackage(applicationContext)
    }.getOrNull()

    /**
     * 알림 본문을 모은다.
     *
     * 카드사 앱은 [Notification.EXTRA_TEXT] 한 줄로 오는 경우가 많고, 긴 문구는
     * [Notification.EXTRA_BIG_TEXT] 에 들어간다. 카카오톡은 MessagingStyle 을 써서
     * [Notification.EXTRA_TEXT_LINES] 에 여러 줄이 쌓이므로 마지막 줄이 방금 온 메시지다.
     */
    private fun buildBody(extras: android.os.Bundle): String? {
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.lastOrNull()?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    private fun appLabel(packageName: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse {
        IssuerRegistry.byPackage(packageName)?.displayName ?: packageName
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit
}

/** 패키지 라벨 조회 실패를 조용히 넘기기 위한 도우미. */
internal fun PackageManager.labelOrNull(packageName: String): String? = runCatching {
    getApplicationLabel(getApplicationInfo(packageName, 0)).toString()
}.getOrNull()
