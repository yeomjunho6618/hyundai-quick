import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sdkDir = Properties().apply { load(rootProject.file("local.properties").inputStream()) }.getProperty("sdk.dir")

android {
    namespace = "probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "probe.reversemap"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    compileOnly(files("$sdkDir/platforms/android-35/optional/android.car.jar"))
}
