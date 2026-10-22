plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// One place decides the version; the code is derived from it so the two can never disagree.
val appVersionName = "2.3.0"
val appVersionCode = appVersionName.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

/** Whatever `local.properties` holds. Git ignores that file: it is where this machine's secrets live. */
val localProperties: Map<String, String> = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val text = line.trim()
        if (text.startsWith("#") || "=" !in text) return@mapNotNull null
        text.substringBefore("=").trim() to text.substringAfter("=").trim()
    }
    ?.toMap()
    .orEmpty()

/** A setting from `local.properties` on a developer machine, or the environment on CI. */
fun buildSetting(name: String): String? =
    (localProperties[name] ?: System.getenv(name))?.takeIf(String::isNotBlank)

/**
 * The key release builds are signed with. Android refuses an update signed by a different key than
 * the install it would replace, so every release must carry the same signature, and the key has to
 * outlive this machine: it lives in GitHub secrets, never in the repository. Absent, the release
 * build is simply unsigned, so a fresh checkout still builds and CI still runs the tests.
 */
val releaseKeystore = buildSetting("SIGNAL_LAB_KEYSTORE_FILE")
    ?.let(rootProject::file)
    ?.takeIf { it.exists() }
val releaseKeystorePassword = buildSetting("SIGNAL_LAB_KEYSTORE_PASSWORD")
val releaseKeyAlias = buildSetting("SIGNAL_LAB_KEY_ALIAS")

android {
    namespace = "com.ikverse.signallab"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ikverse.signallab"
        // Android 10: the lowest phone this app is tested on. Chosen over 8.0 because it is the
        // Note 9's own version and drops two releases' worth of workarounds.
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystorePassword != null && releaseKeyAlias != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = buildSetting("SIGNAL_LAB_KEY_PASSWORD") ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Null where the key is not configured, which leaves the APK unsigned rather than
            // failing the build. An unsigned APK cannot be installed, so nothing can mistake one
            // for a release.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // A sideloaded build should be obvious in Settings without checking a commit hash.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The Claude SDK's dependencies (Jackson, Apache HTTP pieces) each carry the same license and notice files.
        resources.excludes += "/META-INF/{DEPENDENCIES,LICENSE,LICENSE.txt,LICENSE.md,NOTICE,NOTICE.txt,NOTICE.md,INDEX.LIST}"
    }

    testOptions {
        // Robolectric reads the merged manifest and resources to stand up a context; without this
        // it starts with neither and every test that needs one fails on the same complaint.
        unitTests.isIncludeAndroidResources = true
        // The default test heap ran out partway through a run of database tests, and 2g ran out intermittently
        // once the suite passed 500 tests (the backtest and screen tests are the heavy ones).
        unitTests.all {
            it.maxHeapSize = "3g"
            // CI printed only the exception's class for a failure it alone had; show the message and the whole trace.
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

ksp {
    // Room writes each schema version here, so a later migration can be tested against the exact
    // schema an installed phone has.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.window)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // Google's authorization for the app's hidden Drive folder, used by sync between devices.
    implementation(libs.play.services.auth)

    // Signal Lab 2: the ledger database, the daily scan, screens and the foldable layouts.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    // The in-app analyst: Anthropic's official SDK for the Claude API, on the owner's own API key.
    implementation(libs.anthropic.java)

    // The engine's golden files (real candles and what the research version produced) and its test
    // helpers, shared without copying them.
    testImplementation(testFixtures(project(":engine")))
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Certificates for the tests of pairing with the analyst host on a PC.
    testImplementation(libs.okhttp.tls)
    // Plain-JVM tests (the Binance client) need a real org.json; Android's own is a stub there.
    // Robolectric tests bring Android's, and the two coexist.
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    // Gives a Compose test the empty activity it renders into.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
