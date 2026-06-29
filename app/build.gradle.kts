plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dawon.autokkk"
    compileSdk = 34

    // 고정 debug 서명키(repo 커밋) — CI 빌드마다 키가 바뀌어 덮어설치가 막히는 문제 해결.
    // 표준 안드 debug 자격증명(비공개 아님). 이후 모든 빌드는 같은 서명 → 업데이트 설치 가능.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.dawon.autokkk"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.14"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 다운로드 폴더 읽기 프로브(SAF 트리 재귀 나열). 매니페스트 권한 추가 없음.
    implementation("androidx.documentfile:documentfile:1.0.1")
}
