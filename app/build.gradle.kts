plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.duan.lynkcluster"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.duan.lynkcluster"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "1.5.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 无正式 keystore，装车验证阶段用 debug key。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// 运行时仅依赖 Kotlin 官方协程库（StateFlow 合并链路）；Robolectric 只用于把
// Canvas 真实渲染成 PNG 做视觉校验，不进 APK。
dependencies {
    // 1.9.0 与 Kotlin 2.3 编译器同代，且本机 Gradle 缓存里已有，可离线解析。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // android.car 是系统 SDK 的可选库，只在车机上存在。compileOnly 不打包，
    // 运行时用 try/catch 加载，普通手机上自动回退模拟数据。
    compileOnly(
        files(
            "${android.sdkDirectory}/platforms/android-${android.compileSdk}" +
                "/optional/android.car.jar",
        ),
    )
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
