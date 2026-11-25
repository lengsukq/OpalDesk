import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

val resolvedSdkDir = (rootProject.extra["opalDeskAndroidSdkDir"] as? String).orEmpty()
val hasAndroid37Minor2 = resolvedSdkDir.isNotBlank() &&
    file("$resolvedSdkDir/platforms/android-37.2/android.jar").isFile
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) {
        f.inputStream().use { load(it) }
    }
}
fun resolveBuildSecret(propName: String, localKey: String, vararg envNames: String): String {
    val fromProp = project.findProperty(propName)?.toString()?.trim().orEmpty()
    if (fromProp.isNotEmpty()) return fromProp
    val fromLocal = localProps.getProperty(localKey)?.trim().orEmpty()
    if (fromLocal.isNotEmpty()) return fromLocal
    for (envName in envNames) {
        val fromEnv = System.getenv(envName)?.trim().orEmpty()
        if (fromEnv.isNotEmpty()) return fromEnv
    }
    return ""
}
fun escapeBuildConfigString(raw: String): String =
    "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val resolvedSentryDsn = resolveBuildSecret("sentryDsn", "opaldesk.sentry.dsn", "OPALDESK_SENTRY_DSN", "SENTRY_DSN")
val resolvedSentryTunnelUrl = resolveBuildSecret(
    "sentryTunnelUrl",
    "opaldesk.sentry.tunnelUrl",
    "OPALDESK_SENTRY_TUNNEL_URL",
    "SENTRY_TUNNEL_URL",
)

@Suppress("UnstableApiUsage")
android {
    namespace = "io.github.lengsukq.opaldesk"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = if (hasAndroid37Minor2) 2 else 0
        }
    }

    defaultConfig {
        applicationId = "io.github.lengsukq.opaldesk"
        minSdk = 30
        targetSdk = 37
        versionCode = 10014
        versionName = "1.0.14"
        buildConfigField("String", "SENTRY_DSN", escapeBuildConfigString(resolvedSentryDsn))
        buildConfigField("String", "SENTRY_TUNNEL_URL", escapeBuildConfigString(resolvedSentryTunnelUrl))
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.clear()
            java.directories.add("src")
            kotlin.directories.clear()
            kotlin.directories.add("src")
            res.directories.clear()
            res.directories.add("res")
            jniLibs.directories.clear()
            jniLibs.directories.add("jniLibs")
            assets.directories.clear()
            assets.directories.add("assets")
        }
        named("test") {
            java.directories.clear()
            java.directories.add("test")
            kotlin.directories.clear()
            kotlin.directories.add("test")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "jar"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    val activeKsPath = (rootProject.extra["opalDeskSigningKeystorePath"] as? String).orEmpty()
    val activeKs = activeKsPath.takeIf { it.isNotBlank() }?.let { file(it) }
    val signingPass = (rootProject.extra["opalDeskSigningPassword"] as? String).orEmpty()
    val signingAlias = (rootProject.extra["opalDeskSigningAlias"] as? String).orEmpty().ifBlank { "opaldesk" }

    val requireReleaseKey = (System.getenv("OPALDESK_REQUIRE_RELEASE_KEY") == "1")
        || (project.findProperty("requireReleaseKey")?.toString()?.equals("true", ignoreCase = true) == true)
        || (System.getenv("GITHUB_REF")?.startsWith("refs/tags/v") == true)
    val allowUnsignedRelease = !requireReleaseKey && (
        (System.getenv("OPALDESK_ALLOW_UNSIGNED_RELEASE") == "1")
            || (project.findProperty("allowUnsignedRelease")?.toString()?.equals("true", ignoreCase = true) == true)
        )
    val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

    if ((requireReleaseKey || (isReleaseTaskRequested && !allowUnsignedRelease)) && activeKs == null) {
        throw GradleException(
            "Release build requires OPALDESK_KEYSTORE (or local opaldesk.p12). " +
                "Run ./scripts/init-signing.sh to generate a local signing certificate, or for keyless CI verification " +
                "pass -PallowUnsignedRelease=true (or OPALDESK_ALLOW_UNSIGNED_RELEASE=1)."
        )
    }

    signingConfigs {
        if (activeKs != null) {
            create("extrelease") {
                storeFile = activeKs
                storeType = "PKCS12"
                storePassword = signingPass
                keyAlias = signingAlias
                keyPassword = signingPass
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
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
            // Never fall back to debug keystore for release builds: if no release keystore is configured
            // (e.g. keyless CI verification), emit an unsigned APK instead of a debug-signed APK.
            signingConfig = if (activeKs != null) {
                signingConfigs.getByName("extrelease")
            } else {
                null
            }
        }
        debug {
            // If a release keystore is configured, sign debug with it too so upgrade/daemon trust stays unified.
            // Otherwise AGP uses standard ~/.android/debug.keystore, which :bridge:generateBridgeBuildConfig auto-matches.
            if (activeKs != null) {
                signingConfig = signingConfigs.getByName("extrelease")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("androidx.window:window:1.5.0")
    implementation("androidx.window:window-core:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.4")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("io.sentry:sentry-android:8.64.0")

    testImplementation("junit:junit:4.13.2")
}

val syncBridgeDaemonAsset = tasks.register<Copy>("syncBridgeDaemonAsset") {
    group = "build"
    description = "Builds :bridge:dexBridgeJar and copies opaldesk.jar into helper/assets/."
    val dexTask = project(":bridge").tasks.named("dexBridgeJar")
    dependsOn(dexTask)
    from(project(":bridge").layout.buildDirectory.file("libs/opaldesk.jar"))
    into(layout.projectDirectory.dir("assets"))
    rename { "opaldesk.jar" }
}

val helperAssetsJar = layout.projectDirectory.file("assets/opaldesk.jar")

tasks.named("preBuild") {
    dependsOn(syncBridgeDaemonAsset)
    doFirst {
        val jar = helperAssetsJar.asFile
        if (!jar.exists() || jar.length() < 4096L) {
            throw GradleException(
                "Missing or empty helper/assets/opaldesk.jar after :helper:syncBridgeDaemonAsset!"
            )
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(syncBridgeDaemonAsset)
}
