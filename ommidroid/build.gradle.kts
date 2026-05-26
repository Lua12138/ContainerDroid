import org.gradle.api.Project

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    //kotlin("android")
}

fun Project.blackBoxBooleanOption(name: String, defaultValue: Boolean): Boolean {
    val rawValue = findProperty(name)
        ?: rootProject.findProperty(name)
        ?: rootProject.extra.takeIf { it.has(name) }?.get(name)
    return rawValue?.toString()?.toBlackBoxBoolean() ?: defaultValue
}

fun String.toBlackBoxBoolean(): Boolean {
    return equals("true", ignoreCase = true) || equals("y", ignoreCase = true) || this == "1"
}

val releaseKeystoreFile = file("keystore.jks")
val blackboxDiagnosticLogcatEnabledValue =
    blackBoxBooleanOption("blackboxDiagnosticLogcatEnabled", true)
val blackboxDexDumpEnabledValue =
    blackBoxBooleanOption("blackboxDexDumpEnabled", true)
val blackboxDebuggableEnabledValue =
    blackBoxBooleanOption("blackboxDebuggableEnabled", blackboxDiagnosticLogcatEnabledValue)

android {
    namespace = "com.ommidroid.example"
    compileSdk {
        version = release(35)
    }
    ndkVersion = "29.0.13846066"

    defaultConfig {
        applicationId = "com.ommidroid.example"
        minSdk = 21
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = 1
        versionName = "1.0"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField(
            "boolean",
            "BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED",
            blackboxDiagnosticLogcatEnabledValue.toString(),
        )
        buildConfigField(
            "boolean",
            "BLACKBOX_DEX_DUMP_ENABLED",
            blackboxDexDumpEnabledValue.toString(),
        )
    }
    signingConfigs {
        if (releaseKeystoreFile.isFile) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = "123456"
                keyAlias = "key0"
                keyPassword = "123456"
            }
        }
    }
    buildTypes {
        debug {
            isDebuggable = blackboxDebuggableEnabledValue
            isJniDebuggable = blackboxDebuggableEnabledValue
            signingConfig = if (releaseKeystoreFile.isFile) {
                signingConfigs["release"]
            } else {
                signingConfigs["debug"]
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseKeystoreFile.isFile) {
                signingConfigs["release"]
            } else {
                signingConfigs["debug"]
            }
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        disable += setOf("InlinedApi", "OldTargetApi", "UseTomlInstead")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")

    implementation(project(":Bcore"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
