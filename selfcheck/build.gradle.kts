import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
}

val selfCheckApplicationId = "asia.nana7mi.arirang.selfcheck"
val arirangApplicationId = rootProject.extra["arirangApplicationId"] as String
val arirangSubmoduleConfigDir = rootProject.extra["arirangSubmoduleConfigDir"] as String
val arirangSubmoduleConfigFile = rootProject.extra["arirangSubmoduleConfigFile"] as String

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

configure<ApplicationExtension> {
    namespace = selfCheckApplicationId
    compileSdk = 37

    defaultConfig {
        applicationId = selfCheckApplicationId
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.3.1-experimental"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TARGET_PACKAGE_NAME", buildConfigString(arirangApplicationId))
        buildConfigField("String", "SUBMODULE_CONFIG_DIR", buildConfigString(arirangSubmoduleConfigDir))
        buildConfigField("String", "SUBMODULE_CONFIG_FILE", buildConfigString(arirangSubmoduleConfigFile))
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "ja"
        )
    }

    buildFeatures {
        buildConfig = true
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/maven/**"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.play.services.location)
    implementation(libs.play.services.ads.identifier)
    implementation(libs.play.services.appset)
}
