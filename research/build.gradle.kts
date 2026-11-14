import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The whole-account test (docs/strategy-research.md §8): the engine run over years of Binance history on a PC. It is not
// part of the app; the phone never runs it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
    }
}

application {
    mainClass.set("com.ikverse.signallab.research.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

// Run from the repository's root, so "research/data" is found.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

dependencies {
    implementation(project(":engine"))
}
