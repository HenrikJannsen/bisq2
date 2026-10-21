package bisq.gradle.toolchain_resolver

import org.gradle.jvm.toolchain.JavaToolchainDownload
import org.gradle.jvm.toolchain.JavaToolchainRequest
import org.gradle.jvm.toolchain.JavaToolchainResolver
import org.gradle.platform.Architecture
import org.gradle.platform.OperatingSystem
import org.gradle.util.GradleVersion
import java.io.File
import java.net.URI
import java.util.*

/**
 * Resolves the Zulu JDK of the requested platform. Azul builds one JDK per architecture, and a JDK of another
 * architecture does not run, so the resolver returns nothing for a platform without a known build. Gradle then
 * reports that no toolchain can be provisioned, instead of installing a JDK which cannot start.
 */
// Gradle downloads a toolchain archive into <gradle user home>/jdks and skips the download when the file is already
// there. The resolver puts the archive there itself, under the name Gradle uses (ToolchainArchiveName), and keeps it
// only when its SHA-256 matches the pin, so a modified archive on the download server fails the build instead of
// being unpacked. Gradle still asks the server for the file name with a request of its own; a server that answers
// that request with another name makes Gradle download an unchecked copy.
@Suppress("UnstableApiUsage")
abstract class BisqToolchainResolver : JavaToolchainResolver {
    companion object {
        // Set by ToolchainResolverPlugin before the resolver is registered.
        @Volatile
        lateinit var toolchainDownloadDirectory: File
    }

    override fun resolve(toolchainRequest: JavaToolchainRequest): Optional<JavaToolchainDownload> {
        val operatingSystem = toolchainRequest.buildPlatform.operatingSystem
        val architecture = toolchainRequest.buildPlatform.architecture
        val javaVersion = toolchainRequest.javaToolchainSpec.languageVersion.get().asInt()
        val archive: JdkArchive = when (operatingSystem) {
            OperatingSystem.LINUX -> getArchiveForLinux(javaVersion, architecture)
            OperatingSystem.MAC_OS -> getArchiveForMacOs(javaVersion, architecture)
            OperatingSystem.WINDOWS -> getArchiveForWindows(javaVersion, architecture)
            else -> null

        } ?: return Optional.empty()

        val uri = URI(archive.url)
        val fileName = ToolchainArchiveName.of(
                GradleVersion.current(),
                uri.path.substringAfterLast('/'),
                toolchainRequest.javaToolchainSpec.vendor.get().toString(),
                javaVersion
        )
        JdkArchiveDownload.ensureVerified(uri, File(toolchainDownloadDirectory, fileName), archive.sha256)
        return Optional.of(
                JavaToolchainDownload.fromUri(uri)
        )
    }

    private fun getArchiveForLinux(javaVersion: Int, architecture: Architecture): JdkArchive? =
            when (architecture) {
                Architecture.X86_64 -> when (javaVersion) {
                    21 -> JdkArchive(
                            "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jdk21.0.13-linux_x64.zip",
                            "1f0eaaf5604af472a8293def42fbc27c19666839f270da9e8b5a26491e019bd8"
                    )
                    else -> null
                }
                Architecture.AARCH64 -> when (javaVersion) {
                    21 -> JdkArchive(
                            "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jdk21.0.13-linux_aarch64.tar.gz",
                            "783460592bc66c9290b14c32a537b29da812bd62ad53bf6cb565355bca7ea563"
                    )
                    else -> null
                }
                else -> null
            }

    private fun getArchiveForMacOs(javaVersion: Int, architecture: Architecture): JdkArchive? =
            when (architecture) {
                Architecture.AARCH64 -> when (javaVersion) {
                    21 -> JdkArchive(
                            "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jdk21.0.13-macosx_aarch64.tar.gz",
                            "7682fcbbc3a164352d36c09c96b2aa91841c8bbafdb9a9a7f5f04db6d290a624"
                    )
                    else -> null
                }
                Architecture.X86_64 -> when (javaVersion) {
                    21 -> JdkArchive(
                            "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jdk21.0.13-macosx_x64.tar.gz",
                            "a6211ef2de128e72519556ea24c7ecb316ec5589b6ed705aef81c889b4233581"
                    )
                    else -> null
                }
                else -> null
            }

    private fun getArchiveForWindows(javaVersion: Int, architecture: Architecture): JdkArchive? =
            when (architecture) {
                Architecture.X86_64 -> when (javaVersion) {
                    21 -> JdkArchive(
                            "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jdk21.0.13-win_x64.zip",
                            "69e9fd7afafe0d70235884f8d9ab15cf8a4b43215b27900d710841e1fb3c7126"
                    )
                    else -> null
                }
                else -> null
            }

    private class JdkArchive(val url: String, val sha256: String)
}
