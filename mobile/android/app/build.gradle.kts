plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

// Gemma (LiteRT-LM) is the default phone model, so it is compiled in unless -PlitertTrial=false.
val litertTrial = providers.gradleProperty("litertTrial").orNull != "false"

val mnnRoot = providers.environmentVariable("DIAGRAMMER_MNN_ROOT").orNull ?: ""

android {
    namespace = "com.diagrammer.diagrammer_mobile"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.diagrammer.diagrammer_mobile"
        minSdk = 28
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            // Flutter prepopulates multiple ABIs before this script is evaluated.
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake { arguments += listOf("-DMNN_ROOT=$mnnRoot", "-DANDROID_STL=c++_shared") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    if (litertTrial) {
        sourceSets.getByName("main").java.srcDir("src/trial/kotlin")
    }

    buildTypes {
        release {
            if (litertTrial) {
                proguardFiles("litert-trial-proguard.pro")
            }
            // Development only. Configure private release signing before distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}
flutter { source = "../.." }
if (litertTrial) {
    dependencies { implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0") }
}
