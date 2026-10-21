package bisq.gradle.toolchain_resolver

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.jvm.toolchain.*
import java.io.File
import javax.inject.Inject

@Suppress("UnstableApiUsage")
class ToolchainResolverPlugin @Inject constructor(private val toolchainResolverRegistry: JavaToolchainResolverRegistry) : Plugin<Settings> {

    override fun apply(settings: Settings) {
        settings.plugins.apply("jvm-toolchain-management")
        BisqToolchainResolver.toolchainDownloadDirectory = File(settings.gradle.gradleUserHomeDir, "jdks")
        toolchainResolverRegistry.register(BisqToolchainResolver::class.java)
    }
}
