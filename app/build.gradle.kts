import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "2.1.0"
}

// 릴리스 서명 정보는 keystore.properties(git 미포함)에서 읽어온다.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.msyim.dulssencard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.msyim.dulssencard"
        minSdk = 26  // java.time / Keystore AES-GCM 을 디슈거링 없이 쓴다
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 홈 광고 지면: 코드와 레이아웃은 존재하되 기본 비활성.
        // Play 심사(SMS 민감권한) 통과 후 true 로 바꾸면 활성화된다.
        buildConfigField("boolean", "ADS_ENABLED", "false")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ML Kit 이 androidx.fragment 1.0.0 을 끌고 들어온다. 그 버전은 ActivityResult API 와
    // 호환되지 않아 릴리스 lint 가 치명 오류로 잡는다(InvalidFragmentVersionForActivityResult).
    // 프래그먼트를 직접 쓰지는 않지만, 클래스패스에 낡은 버전이 남아 있는 것 자체가 문제라 올린다.
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("ActivityResult API 와 호환되는 최소 버전(1.3.0) 이상으로 강제")
        }
    }

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    // 온디바이스 Gemini Nano(com.google.mlkit:genai-prompt)는 **빼 두었다.**
    // 2026-09-08 갤럭시 S24+(SM-S926N, Android 16)에서 checkStatus() 가 UNAVAILABLE(0) 을
    // 돌려줬다. AICore 는 구글·삼성 둘 다 깔려 있지만 ML Kit GenAI 의 기기 허용 목록에
    // 아직 없다. 지원 기기가 늘면 다시 확인할 것 — 되면 API 키도 네트워크 전송도 없이
    // AI 파싱을 쓸 수 있어 지금 구조에서 가장 이상적이다.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // 광고 SDK는 의도적으로 빠져 있다.
    // 홈 광고 지면(320x50)의 레이아웃과 코드는 AdSlot.kt 에 있고 BuildConfig.ADS_ENABLED=false 로 꺼져 있다.
    // play-services-ads 를 넣는 순간 매니페스트 병합으로 INTERNET / ACCESS_NETWORK_STATE /
    // com.google.android.gms.permission.AD_ID 가 자동 추가되는데, 이는 "네트워크 호출 없음"이라는
    // 개인정보 약속과 SMS 민감권한 심사 서사를 직접 훼손한다.
    // 광고를 켤 때는 (1) 아래 줄 주석 해제 (2) ADS_ENABLED=true (3) 개인정보처리방침·데이터 안전성 섹션 갱신.
    // implementation(libs.play.services.ads)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
