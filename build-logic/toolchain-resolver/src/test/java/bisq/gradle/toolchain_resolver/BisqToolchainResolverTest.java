package bisq.gradle.toolchain_resolver;

import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainDownload;
import org.gradle.jvm.toolchain.JavaToolchainRequest;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.jvm.toolchain.JvmVendorSpec;
import org.gradle.platform.Architecture;
import org.gradle.platform.BuildPlatform;
import org.gradle.platform.OperatingSystem;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BisqToolchainResolverTest {
    private static final String LINUX_ARCHIVE = "zulu21.54.107-ca-jdk21.0.13-linux_x64.zip";
    private static final String LINUX_ARCHIVE_IN_CACHE = inCache(LINUX_ARCHIVE);

    @Test
    void aCachedArchiveIsVerifiedBeforeTheDownloadIsReturned(@TempDir File directory) throws Exception {
        BisqToolchainResolver.Companion.setToolchainDownloadDirectory(directory);
        Files.write(new File(directory, LINUX_ARCHIVE_IN_CACHE).toPath(), "not the jdk".getBytes(StandardCharsets.UTF_8));
        BisqToolchainResolver resolver = newResolver();

        GradleException exception = assertThrows(GradleException.class,
                () -> resolver.resolve(requestFor(OperatingSystem.LINUX, 21)));

        assertTrue(exception.getMessage().contains("does not match the pinned SHA-256"), exception.getMessage());
        assertTrue(exception.getMessage().contains(LINUX_ARCHIVE_IN_CACHE), exception.getMessage());
    }

    @Test
    void anUnpinnedJavaVersionYieldsNoDownload(@TempDir File directory) {
        BisqToolchainResolver.Companion.setToolchainDownloadDirectory(directory);
        BisqToolchainResolver resolver = newResolver();

        Optional<JavaToolchainDownload> download = resolver.resolve(requestFor(OperatingSystem.LINUX, 17));

        assertTrue(download.isEmpty());
        assertEquals(0, directory.list().length);
    }

    // The name under which the running Gradle keeps the archive; ToolchainArchiveNameTest pins it per Gradle version.
    private static String inCache(String serverFileName) {
        return ToolchainArchiveName.INSTANCE.of(GradleVersion.current(), serverFileName, JvmVendorSpec.AZUL.toString(), 21);
    }

    private static BisqToolchainResolver newResolver() {
        return new BisqToolchainResolver() {
            @Override
            public BuildServiceParameters.None getParameters() {
                return null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static JavaToolchainRequest requestFor(OperatingSystem operatingSystem, int javaVersion) {
        BuildPlatform platform = mock(BuildPlatform.class);
        when(platform.getOperatingSystem()).thenReturn(operatingSystem);
        when(platform.getArchitecture()).thenReturn(Architecture.X86_64);
        Property<JavaLanguageVersion> languageVersion = mock(Property.class);
        when(languageVersion.get()).thenReturn(JavaLanguageVersion.of(javaVersion));
        Property<JvmVendorSpec> vendor = mock(Property.class);
        when(vendor.get()).thenReturn(JvmVendorSpec.AZUL);
        JavaToolchainSpec spec = mock(JavaToolchainSpec.class);
        when(spec.getLanguageVersion()).thenReturn(languageVersion);
        when(spec.getVendor()).thenReturn(vendor);
        JavaToolchainRequest request = mock(JavaToolchainRequest.class);
        when(request.getBuildPlatform()).thenReturn(platform);
        when(request.getJavaToolchainSpec()).thenReturn(spec);
        return request;
    }
}
