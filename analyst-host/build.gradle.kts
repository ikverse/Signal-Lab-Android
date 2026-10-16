import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The analyst host: a small program for the owner's PC that runs Claude Code, signed in with the owner's own account, for the
// phone. It is plain Kotlin on the JDK alone (its HTTP and HTTPS servers are the JDK's own) and has no Android in it.
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
    mainClass.set("com.ikverse.signallab.host.MainKt")
    applicationName = "signal-lab-host"
}

dependencies {
    implementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
