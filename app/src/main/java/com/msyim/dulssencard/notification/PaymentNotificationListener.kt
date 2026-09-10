package com.msyim.dulssencard.notification

import android.app.Notification
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.msyim.dulssencard.data.DulSsenRepository
import com.msyim.dulssencard.data.Settings
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ingest.RawMessage
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 패키지별로 '알림을 띄웠다'를 마지막으로 기록한 시각.
     *
     * 이게 없으면 **기기의 모든 알림 하나하나마다** 암호화 DB 에 쓰기가 일어난다.
     * 카톡 대화 알림까지 포함하면 하루에 수백~수천 번이다. 이 기록은 설정 화면의
     * 목록을 채우려는 것뿐이라 분 단위 정확도가 필요 없다.
     */
    private val lastNotedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
            if (shouldNote(packageName)) {
                repository.noteSourceAppSeen(packageName, label)
            }

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

    /**
     * 이 패키지를 지금 기록할까. 처음 보는 앱은 언제나 기록한다 —
     * 그래야 허용 목록에 없는 카드사 앱도 설정 화면에 나타나 사용자가 켤 수 있다.
     */
    private fun shouldNote(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastNotedAt[packageName]
        if (last != null && now - last in 0 until SOURCE_NOTE_INTERVAL_MILLIS) return false
        lastNotedAt[packageName] = now
        return true
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    private companion object {
        /** 같은 앱을 다시 기록하기까지 기다리는 시간. */
        const val SOURCE_NOTE_INTERVAL_MILLIS = 60L * 60 * 1000
    }
}
