import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl
import java.util.Properties

plugins {
    alias(libs.plugins.bika.android.application)
    alias(libs.plugins.bika.android.application.compose)
    alias(libs.plugins.bika.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.shizq.bika"

    defaultConfig {
        applicationId = "com.shizq.bika"
        versionCode = 74
        versionName = "1.11.24"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) load(f.inputStream())
        }
        create("keyStore") {
            storeFile = file(localProps.getProperty("STORE_FILE", "appkey.jks"))
            storePassword = localProps.getProperty("STORE_PASSWORD", "123456")
            keyAlias = localProps.getProperty("KEY_ALIAS", "shizq")
            keyPassword = localProps.getProperty("KEY_PASSWORD", "123456")

            // 三种签名方案全开，覆盖率最大：
            // - V1（JAR 签名）：最老设备 / 部分第三方安装器与校验工具只认它。
            //   默认值取决于 minSdk（本项目 26 时默认关闭），这里显式打开。
            // - V2：Android 7.0+ 的整包签名，也是目前实际生效的那一层。
            // - V3：Android 9.0+，在 V2 基础上记录签名者信息（支持密钥轮换）。
            // 三者共存不冲突，Android 会按版本取它支持的最高一档校验。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            // V4 只服务于增量安装（adb install --incremental），需要额外分发 .idsig
            // 文件，单独装 APK 没有任何作用，保持关闭。
            enableV4Signing = false
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("keyStore")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("keyStore")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val suffix = when (abi) {
                "arm64-v8a" -> "_arm64-v8a"
                "armeabi-v7a" -> "_armeabi-v7a"
                else -> if (!abi.isNullOrEmpty()) "_$abi" else ""
            }
            (output as? VariantOutputImpl)?.outputFileName = "BIKA_v${output.versionName.get()}$suffix.apk"
        }
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.download)
    implementation(projects.core.domain)
    implementation(projects.core.logging)
    implementation(projects.core.ui)
    implementation(projects.sync.work)

    implementation(projects.feature.comicdetail)
    implementation(projects.feature.reader)
    implementation(projects.feature.settings)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.windowSizeClass)
    implementation(libs.androidx.compose.runtime.tracing)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewModel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.savedstate.compose)
    implementation(libs.androidx.tracing.ktx)
    implementation(libs.androidx.window.core)
    implementation(libs.coil.kt)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.appcompat)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.coil.compose)

    implementation(libs.reorderable)

    implementation(libs.flowredux)

    // 测试框架依赖：仅测试作用域，避免打进 release APK
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))

    // Compose UI 交互测试（instrumented）
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.testManifest)
}

baselineProfile {
    // Don't build on every iteration of a full assemble.
    // Instead enable generation directly for the release build variant.
    automaticGenerationDuringBuild = false

    // Make use of Dex Layout Optimizations via Startup Profiles
    dexLayoutOptimization = true
}