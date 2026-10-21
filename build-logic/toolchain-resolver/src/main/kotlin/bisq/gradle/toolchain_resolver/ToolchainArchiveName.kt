package bisq.gradle.toolchain_resolver

import org.gradle.api.GradleException
import org.gradle.util.GradleVersion

// The name under which Gradle keeps a provisioned toolchain archive in <gradle user home>/jdks. Gradle skips its own
// download when that file exists, which is what lets the resolver put a verified archive there first. Gradle 8
// (verified with 8.9) uses the file name the server reports. Gradle 9 (verified with 9.0.0) appends the vendor and
// the language version of the toolchain spec, see buildFileNameWithDetails in its JavaToolchainProvisioningService.
// A Gradle this object does not know fails the build, so that a changed naming cannot silently skip the check.
internal object ToolchainArchiveName {
    fun of(gradleVersion: GradleVersion, serverFileName: String, vendorDescription: String, languageVersion: Int): String {
        val major = gradleVersion.baseVersion.version.substringBefore('.').toInt()
        return when (major) {
            8 -> serverFileName
            9 -> addSuffixToName(serverFileName, toSafeFileName("-$vendorDescription-$languageVersion"))
            else -> throw GradleException(
                    "BisqToolchainResolver does not know how Gradle ${gradleVersion.version} names a provisioned " +
                            "toolchain archive. Check its JavaToolchainProvisioningService and extend ToolchainArchiveName."
            )
        }
    }

    // org.gradle.internal.FileUtils.addSuffixToName of Gradle 9.0.0: the suffix goes before the first dot.
    private fun addSuffixToName(fileName: String, suffix: String): String {
        val dotIndex = fileName.indexOf('.')
        return if (dotIndex > 0) {
            fileName.substring(0, dotIndex) + suffix + fileName.substring(dotIndex)
        } else {
            fileName + suffix
        }
    }

    // org.gradle.internal.FileUtils.toSafeFileName of Gradle 9.0.0.
    private fun toSafeFileName(name: String): String {
        val safe = StringBuilder(name.length * 2)
        for (c in name) {
            val valid = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '$'
            if (valid) {
                safe.append(c)
            } else {
                safe.append('#').append(Integer.toHexString(c.code))
            }
        }
        return safe.toString()
    }
}
