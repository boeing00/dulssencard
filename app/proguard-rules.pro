# SQLCipher: JNI 로 부르는 네이티브 바인딩 클래스는 이름이 유지돼야 한다.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Room 이 생성한 구현체는 리플렉션으로 찾는다.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# 엔티티는 필드명이 곧 컬럼명이다. 난독화되면 스키마가 어긋난다.
-keepclassmembers class com.msyim.dulssencard.data.model.** { <fields>; }

# 시스템이 매니페스트 이름으로 인스턴스화하는 컴포넌트.
-keep class com.msyim.dulssencard.notification.PaymentNotificationListener { *; }

# 진단 로그에 거래 데이터가 남지 않도록, 릴리스에서 Log 호출을 통째로 지운다.
# (PRD §10 — 지원 진단 로그에는 거래 데이터와 매칭 키워드를 기록하지 않는다.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
