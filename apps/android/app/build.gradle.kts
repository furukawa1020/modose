import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val rustNdkVersion = "30.0.16248370"

android {
    ndkVersion = rustNdkVersion
    namespace = "com.modose.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.modose.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("com.google.ar:core:1.54.0")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

val rustWorkspace = rootProject.layout.projectDirectory.dir("../..")
val rustAbis = listOf(
    Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android"),
    Triple("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi"),
    Triple("x86_64", "x86_64-linux-android", "x86_64-linux-android"),
    Triple("x86", "i686-linux-android", "i686-linux-android"),
)
val rustJniTasks = rustAbis.map { (androidAbi, rustTriple, clangTriple) ->
    tasks.register<BuildRustJni>("buildRustJni" + androidAbi.replace("-", "").replace("_", "")) {
        abi.set(androidAbi)
        rustTarget.set(rustTriple)
        clangTarget.set(clangTriple)
        apiLevel.set(29)
        ndkVersion.set(rustNdkVersion)
        workspace.set(rustWorkspace)
        ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
        sources.from(fileTree(rustWorkspace.dir("crates")) {
            include("**/*.rs", "**/Cargo.toml")
        })
        sources.from(rustWorkspace.file("Cargo.toml"), rustWorkspace.file("Cargo.lock"),
            rustWorkspace.file("rust-toolchain.toml"))
        sources.from(fileTree(rustWorkspace.dir(".cargo")))
        cargoTargetDirectory.set(layout.buildDirectory.dir("rust-targets/$androidAbi"))
        outputDirectory.set(layout.buildDirectory.dir("generated/rustJni/$androidAbi"))
    }
}
androidComponents.onVariants { variant ->
    rustJniTasks.forEach { task ->
        requireNotNull(variant.sources.jniLibs).addGeneratedSourceDirectory(task, BuildRustJni::outputDirectory)
    }
}

val embedderAssets = layout.buildDirectory.dir("generated/embedderAssets")
val prepareEmbedderModel by tasks.registering {
    val expectedSha = "bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40"
    val modelUrl = "https://storage.googleapis.com/mediapipe-models/image_embedder/" +
        "mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
    inputs.property("modelUrl", modelUrl)
    inputs.property("sha256", expectedSha)
    outputs.dir(embedderAssets)
    doLast {
        val connection = URI(modelUrl).toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val bytes = connection.getInputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 4_117_670) { "Unexpected model size" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        check(bytes.size == 4_117_670) { "Incomplete model download" }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == expectedSha) { "Embedding model SHA-256 mismatch" }
        val target = embedderAssets.get().file("models/mobilenet_v3_small.tflite").asFile
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}
android.sourceSets.getByName("main").assets.srcDir(embedderAssets.get().asFile)
tasks.named("preBuild").configure { dependsOn(prepareEmbedderModel) }

// Config directory is provided outside the repository; each variant supplies its own Firebase app.
providers.gradleProperty("modoseRuntimeConfigDir").orNull?.let { directory ->
    listOf("debug", "release").forEach { variant ->
        android.sourceSets.getByName(variant).assets.srcDir(file(directory).resolve(variant))
    }
}
