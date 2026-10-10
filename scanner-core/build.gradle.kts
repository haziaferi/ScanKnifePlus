// Pure-Kotlin document scanner core (no Android dependencies), ported from OpenScan. See NOTICE.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Links against the JDK 17 API so JDK 18+ calls such as List.removeFirst cannot slip into the parity-tested core.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    testImplementation(libs.junit)
}
