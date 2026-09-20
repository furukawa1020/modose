plugins {
    kotlin("jvm") version "2.3.21"
}

kotlin {
    jvmToolchain(17)
    sourceSets.named("main") {
        kotlin.srcDir("../../apps/android/app/src/main/java")
        kotlin.include("com/modose/app/core/**")
        kotlin.include("com/modose/app/vision/tracking/ImageDetectionContract.kt")
        kotlin.include("com/modose/app/vision/tracking/ImageGuidanceBridge.kt")
        kotlin.include("com/modose/app/vision/tracking/MeasuredAppearance.kt")
        kotlin.include("com/modose/app/vision/tracking/MeasuredGuidanceEvidenceSource.kt")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    maxParallelForks = 1
    systemProperty("java.library.path", providers.gradleProperty("nativeLibraryDir").get())
    testLogging {
        events("passed", "skipped", "failed")
    }
}
