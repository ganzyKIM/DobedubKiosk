import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 릴리스 서명 정보는 git 밖 keystore.properties 에서 읽는다(있을 때만 릴리스 서명 적용).
// 이 파일이 없는 PC(키스토어 미보유)에서는 릴리스도 디버그 키로 서명되어 빌드는 되지만
// 배포된 기기의 자동 업데이트와는 서명이 달라 호환되지 않는다 — 운영 빌드는 반드시 키스토어 보유 PC에서.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// 함대 관리 서버 URL — 기기가 체크인/업데이트 확인을 보낼 주소.
// 배포 전 실제 서버 주소로 바꿀 것. gradle -PfleetServerUrl=... 또는 gradle.properties 로 재정의 가능.
val fleetServerUrl = (project.findProperty("fleetServerUrl") as String?) ?: "https://kiosk.dobedub.com"

android {
    namespace = "com.dobedub.kiosk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dobedub.kiosk"
        minSdk = 29
        targetSdk = 34
        // versionCode: 릴리스마다 반드시 +1 (정수). 함대 서버가 이 값이 기기보다 크면 자동 업데이트 트리거.
        // versionName: 사람이 읽는 표시용 버전(임의 형식). 관리자 화면/백오피스에 노출.
        versionCode = 25
        versionName = "2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FLEET_SERVER_URL", "\"$fleetServerUrl\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 키스토어가 있으면 릴리스 서명, 없으면 기본(디버그) 서명으로 폴백.
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.tabler.icons)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
