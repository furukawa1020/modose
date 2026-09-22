import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The pinned model is downloaded and checked locally")
abstract class PrepareEmbedderModel : DefaultTask() {
    @get:Input abstract val modelUrl: Property<String>
    @get:Input abstract val expectedSha256: Property<String>
    @get:Input abstract val expectedBytes: Property<Int>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val size = expectedBytes.get()
        require(size > 0)
        val connection = URI(modelUrl.get()).toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val bytes = connection.getInputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count <= size - output.size()) { "Unexpected model size" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        check(bytes.size == size) { "Incomplete model download" }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == expectedSha256.get()) { "Embedding model SHA-256 mismatch" }
        val target = outputDirectory.get().file("models/mobilenet_v3_small.tflite").asFile
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}
