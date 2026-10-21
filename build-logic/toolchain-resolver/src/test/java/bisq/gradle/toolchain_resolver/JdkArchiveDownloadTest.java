package bisq.gradle.toolchain_resolver;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkArchiveDownloadTest {
    private static final byte[] CONTENT = "jdk".getBytes(StandardCharsets.UTF_8);
    private static final String CONTENT_SHA256 = "a81e02e119ce4422ad0d6692bf572e10760fa0e7fc8e6931e84d19cf07abc195";
    private static final String OTHER_SHA256 = "d9b0e640b6330a1e854a5acdc081273fa864f9e2e534b1225a0841015ababa4f";
    private static final URI ARCHIVE_URI = URI.create("https://example.invalid/zulu/bin/jdk.zip");
    private static final JdkArchiveDownload DOWNLOAD = JdkArchiveDownload.INSTANCE;

    @Test
    void aMatchingDownloadIsKept(@TempDir File directory) throws Exception {
        File target = new File(directory, "jdk.zip");

        DOWNLOAD.copyVerified(new ByteArrayInputStream(CONTENT), -1, target, CONTENT_SHA256, "test");

        assertArrayEquals(CONTENT, Files.readAllBytes(target.toPath()));
    }

    @Test
    void aDownloadWithAnotherHashIsDiscarded(@TempDir File directory) {
        File target = new File(directory, "jdk.zip");

        GradleException exception = assertThrows(GradleException.class,
                () -> DOWNLOAD.copyVerified(new ByteArrayInputStream(CONTENT), -1, target, OTHER_SHA256, "test"));

        assertTrue(exception.getMessage().contains("expected: " + OTHER_SHA256), exception.getMessage());
        assertTrue(exception.getMessage().contains("actual:   " + CONTENT_SHA256), exception.getMessage());
        assertFalse(target.exists());
    }

    @Test
    void anIncompleteDownloadIsDiscarded(@TempDir File directory) {
        File target = new File(directory, "jdk.zip");

        GradleException exception = assertThrows(GradleException.class,
                () -> DOWNLOAD.copyVerified(new ByteArrayInputStream(CONTENT), CONTENT.length + 1, target, CONTENT_SHA256, "test"));

        assertTrue(exception.getMessage().contains("incomplete: 3 of 4 bytes"), exception.getMessage());
        assertFalse(target.exists());
    }

    @Test
    void aDownloadLeavesOnlyTheArchiveBehind(@TempDir File directory) throws Exception {
        File archive = DOWNLOAD.ensureVerified(ARCHIVE_URI, new File(directory, "jdk.zip"), CONTENT_SHA256,
                uri -> new JdkArchiveDownload.Source(new ByteArrayInputStream(CONTENT), CONTENT.length, null));

        assertEquals(new File(directory, "jdk.zip"), archive);
        assertArrayEquals(CONTENT, Files.readAllBytes(archive.toPath()));
        assertEquals(List.of("jdk.zip"), List.of(directory.list()));
    }

    @Test
    void aDownloadCreatesTheDirectoryOfTheArchive(@TempDir File directory) throws Exception {
        File archive = new File(new File(directory, "jdks"), "jdk.zip");

        DOWNLOAD.ensureVerified(ARCHIVE_URI, archive, CONTENT_SHA256,
                uri -> new JdkArchiveDownload.Source(new ByteArrayInputStream(CONTENT), -1, null));

        assertArrayEquals(CONTENT, Files.readAllBytes(archive.toPath()));
    }

    @Test
    void aDownloadWithAnotherHashLeavesNothingBehind(@TempDir File directory) {
        assertThrows(GradleException.class, () -> DOWNLOAD.ensureVerified(ARCHIVE_URI, new File(directory, "jdk.zip"), OTHER_SHA256,
                uri -> new JdkArchiveDownload.Source(new ByteArrayInputStream(CONTENT), -1, null)));

        assertEquals(0, directory.list().length);
    }

    @Test
    void aDownloadThatBreaksOffLeavesNothingBehind(@TempDir File directory) {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };

        assertThrows(IOException.class, () -> DOWNLOAD.ensureVerified(ARCHIVE_URI, new File(directory, "jdk.zip"), CONTENT_SHA256,
                uri -> new JdkArchiveDownload.Source(broken, -1, null)));

        assertEquals(0, directory.list().length);
    }

    @Test
    void aResponseWithAContentDispositionHeaderIsRejected(@TempDir File directory) {
        GradleException exception = assertThrows(GradleException.class,
                () -> DOWNLOAD.ensureVerified(ARCHIVE_URI, new File(directory, "jdk.zip"), CONTENT_SHA256,
                        uri -> new JdkArchiveDownload.Source(new ByteArrayInputStream(CONTENT), -1, "attachment; filename=\"jdk.zip\"")));

        assertTrue(exception.getMessage().contains("Content-Disposition"), exception.getMessage());
        assertEquals(0, directory.list().length);
    }

    @Test
    void aCachedArchiveWithTheExpectedHashIsReturnedWithoutADownload(@TempDir File directory) throws Exception {
        File cached = new File(directory, "jdk.zip");
        Files.write(cached.toPath(), CONTENT);

        File archive = DOWNLOAD.ensureVerified(ARCHIVE_URI, cached, CONTENT_SHA256, uri -> {
            throw new AssertionError("no download expected");
        });

        assertEquals(cached, archive);
    }

    @Test
    void aCachedArchiveWithAnotherHashFailsAndIsKept(@TempDir File directory) throws Exception {
        File cached = new File(directory, "jdk.zip");
        Files.write(cached.toPath(), CONTENT);

        GradleException exception = assertThrows(GradleException.class,
                () -> DOWNLOAD.ensureVerified(ARCHIVE_URI, cached, OTHER_SHA256));

        assertTrue(exception.getMessage().contains(cached.toString()), exception.getMessage());
        assertTrue(exception.getMessage().contains("Delete the file"), exception.getMessage());
        assertTrue(cached.isFile());
    }

    @Test
    void anArchiveIsOnlyDownloadedOverHttps(@TempDir File directory) {
        GradleException exception = assertThrows(GradleException.class,
                () -> DOWNLOAD.ensureVerified(URI.create("http://example.invalid/zulu/bin/jdk.zip"), new File(directory, "jdk.zip"), CONTENT_SHA256));

        assertTrue(exception.getMessage().contains("https"), exception.getMessage());
        assertEquals(0, directory.list().length);
    }
}
