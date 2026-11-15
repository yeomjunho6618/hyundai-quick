import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 로컬 SDK 위치 (android.car.jar 를 컴파일에만 쓰려고)
val sdkDir = Properties().apply { load(rootProject.file("local.properties").inputStream()) }.getProperty("sdk.dir")

android {
    // 코드 패키지 이름. 앱 ID(applicationId)는 옛 프로젝트명이라 그대로 둠
    namespace = "probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "probe.reversemap"
        // AAOS 14(API 34) 기준, 최소는 28
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    // 서명 정보는 저장소에 올리지 않는 keystore.properties 에서 읽는다
    val ks = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.let { load(it) } }
    signingConfigs {
        create("release") {
            if (ks.isNotEmpty()) {
                storeFile = rootProject.file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }
    // release 는 위 서명 설정으로 서명
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 차량 SDK (공조·시트·조명·기어 / 내비 경로)
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // 차 안에 이미 있는 라이브러리라 앱에 넣지 않고 컴파일에만 쓴다
    compileOnly(files("$sdkDir/platforms/android-35/optional/android.car.jar"))
}
