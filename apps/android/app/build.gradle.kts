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
