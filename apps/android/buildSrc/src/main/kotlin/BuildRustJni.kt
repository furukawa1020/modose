import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Uses locally installed Rust and NDK toolchains")
abstract class BuildRustJni @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val abi: Property<String>
    @get:Input abstract val rustTarget: Property<String>
    @get:Input abstract val clangTarget: Property<String>
    @get:Input abstract val apiLevel: Property<Int>
    @get:Input abstract val ndkVersion: Property<String>
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val workspace: DirectoryProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:LocalState abstract val cargoTargetDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val ndk = ndkDirectory.get().asFile
        val properties = Properties().apply {
            ndk.resolve("source.properties").inputStream().use { load(it) }
        }
        check(properties.getProperty("Pkg.Revision") == ndkVersion.get()) {
            "Installed NDK does not match the pinned revision"
        }
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val host = when {
            os.contains("windows") -> "windows-x86_64"
            os.contains("mac") -> "darwin-x86_64"
            os.contains("linux") -> "linux-x86_64"
            else -> error("Unsupported NDK build host")
        }
        val clang = ndk.resolve("toolchains/llvm/prebuilt/$host/bin/clang" +
            if (os.contains("windows")) ".exe" else "")
        check(clang.isFile) { "NDK clang is missing" }
        val target = rustTarget.get()
        val targetDirectory = cargoTargetDirectory.get().asFile
        val flags = listOf(
            "-C", "link-arg=--target=${clangTarget.get()}${apiLevel.get()}",
            "-C", "link-arg=-Wl,-z,max-page-size=16384",
            "-C", "link-arg=-Wl,-z,common-page-size=16384",
        ).joinToString("\u001f")
        execOperations.exec {
            workingDir(workspace.get().asFile)
            commandLine("cargo", "build", "--locked", "--release",
                "--target", target, "-p", "scene-core-jni")
            environment("CARGO_TARGET_DIR", targetDirectory.absolutePath)
            environment("CARGO_TARGET_${target.uppercase(Locale.ROOT).replace('-', '_')}_LINKER",
                clang.absolutePath)
            environment("CARGO_ENCODED_RUSTFLAGS", flags)
        }.assertNormalExitValue()
        val library = targetDirectory.resolve("$target/release/libscene_core_jni.so")
        check(library.isFile && library.length() > 0) { "Rust JNI output is missing" }
        val destination = outputDirectory.get().asFile.resolve("${abi.get()}/libscene_core_jni.so")
        check(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) {
            "Cannot create generated JNI directory"
        }
        library.copyTo(destination, overwrite = true)
    }
}
