import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
}

kotlin {
    jvmToolchain(21)
}

fun receiverSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { b -> "%02x".format(b) }

fun loadReceiverLocalProps(rootDir: File): Properties {
    val props = Properties()
    val f = File(rootDir, "local.properties")
    if (f.isFile) {
        f.inputStream().use { props.load(it) }
    }
    return props
}

val resolvedSdkDir = (rootProject.extra["opalDeskAndroidSdkDir"] as? String).orEmpty()
val hasAndroid37Minor2 = resolvedSdkDir.isNotBlank() &&
    file("$resolvedSdkDir/platforms/android-37.2/android.jar").isFile

val localProps = loadReceiverLocalProps(rootProject.projectDir)
val envReceiverKsPath = System.getenv("OPALCAST_KEYSTORE")?.takeIf { it.isNotBlank() }
val envReceiverKsFile = envReceiverKsPath?.let { rootProject.file(it) }
if (envReceiverKsFile != null && !envReceiverKsFile.exists()) {
    throw GradleException("OPALCAST_KEYSTORE points to non-existent file: ${envReceiverKsFile.absolutePath}")
}
val localReceiverP12 = rootProject.file("opalcast-receiver.p12")
val envReceiverPass = System.getenv("OPALCAST_KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
val propReceiverPass = localProps.getProperty("opalcast.keystore.password")?.trim()?.takeIf { it.isNotBlank() }
if (envReceiverKsFile != null && envReceiverPass == null) {
    throw GradleException("OPALCAST_KEYSTORE_PASS must be set when OPALCAST_KEYSTORE is provided.")
}
val receiverAlias = System.getenv("OPALCAST_KEYSTORE_ALIAS")?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty("opalcast.keystore.alias")?.trim()?.takeIf { it.isNotBlank() }
    ?: "opalcast"

val activeReceiverKs: File? = when {
    envReceiverKsFile != null -> envReceiverKsFile
    localReceiverP12.exists() -> localReceiverP12
    else -> null
}

val expectedReceiverCertSha256 = (
    project.findProperty("expectedReceiverCertSha256")?.toString()?.takeIf { it.isNotBlank() }
        ?: System.getenv("OPALCAST_EXPECTED_CERT_SHA256")?.takeIf { it.isNotBlank() }
    )?.trim()?.lowercase()
val allowCustomReceiverCert =
    project.findProperty("allowCustomReceiverCert")?.toString()?.equals("true", ignoreCase = true) == true
val allowUnsignedRelease =
    project.findProperty("allowUnsignedRelease")?.toString()?.equals("true", ignoreCase = true) == true

var receiverSigningPass = ""
var receiverCertSha256 = ""
if (activeReceiverKs != null) {
    val candidates = listOfNotNull(envReceiverPass, propReceiverPass, "opalcast").distinct()
    var unlocked: Pair<KeyStore, String>? = null
    var lastErr: Throwable? = null
    for (cand in candidates) {
        try {
            val ks = KeyStore.getInstance("PKCS12")
            activeReceiverKs.inputStream().use { ks.load(it, cand.toCharArray()) }
            if (ks.getKey(receiverAlias, cand.toCharArray()) != null) {
                unlocked = ks to cand
                break
            }
        } catch (t: Throwable) {
            lastErr = t
        }
    }
    val (ks, pass) = unlocked ?: throw GradleException(
        "Failed to unlock receiver keystore (${activeReceiverKs.name}) with configured password.",
        lastErr,
    )
    val cert = ks.getCertificate(receiverAlias)
        ?: throw GradleException("Alias '$receiverAlias' not found in ${activeReceiverKs.absolutePath}")
    val actualSha256 = receiverSha256Hex(cert.encoded)
    if (expectedReceiverCertSha256 != null && !allowCustomReceiverCert &&
        !actualSha256.equals(expectedReceiverCertSha256, ignoreCase = true)
    ) {
        throw GradleException(
            "OpalCast receiver keystore SHA-256 mismatch! Expected $expectedReceiverCertSha256, got $actualSha256",
        )
    }
    receiverSigningPass = pass
    receiverCertSha256 = actualSha256
} else if (!allowUnsignedRelease && System.getenv("OPALCAST_REQUIRE_RELEASE_KEY") == "1") {
    throw GradleException(
        "OpalCast receiver release signing keystore is required (OPALCAST_KEYSTORE or opalcast-receiver.p12).",
    )
}

@Suppress("UnstableApiUsage")
android {
    namespace = "io.github.lengsukq.opalcast"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = if (hasAndroid37Minor2) 2 else 0
        }
    }

    defaultConfig {
        applicationId = "io.github.lengsukq.opalcast"
        // Android 5.0 (API 21) through Android 17 (API 37), zero native .so libraries ->
        // 100% universal single APK across armeabi-v7a, arm64-v8a, x86, and x86_64.
        minSdk = 21
        targetSdk = 37
        versionCode = 10023
        versionName = "1.0.23"
        buildConfigField("String", "RECEIVER_CERT_SHA256", "\"$receiverCertSha256\"")
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
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        if (activeReceiverKs != null) {
            create("receiverRelease") {
                storeFile = activeReceiverKs
                storeType = "PKCS12"
                storePassword = receiverSigningPass
                keyAlias = receiverAlias
                keyPassword = receiverSigningPass
                // V1 required for Android 5.0–6.0 (API 21–23); V2/V3 for Android 7.0–17 (API 24–37).
                enableV1Signing = true
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
            signingConfig = if (activeReceiverKs != null) {
                signingConfigs.getByName("receiverRelease")
            } else {
                null
            }
        }
        debug {
            if (activeReceiverKs != null) {
                signingConfig = signingConfigs.getByName("receiverRelease")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
