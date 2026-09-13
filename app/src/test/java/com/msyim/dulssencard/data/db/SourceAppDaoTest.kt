package com.msyim.dulssencard.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 직접 쓴 SQL(`@Query("INSERT …")`) 회귀 테스트.
 *
 * Room 이 만든 INSERT 는 엔티티의 모든 컬럼을 채우지만, 손으로 쓴 SQL 은 그렇지 않다.
 * v5 에서 source_apps 에 진단 컬럼을 더하고 [SourceAppDao.touch] 를 안 고쳐, **새로 설치한 기기에서
 * 처음 보는 앱이 알림을 띄울 때마다 NOT NULL 위반으로 앱이 죽었다.** 마이그레이션으로 올라온 DB 는
 * ALTER … DEFAULT 0 덕분에 멀쩡해서, 기존 사용자로는 재현되지 않는 종류의 버그였다(에뮬레이터 검증에서 발견).
 *
 * 그래서 **새 설치 스키마(엔티티로 만든 DB)** 위에서 직접 돌린다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SourceAppDaoTest {

    private fun freshDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun `새로 설치한 DB 에서 처음 보는 앱의 알림을 기록해도 죽지 않는다`() = runBlocking {
        val db = freshDb()
        try {
            db.sourceAppDao().touch("com.example.newapp", "새 앱", issuerKey = null, seenAt = 1_000L)
            val row = db.sourceAppDao().byPackage("com.example.newapp")!!
            assertEquals("새 앱", row.label)
            assertFalse("처음 본 앱은 꺼진 상태로 들어가야 한다 — 내용을 읽지 않는다", row.enabled)
            assertEquals(1_000L, row.lastSeenAt)
            assertEquals(0L, row.lastPaymentAt)
            assertEquals(0, row.failedCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun `이미 있는 앱은 켬 끔과 진단 카운터를 건드리지 않고 시각만 갱신한다`() = runBlocking {
        val db = freshDb()
        try {
            db.sourceAppDao().touch("com.kakao.talk", "카카오톡", null, seenAt = 1_000L)
            val enabled = db.sourceAppDao().byPackage("com.kakao.talk")!!.copy(enabled = true, recognizedCount = 5)
            db.sourceAppDao().upsert(enabled)
            db.sourceAppDao().touch("com.kakao.talk", "카카오톡", null, seenAt = 2_000L)
            val row = db.sourceAppDao().byPackage("com.kakao.talk")!!
            assertEquals(true, row.enabled)
            assertEquals(5, row.recognizedCount)
            assertEquals(2_000L, row.lastSeenAt)
        } finally {
            db.close()
        }
    }
}
