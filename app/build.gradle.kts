import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files

plugins {
    // Brings Kotlin with it: AGP 9 has built-in Kotlin support, so
    // `org.jetbrains.kotlin.android` must NOT be applied alongside it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Powers Navigation Compose's type-safe routes: destinations are @Serializable
    // classes, so a wrong argument is a compile error rather than a crash on a
    // malformed route string.
    alias(libs.plugins.kotlin.serialization)
    // Reads app/google-services.json and generates the Firebase config resources.
    // It matches the file's `package_name` against this module's applicationId and
    // fails the build outright when there is no match - see the note on
    // applicationIdSuffix below.
    alias(libs.plugins.google.services)
}

// This is a public backend address, never an API key. CI or a local build can
// select the Cloudflare endpoint with -PsessionAiUrl=https://... or SESSION_AI_URL.
val sessionAiUrl = providers.gradleProperty("sessionAiUrl")
    .orElse(providers.environmentVariable("SESSION_AI_URL"))
    .orElse("")
    .get().trim()

// Keep clean checkouts from silently producing an APK without live pose tracking.
abstract class VerifyPoseModel : DefaultTask() {
    @get:org.gradle.api.tasks.Internal abstract val model: org.gradle.api.file.RegularFileProperty
    @get:org.gradle.api.tasks.Internal abstract val temporaryDirectory: org.gradle.api.file.DirectoryProperty
    @get:org.gradle.api.tasks.Input abstract val offline: org.gradle.api.provider.Property<Boolean>

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val expected = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
        val source = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task"
        val target = model.get().asFile
        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!target.isFile) {
            check(!offline.get()) { "The pose model is missing. Run an online build once to download it, then offline builds will work." }
            val directory = temporaryDirectory.get().asFile.toPath()
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "pose-landmarker-", ".part")
            try {
                val connection = URI(source).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 60_000 }
                connection.getInputStream().use { input -> Files.newOutputStream(temporary).use { input.copyTo(it) } }
                check(sha256(temporary.toFile()) == expected) { "The downloaded pose model checksum did not match." }
                Files.move(temporary, target.toPath())
            } finally { Files.deleteIfExists(temporary) }
        }
        check(sha256(target) == expected) { "The pose model checksum did not match. Restore the official full model before building." }
    }
}
val verifyPoseModel = tasks.register<VerifyPoseModel>("verifyPoseModel") {
    model.set(layout.projectDirectory.file("src/main/assets/pose_landmarker_full.task"))
    temporaryDirectory.set(layout.buildDirectory.dir("pose-model-download"))
    offline.set(gradle.startParameter.isOffline)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") && !it.name.contains("AndroidTest") }
    .configureEach { dependsOn(verifyPoseModel) }
if (sessionAiUrl.isNotEmpty()) {
    val endpoint = URI(sessionAiUrl)
    require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() &&
        endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) {
        "sessionAiUrl must be a public HTTPS endpoint without credentials, query, or fragment."
    }
}

android {
    namespace = "com.volleyballlab.player"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.volleyballlab.player"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 15
        versionName = "0.1.14"
        buildConfigField("String", "SESSION_AI_URL", "\"${sessionAiUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // No applicationIdSuffix, deliberately. google-services.json registers
            // exactly one package, com.volleyballlab.player, and the Google
            // Services plugin fails the build with "No matching client found for
            // package name" the moment the debug applicationId stops matching it.
            //
            // To get the suffix back - which is worth doing, since it lets a debug
            // and a release build sit on one phone at once - register a second
            // Android app in the Firebase console for
            // com.volleyballlab.player.debug and re-download the json.
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Optimized sideload builds use the existing development certificate and
        // App Check provider, so they can update test phones without losing data.
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-preview"
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // MediaPipe memory-maps the .task model straight out of the APK. If aapt
        // compresses it the mapping fails at runtime with a confusing
        // "Model provided has model identifier ..." error.
        noCompress += listOf("task", "tflite")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    add("previewImplementation", libs.firebase.appcheck.debug)
    // Coach cloud: the shared roster/assignment/review backend also served by
    // the coach web portal. See docs/coach-cloud-contract.md.
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-functions")
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity.googleid)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.litert)

    implementation(libs.rive.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
