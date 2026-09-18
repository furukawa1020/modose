plugins {
    kotlin("jvm") version "2.3.21"
}

kotlin {
    jvmToolchain(17)
    sourceSets.named("main") {
        kotlin.srcDir("../../apps/android/app/src/main/java/com/modose/app/core")
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
