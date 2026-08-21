plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.armakeup"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.example.armakeup"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    androidResources {
        noCompress += "task"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.filament.android)
    implementation(libs.filamat.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

val debugUnitTestRuntimeClasspath = objects.fileCollection()
afterEvaluate {
    debugUnitTestRuntimeClasspath.from(
        tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").get().classpath,
    )
}

tasks.register<org.gradle.api.tasks.JavaExec>("analyzeTrackingTelemetry") {
    group = "verification"
    description = "Analyzes an existing .arv6 recording without rebuilding or replaying it on-device."
    dependsOn("testDebugUnitTest")
    classpath = debugUnitTestRuntimeClasspath
    mainClass.set("com.example.armakeup.tracking.TrackingTelemetryAnalysisCli")
    val telemetryFile = providers.gradleProperty("telemetryFile").orNull
        ?: error("Pass -PtelemetryFile=<absolute-or-app-relative-path-to-.arv6>")
    args(telemetryFile)
}
