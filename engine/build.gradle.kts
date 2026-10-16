import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // Test helpers and the golden data live in the testFixtures source set, so the app's tests use
    // the same ones instead of reaching into this module's folders by path.
    `java-test-fixtures`
}

// The engine is plain Kotlin with no Android in it. That is what lets its tests run on a PC in
// seconds, and it is enforced by the build: this module cannot see the Android SDK.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // Warnings are where a wrong comparison or a lossy conversion shows up first.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    // Reads the golden files exported from the research version.
    testImplementation(libs.json)
    // The fixtures' helpers take and return org.json types, so their users need it too.
    testFixturesApi(libs.json)
}

tasks.test {
    // The golden-data tests read a few thousand candles; give them room rather than flake.
    maxHeapSize = "1g"
}
