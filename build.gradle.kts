import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("maven-publish")
}

val sdkVersion: String = providers.gradleProperty("sdk.version").get()

group = "com.github.CTV-House"
version = sdkVersion

android {
    namespace = "com.ctvhouse.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION_NAME", "\"$sdkVersion\"")
        resourcePrefix = "ctv_sdk_"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        // Real default methods in the bytecode, so a Java host overrides only the
        // listener callbacks it cares about instead of the whole catalog.
        freeCompilerArgs += "-Xjvm-default=all"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Failures are logged unconditionally, so plain JVM tests hit android.util.Log.
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Host must provide Media3 at runtime (any 1.x).
    compileOnly("androidx.media3:media3-common:1.3.1")
    compileOnly("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("androidx.media3:media3-common:1.3.1")
    testImplementation("androidx.media3:media3-exoplayer:1.3.1")
    // XmlPullParser implementation for JVM tests only. On device it comes from the platform,
    // and shipping it would put duplicate org.xmlpull classes into every host app.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // Real View/Handler behaviour for the overlay chrome tests.
    testImplementation("org.robolectric:robolectric:4.12.2")
}

/**
 * The artifact must be inert until the host calls it: nothing merged into the host manifest, and
 * no third-party classes that could collide with the host's own copies (Media3 stays `compileOnly`,
 * the XML parser comes from the platform). Cheap enough to keep in CI ahead of every publish.
 */
val verifyReleaseAar by tasks.registering {
    dependsOn("assembleRelease", "generatePomFileForReleasePublication")
    val aarFile = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")
    val pomFile = layout.buildDirectory.file("publications/release/pom-default.xml")
    outputs.upToDateWhen { false }
    doLast {
        val aar = aarFile.get().asFile
        val problems = mutableListOf<String>()
        var declaredPermissions = emptySet<String>()
        ZipFile(aar).use { zip ->
            val manifest = zip.getEntry("AndroidManifest.xml")
                ?: error("no AndroidManifest.xml in ${aar.name}")
            val text = zip.getInputStream(manifest).bufferedReader().readText()
            val components = listOf("<application", "<activity", "<service", "<receiver", "<provider")
            components.filter { text.contains(it) }.forEach {
                problems += "manifest declares $it — it would be merged into every host app"
            }
            // Install-time permissions the library needs; anything else has to be argued for
            // before it lands in every host manifest.
            val allowedPermissions = setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "com.google.android.gms.permission.AD_ID",
            )
            declaredPermissions = Regex("""uses-permission android:name="([^"]+)"""")
                .findAll(text)
                .map { it.groupValues[1] }
                .toSet()
            val declared = declaredPermissions
            (declared - allowedPermissions).forEach {
                problems += "manifest asks the host for $it"
            }
            (allowedPermissions - declared).forEach {
                problems += "manifest lost $it — the feature behind it would fail on device"
            }
            val classes = zip.getEntry("classes.jar") ?: error("no classes.jar in ${aar.name}")
            val bytes = zip.getInputStream(classes).readBytes()
            ZipInputStream(bytes.inputStream()).use { jar ->
                while (true) {
                    val entry = jar.nextEntry ?: break
                    val name = entry.name
                    if (name.endsWith(".class") && !name.startsWith("com/ctvhouse/")) {
                        problems += "classes.jar bundles $name"
                    }
                }
            }
        }
        // A published Media3 dependency would fight the host for the version it already ships.
        val pom = pomFile.get().asFile
        if (pom.readText().contains("androidx.media3")) {
            problems += "pom-default.xml depends on Media3 — it must stay compileOnly"
        }
        if (problems.isNotEmpty()) {
            error(
                problems.joinToString(
                    prefix = "${aar.name} is not host-safe:\n  - ",
                    separator = "\n  - ",
                ),
            )
        }
        logger.lifecycle(
            "${aar.name}: no components, ${declaredPermissions.size} install-time permissions, " +
                "only com.ctvhouse classes, no Media3 in the pom",
        )
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.CTV-House"
                // Local / composite coordinate. JitPack from repo android-sdk →
                // com.github.CTV-House:android-sdk:<tag>
                artifactId = "sdk"
                version = sdkVersion

                pom {
                    name.set("CTV House Android SDK")
                    description.set(
                        "Ad formats over a Media3 content player for Android and Android TV.",
                    )
                    url.set("https://github.com/CTV-House/android-sdk")
                    licenses {
                        license {
                            name.set("CTV House Proprietary License")
                            url.set(
                                "https://github.com/CTV-House/android-sdk/blob/main/LICENSE",
                            )
                            distribution.set("repo")
                            comments.set(
                                "Non-commercial use only; commercial use is reserved to " +
                                    "the copyright holder.",
                            )
                        }
                    }
                    scm {
                        url.set("https://github.com/CTV-House/android-sdk")
                        connection.set(
                            "scm:git:https://github.com/CTV-House/android-sdk.git",
                        )
                    }
                }
            }
        }
    }
}
