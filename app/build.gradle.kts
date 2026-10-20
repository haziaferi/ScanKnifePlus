import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The shared keystore stays out of git: keystore.properties (see keystore.properties.example) signs debug and release alike, so either
// installs over the other. Without it, debug uses the default debug key and release is left unsigned.
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "com.paperknifeplus.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.haziaferi.scanknifeplus"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val shared = keystoreProperties?.let { props ->
        fun prop(key: String): String = requireNotNull(props.getProperty(key)?.takeIf { it.isNotBlank() }) { "keystore.properties has no $key" }
        signingConfigs.create("shared") {
            storeFile = rootProject.file(prop("storeFile"))
            storePassword = prop("storePassword")
            keyAlias = prop("keyAlias")
            keyPassword = prop("keyPassword")
        }
    }

    buildTypes {
        debug {
            if (shared != null) signingConfig = shared
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = shared
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/*.txt"
            excludes += "/com/tom_roush/pdfbox/resources/version.properties"
            excludes += "/*.properties"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/proguard/*"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Robolectric needs the merged manifest and resources to resolve the FileProvider paths.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    implementation(project(":scanner-core"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.pdfbox.android)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.rules)
    // OCR benchmark only (OcrBenchmarkTest); no OCR ships to users yet.
    androidTestImplementation(libs.tesseract4android.openmp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
