import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun localConfigValue(name: String, fallback: String = ""): String =
    localProperties.getProperty(name).orEmpty().trim().ifBlank { fallback }

fun String.asBuildConfigString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

val taskApiKey = localConfigValue("taskflowApiKey")
val taskApiBaseUrl = localConfigValue("taskflowApiBaseUrl", "https://token-plan-cn.xiaomimimo.com/v1")
val taskApiModel = localConfigValue("taskflowApiModel", "mimo-v2-flash")
val appVersionName = "1.0"
val releaseStoreFilePath = localConfigValue("releaseStoreFile")
val releaseStorePassword = localConfigValue("releaseStorePassword")
val releaseKeyAlias = localConfigValue("releaseKeyAlias")
val releaseKeyPassword = localConfigValue("releaseKeyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isNotBlank() } && rootProject.file(releaseStoreFilePath).isFile

ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
}

android {
    namespace = "com.taskflow.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.taskflow.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TASK_API_KEY", "\"${taskApiKey.asBuildConfigString()}\"")
        buildConfigField("String", "TASK_API_BASE_URL", "\"${taskApiBaseUrl.asBuildConfigString()}\"")
        buildConfigField("String", "TASK_API_MODEL", "\"${taskApiModel.asBuildConfigString()}\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
        }

        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val copyDebugApk = tasks.register("copyDebugApk") {
    dependsOn("packageDebug")
    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
            into(layout.buildDirectory.dir("outputs/apk/debug"))
            rename { "ChaosQuest-$appVersionName-debug.apk" }
        }
    }
}

val copyReleaseApk = tasks.register("copyReleaseApk") {
    dependsOn("packageRelease")
    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
            into(layout.buildDirectory.dir("outputs/apk/release"))
            rename { "ChaosQuest-$appVersionName-release.apk" }
        }
    }
}

tasks.configureEach {
    when (name) {
        "assembleDebug" -> finalizedBy(copyDebugApk)
        "assembleRelease" -> finalizedBy(copyReleaseApk)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha09")
    implementation("androidx.appfunctions:appfunctions-service:1.0.0-alpha09")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha09")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
