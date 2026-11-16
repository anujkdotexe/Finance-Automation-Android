import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.financeautomation"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.financeautomation"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }

    signingConfigs {
        create("releaseSigning") {
            if (keystorePropsFile.exists()) {
                val storePath = keystoreProps.getProperty("storeFile")
                storeFile = if (storePath != null) file(storePath) else null
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else {
                initWith(signingConfigs.getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("releaseSigning")
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "ENABLE_DISCOVERY_SCAN", "false")
        }
        create("beta") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("releaseSigning")
            buildConfigField("Boolean", "ENABLE_DISCOVERY_SCAN", "true")
            if (project.hasProperty("devFast")) {
                // Development builds only (-PdevFast): one ABI. Never used for the final proof or for friends.
                ndk {
                    abiFilters.clear()
                    abiFilters += listOf("arm64-v8a")
                }
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("Boolean", "ENABLE_DISCOVERY_SCAN", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.tooling.preview)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.tooling)

    // Navigation Compose
    implementation(libs.navigation.compose)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlite)
    implementation(libs.sqlcipher)

    // Jetpack WorkManager
    implementation(libs.work.runtime.ktx)

    // OkHttp Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // KotlinX Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // DataStore Preferences
    implementation(libs.datastore.preferences)

    // Google Play Services Auth
    implementation(libs.play.services.auth)

    // KotlinX Serialization JSON
    implementation(libs.kotlinx.serialization.json)

    // Firebase Crashlytics (inert without google-services.json)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    // Instrumented Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}


if (project.hasProperty("devFast")) {
    // Development builds only (-PdevFast): skip the lint checks that run inside assembleBeta.
    tasks.configureEach {
        if (name.contains("lintvital", ignoreCase = true) && name.contains("Beta")) {
            enabled = false
        }
    }
}


// Signing safety. Without keystore.properties the release and beta types fall back to the debug key (see
// signingConfigs above). That is acceptable for a local beta build, never for a release build.
gradle.taskGraph.whenReady {
    if (!rootProject.file("keystore.properties").exists()) {
        val appTasks = allTasks.filter { it.project == project }
        val buildsApk = { t: org.gradle.api.Task ->
            t.name.startsWith("assemble") || t.name.startsWith("bundle") || t.name.startsWith("package")
        }
        val releaseTask = appTasks.firstOrNull { buildsApk(it) && it.name.contains("Release") }
        if (releaseTask != null) {
            throw GradleException(
                "keystore.properties is missing, so the release build would be signed with the DEBUG key. " +
                    "Create keystore.properties (storeFile, storePassword, keyAlias, keyPassword) and build again. " +
                    "A beta build (assembleBeta) does not need it."
            )
        }
        if (appTasks.any { buildsApk(it) && it.name.contains("Beta") }) {
            logger.warn("")
            logger.warn("==================================================================")
            logger.warn("WARNING: keystore.properties is missing.")
            logger.warn("This beta build is signed with the DEBUG key. Do not distribute it.")
            logger.warn("==================================================================")
            logger.warn("")
        }
    }
}
