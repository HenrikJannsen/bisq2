package bisq.gradle.toolchain_resolver

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

private const val TIMEOUT_MILLIS = 60_000

internal object JdkArchiveDownload {
    private val logger = Logging.getLogger(JdkArchiveDownload::class.java)

    // length is -1 when unknown. Gradle names its own copy after a Content-Disposition header, so one is refused.
    class Source(val stream: InputStream, val length: Long, val contentDisposition: String?)

    fun ensureVerified(uri: URI, archive: File, expectedSha256: String): File =
            ensureVerified(uri, archive, expectedSha256, ::openHttps)

    // The returned file is the archive, which then has the expected SHA-256; a mismatch fails the build.
    @Synchronized
    fun ensureVerified(uri: URI, archive: File, expectedSha256: String, open: (URI) -> Source): File {
        if (!archive.isFile) {
            download(uri, archive, expectedSha256, open)
        }
        verify(archive, expectedSha256)
        return archive
    }

    private fun download(uri: URI, archive: File, expectedSha256: String, open: (URI) -> Source) {
        Files.createDirectories(archive.parentFile.toPath())
        val partFile = Files.createTempFile(archive.parentFile.toPath(), archive.name + ".", ".part").toFile()
        try {
            logger.lifecycle("Downloading {} and checking its SHA-256", uri)
            val source = open(uri)
            source.stream.use { input ->
                if (source.contentDisposition != null) {
                    throw GradleException(
                            "The server sent a Content-Disposition header for the JDK archive $uri " +
                                    "('${source.contentDisposition}'). Gradle would name its copy after it and not use the verified file."
                    )
                }
                copyVerified(input, source.length, partFile, expectedSha256, uri.toString())
            }
            moveInto(partFile, archive)
        } finally {
            partFile.delete()
        }
    }

    private fun openHttps(uri: URI): Source {
        if (uri.scheme != "https") {
            throw GradleException("The JDK archive $uri must be downloaded over https.")
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw GradleException("Could not download the JDK archive $uri: HTTP $responseCode.")
        }
        return Source(connection.inputStream, connection.contentLengthLong, connection.getHeaderField("Content-Disposition"))
    }

    fun copyVerified(input: InputStream, expectedLength: Long, target: File, expectedSha256: String, source: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val copied = DigestInputStream(input, digest).use { digestInput ->
            Files.copy(digestInput, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (expectedLength >= 0 && copied != expectedLength) {
            target.delete()
            throw GradleException("The download of the JDK archive from $source is incomplete: $copied of $expectedLength bytes.")
        }
        val actualSha256 = HexFormat.of().formatHex(digest.digest())
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            target.delete()
            throw GradleException(
                    "The JDK archive downloaded from $source does not match the pinned SHA-256 and was discarded.\n" +
                            "expected: $expectedSha256\n" +
                            "actual:   $actualSha256"
            )
        }
    }

    fun verify(file: File, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            DigestInputStream(input, digest).use { digestInput ->
                digestInput.transferTo(OutputStream.nullOutputStream())
            }
        }
        val actualSha256 = HexFormat.of().formatHex(digest.digest())
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw GradleException(
                    "The JDK archive $file does not match the pinned SHA-256. Delete the file to download it again.\n" +
                            "expected: $expectedSha256\n" +
                            "actual:   $actualSha256"
            )
        }
    }

    private fun moveInto(partFile: File, archive: File) {
        try {
            Files.move(partFile.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Another process may have put the archive there meanwhile; the caller verifies what is there.
            if (!archive.isFile) {
                throw e
            }
        }
    }
}
