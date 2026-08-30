plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.powerdebug.record"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.powerdebug.record"
        minSdk = 28
        targetSdk = 34
        versionCode = 31
        versionName = "2.28"
    }

    // 签名密码经环境变量注入（GitHub Secrets），仓库内不出现明文
    val storePass = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
    val keyPass = System.getenv("SIGNING_KEY_PASSWORD") ?: storePass

    signingConfigs {
        create("fixed") {
            storeFile = file("signing/powerdebug.keystore")
            storePassword = storePass
            keyAlias = "powerdebug"
            keyPassword = keyPass
        }
    }

    buildTypes {
        // debug与release统一使用固定签名，保证所有历史/未来版本可覆盖安装
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("fixed")
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
        viewBinding = true
    }
    // 单元测试走 Robolectric（本地JVM跑Android类），需加载 resources/Manifest
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WebDAV需要PROPFIND等自定义HTTP动词，Android原生HttpURLConnection不支持，改用OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    kapt("androidx.room:room-compiler:$room")

    // 回归测试（Robolectric 本地 JVM；CI 跑 testDebugUnitTest）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
