import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val arirangApplicationId = rootProject.extra["arirangApplicationId"] as String
val arirangSubmoduleConfigDir = rootProject.extra["arirangSubmoduleConfigDir"] as String
val arirangSubmoduleConfigFile = rootProject.extra["arirangSubmoduleConfigFile"] as String

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

configure<ApplicationExtension>  {
    namespace = arirangApplicationId
    compileSdk = 37

    defaultConfig {
        applicationId = arirangApplicationId
        minSdk = 34
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0-experimental"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = false
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
        compose = true
        aidl = true
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
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/maven/**"
            excludes += "**/dump_syms/**"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.register("unitTestClasses") {
    dependsOn("compileDebugUnitTestSources")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.google.gson)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    implementation(libs.androidx.datastore.preferences)
    compileOnly(libs.play.services.appset)
    compileOnly(libs.api)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
android {
    compileSdk {
        version = release(37)
    }
    ndkVersion = "23.1.7779620"
    buildToolsVersion = "37.0.0"
}
