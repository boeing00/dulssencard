package com.msyim.dulssencard.ingest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony
import com.msyim.dulssencard.notification.PaymentNotificationListener

/**
 * 수집 경로 조회와 시스템 설정 진입.
 *
 * 이 앱의 수집 경로는 알림 접근 하나뿐이다. SMS 권한은 선언하지 않는다.
 * 결제 문자는 기본 문자 앱이 띄운 알림을 통해 읽는다.
 *
 * 알림 접근은 앱이 요청할 수 없다 — 사용자가 시스템 설정에서 직접 켜야 하므로,
 * 앱이 할 수 있는 일은 상태를 읽고 설정 화면으로 보내는 것뿐이다.
 */
object SourceGate {

    /** 알림 접근이 허용돼 있는가. */
    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val component = ComponentName(context, PaymentNotificationListener::class.java)
        return enabled.split(':').any { entry ->
            val parsed = ComponentName.unflattenFromString(entry)
            parsed == component || parsed?.packageName == context.packageName
        }
    }

    /**
     * 기본 문자 앱 패키지. 결제 문자를 어느 앱의 알림에서 읽을지 정하는 데 쓴다.
     * 문자를 못 받는 기기(태블릿 등)에서는 null 이다.
     */
    fun defaultSmsPackage(context: Context): String? = runCatching {
        Telephony.Sms.getDefaultSmsPackage(context)
    }.getOrNull()

    /** 알림 접근 설정 화면. */
    fun notificationAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 앱 상세 설정 화면. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
