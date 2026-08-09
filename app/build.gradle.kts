import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val reqToken: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}.getProperty("REQ_TOKEN") ?: ""

gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { it.name.contains("Release", ignoreCase = true) }
    if (buildingRelease && reqToken.isBlank()) {
        throw GradleException(
            "local.properties 缺少 REQ_TOKEN，正式包的「申请存档」会失效。" +
                "如确认不需要该功能，请显式设为 REQ_TOKEN=none"
        )
    }
}

android {
    namespace = "io.github.twitterarchiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.twitterarchiver"
        minSdk = 30
        targetSdk = 37
        versionCode = 14
        versionName = "1.3.0"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "mode"
    productFlavors {
        create("visitor") {
            dimension = "mode"
            applicationIdSuffix = ".visitor"
            versionNameSuffix = "-visitor"
            resValue("string", "app_name", "推文存档")
            buildConfigField("boolean", "IS_ADMIN", "false")
            // 受限申请 token：只能对 requests 仓库开 Issue，权限极小
            buildConfigField("String", "REQ_TOKEN", "\"$reqToken\"")
        }
        create("admin") {
            dimension = "mode"
            applicationIdSuffix = ".admin"
            versionNameSuffix = "-admin"
            resValue("string", "app_name", "存档管理")
            buildConfigField("boolean", "IS_ADMIN", "true")
            buildConfigField("String", "REQ_TOKEN", "\"$reqToken\"")
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters += listOf("zh-rCN", "zh-rTW")
    }
    packaging {
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.datastore.preferences)
}
