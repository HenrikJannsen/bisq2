package bisq.gradle.toolchain_resolver;

import org.gradle.api.GradleException;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolchainArchiveNameTest {
    private static final ToolchainArchiveName NAME = ToolchainArchiveName.INSTANCE;
    private static final String ZIP = "zulu21.54.107-ca-jdk21.0.13-linux_x64.zip";
    private static final String TAR_GZ = "zulu21.54.107-ca-jdk21.0.13-macosx_aarch64.tar.gz";

    @Test
    void gradle8KeepsTheServerFileName() {
        assertEquals(ZIP, NAME.of(GradleVersion.version("8.9"), ZIP, "Azul Zulu", 21));
    }

    // Observed with Gradle 9.0.0: Azul Zulu 21.0.11 was stored as zulu21-Azul#20Zulu-21.50.19-ca-jdk21.0.11-linux_x64.zip.
    @Test
    void gradle9AppendsVendorAndVersionBeforeTheFirstDot() {
        assertEquals("zulu21-Azul#20Zulu-21.54.107-ca-jdk21.0.13-linux_x64.zip",
                NAME.of(GradleVersion.version("9.0.0"), ZIP, "Azul Zulu", 21));
        assertEquals("zulu21-Azul#20Zulu-21.54.107-ca-jdk21.0.13-macosx_aarch64.tar.gz",
                NAME.of(GradleVersion.version("9.0.0"), TAR_GZ, "Azul Zulu", 21));
    }

    // Observed with Gradle 9.0.0 for a spec without a vendor, as the Tor build declares it.
    @Test
    void aSpecWithoutAVendorIsNamedAfterAnyVendor() {
        assertEquals("zulu21-any#20vendor-21.54.107-ca-jdk21.0.13-linux_x64.zip",
                NAME.of(GradleVersion.version("9.0.0"), ZIP, "any vendor", 21));
    }

    @Test
    void charactersOutsideTheSafeSetAreHexEncoded() {
        assertEquals("jdk-vendor#20matching#28#27x#27#29-21.zip",
                NAME.of(GradleVersion.version("9.0.0"), "jdk.zip", "vendor matching('x')", 21));
    }

    @Test
    void aNameWithoutADotGetsTheSuffixAppended() {
        assertEquals("jdk-Azul#20Zulu-21", NAME.of(GradleVersion.version("9.0.0"), "jdk", "Azul Zulu", 21));
    }

    @Test
    void anUncheckedGradleFailsInsteadOfGuessing() {
        for (String version : new String[]{"8.14.3", "9.0.1", "9.2.1", "10.0"}) {
            GradleException exception = assertThrows(GradleException.class,
                    () -> NAME.of(GradleVersion.version(version), ZIP, "Azul Zulu", 21));

            assertTrue(exception.getMessage().contains(version), exception.getMessage());
        }
    }
}
