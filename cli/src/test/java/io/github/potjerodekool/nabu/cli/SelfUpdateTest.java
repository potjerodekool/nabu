package io.github.potjerodekool.nabu.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateTest {

    private static final String PLATFORM = "linux-x86_64";

    @TempDir
    Path home;

    private FakeReleases releases;
    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errBytes;

    @BeforeEach
    void installed() throws IOException {
        releases = new FakeReleases();
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        install("0.1.0", false);
    }

    @AfterEach
    void stop() {
        releases.close();
    }

    private void install(final String version, final boolean withNative) throws IOException {
        final Path lib = Files.createDirectories(home.resolve("versions").resolve(version).resolve("lib"));
        Files.writeString(lib.resolve("nabu-cli.jar"), "core " + version);
        if (withNative) {
            Files.writeString(lib.resolve("native-backend-" + version + ".jar"), "native " + version);
            final Path bin = Files.createDirectories(home.resolve("bin"));
            Files.writeString(bin.resolve("nabu-native-image"), "launcher " + version);
            Files.writeString(bin.resolve("nabu-native-image.cmd"), "launcher " + version);
        }
        Files.writeString(home.resolve("current"), version + "\n");
    }

    private void publish(final String version) throws IOException {
        final String tag = "v" + version;
        releases.publish(tag, "nabu-" + version + ".zip",
                FakeReleases.zip("lib/nabu-cli.jar", "core " + version, "NOTICE", "notice"));
        releases.publish(tag, "nabu-native-" + version + "-" + PLATFORM + ".zip",
                FakeReleases.zip("lib/native-backend-" + version + ".jar", "native " + version,
                        "bin/nabu-native-image", "launcher " + version,
                        "bin/nabu-native-image.cmd", "launcher " + version));
    }

    private int run(final String running, final String... args) {
        final Settings settings = new Settings(home, running, PLATFORM, releases.url());
        final SelfUpdate.Options options = SelfUpdate.parse(List.of(args), new PrintStream(errBytes));
        return new SelfUpdate(settings, new Releases(releases.url()),
                new PrintStream(outBytes), new PrintStream(errBytes)).run(options);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private String current() throws IOException {
        return Files.readString(home.resolve("current")).trim();
    }

    private List<String> versions() throws IOException {
        try (Stream<Path> dirs = Files.list(home.resolve("versions"))) {
            return dirs.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private List<String> homeEntries() throws IOException {
        try (Stream<Path> entries = Files.list(home)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theLatestReleaseIsInstalledNextToTheRunningOneAndBecomesCurrent() throws Exception {
        publish("0.2.0");

        assertEquals(0, run("0.1.0"), err());

        assertEquals("0.2.0", current());
        assertEquals(List.of("0.1.0", "0.2.0"), versions());
        assertEquals("core 0.2.0", Files.readString(home.resolve("versions/0.2.0/lib/nabu-cli.jar")));
        assertFalse(Files.exists(home.resolve("versions/0.2.0/lib/native-backend-0.2.0.jar")));
        assertEquals(List.of("current", "versions"), homeEntries(), "nothing is left behind");
    }

    @Test
    void theRunningVersionIsNotTouched() throws Exception {
        publish("0.2.0");

        run("0.1.0");

        assertEquals("core 0.1.0", Files.readString(home.resolve("versions/0.1.0/lib/nabu-cli.jar")));
    }

    @Test
    void nothingHappensWhenTheLatestReleaseIsTheRunningOne() throws Exception {
        releases.latest("v0.1.0");

        assertEquals(0, run("0.1.0"));

        assertTrue(out().contains("up to date"), out());
        assertEquals(List.of("0.1.0"), versions());
    }

    @Test
    void checkOnlySaysThatAnUpdateExists() throws Exception {
        publish("0.2.0");

        assertEquals(0, run("0.1.0", "--check"));

        assertTrue(out().contains("0.2.0 is available"), out());
        assertEquals("0.1.0", current());
        assertEquals(List.of("0.1.0"), versions());
    }

    @Test
    void anArchiveThatDoesNotMatchTheChecksumChangesNothing() throws Exception {
        releases.publishCorrupt("v0.2.0", "nabu-0.2.0.zip", FakeReleases.zip("lib/nabu-cli.jar", "core 0.2.0"));

        assertEquals(1, run("0.1.0"));

        assertTrue(err().contains("does not match SHA256SUMS"), err());
        assertEquals("0.1.0", current());
        assertEquals(List.of("0.1.0"), versions());
        assertEquals(List.of("current", "versions"), homeEntries());
    }

    @Test
    void aReleaseThatIsNotPublishedGivesAMessageAndChangesNothing() throws Exception {
        releases.latest("v0.9.0");

        assertEquals(1, run("0.1.0"));

        assertTrue(err().contains("nothing was changed"), err());
        assertEquals("0.1.0", current());
    }

    @Test
    void anArchiveWithAnEntryOutsideItsDirectoryIsRejected() throws Exception {
        releases.publish("v0.2.0", "nabu-0.2.0.zip", FakeReleases.zip("../evil.txt", "x", "lib/nabu-cli.jar", "core"));

        assertEquals(1, run("0.1.0"));

        assertTrue(err().contains("outside its directory"), err());
        assertFalse(Files.exists(home.resolve("versions/evil.txt")));
        assertFalse(Files.exists(home.getParent().resolve("evil.txt")));
        assertEquals("0.1.0", current());
        assertEquals(List.of("0.1.0"), versions());
    }

    @Test
    void theNativeBackendComesAlongWhenAskedFor() throws Exception {
        publish("0.2.0");

        assertEquals(0, run("0.1.0", "--native"), err());

        assertEquals("native 0.2.0", Files.readString(home.resolve("versions/0.2.0/lib/native-backend-0.2.0.jar")));
        assertTrue(out().contains("with the native backend"), out());
        assertEquals("launcher 0.2.0", Files.readString(home.resolve("bin/nabu-native-image")));
        assertEquals("launcher 0.2.0", Files.readString(home.resolve("bin/nabu-native-image.cmd")));
    }

    @Test
    void theNativeBackendIsCarriedOverWhenTheRunningVersionHasIt() throws Exception {
        install("0.1.0", true);
        publish("0.2.0");

        assertEquals(0, run("0.1.0"), err());

        assertTrue(Files.exists(home.resolve("versions/0.2.0/lib/native-backend-0.2.0.jar")));
        assertEquals("launcher 0.2.0", Files.readString(home.resolve("bin/nabu-native-image")));
    }

    @Test
    void noNativeLeavesItOut() throws Exception {
        install("0.1.0", true);
        publish("0.2.0");

        assertEquals(0, run("0.1.0", "--no-native"), err());

        assertFalse(Files.exists(home.resolve("versions/0.2.0/lib/native-backend-0.2.0.jar")));
        assertFalse(Files.exists(home.resolve("bin/nabu-native-image")));
        assertFalse(Files.exists(home.resolve("bin/nabu-native-image.cmd")));
    }

    @Test
    void whenThereIsNothingToInstallTheMissingNativeLaunchersAreRestored() throws Exception {
        install("0.1.0", true);
        final Path shipped = Files.createDirectories(home.resolve("versions/0.1.0/bin"));
        Files.writeString(shipped.resolve("nabu-native-image"), "shipped");
        Files.writeString(shipped.resolve("nabu-native-image.cmd"), "shipped");
        Files.delete(home.resolve("bin/nabu-native-image"));
        Files.delete(home.resolve("bin/nabu-native-image.cmd"));
        publish("0.1.0");
        releases.latest("v0.1.0");

        assertEquals(0, run("0.1.0", "--check"), err());
        assertFalse(Files.exists(home.resolve("bin/nabu-native-image")), "--check changes nothing");

        assertEquals(0, run("0.1.0"), err());

        assertTrue(out().contains("is up to date"), out());
        assertEquals("shipped", Files.readString(home.resolve("bin/nabu-native-image")));
        assertEquals("shipped", Files.readString(home.resolve("bin/nabu-native-image.cmd")));
    }

    @Test
    void aNativeBackendThatIsNotPublishedForThePlatformChangesNothing() throws Exception {
        releases.publish("v0.2.0", "nabu-0.2.0.zip", FakeReleases.zip("lib/nabu-cli.jar", "core 0.2.0"));

        assertEquals(1, run("0.1.0", "--native"));

        assertTrue(err().contains("nabu-native-0.2.0-" + PLATFORM + ".zip is not in SHA256SUMS"), err());
        assertEquals("0.1.0", current());
        assertEquals(List.of("0.1.0"), versions());
    }

    @Test
    void theNativeBackendCanBeAddedToTheRunningVersion() throws Exception {
        publish("0.1.0");
        releases.latest("v0.1.0");

        assertEquals(0, run("0.1.0", "--native"), err());

        assertEquals("native 0.1.0", Files.readString(home.resolve("versions/0.1.0/lib/native-backend-0.1.0.jar")));
        assertEquals("0.1.0", current());
    }

    @Test
    void aVersionAskedForIsInstalledAndAnOlderOneCanBeGoneBackTo() throws Exception {
        publish("0.2.0");
        publish("0.3.0");

        run("0.1.0");
        run("0.2.0", "--version", "0.3.0");

        assertEquals("0.3.0", current());
        assertEquals(List.of("0.2.0", "0.3.0"), versions(), "the version before the previous one is removed");

        assertEquals(0, run("0.3.0", "--version", "0.2.0"), err());
        assertEquals("0.2.0", current());
    }

    @Test
    void homelessNabuCannotUpdate() {
        final Settings settings = new Settings(null, "0.1.0", PLATFORM, releases.url());

        final int code = new SelfUpdate(settings, new Releases(releases.url()),
                new PrintStream(outBytes), new PrintStream(errBytes)).run(new SelfUpdate.Options(false, null, null));

        assertEquals(1, code);
        assertTrue(err().contains("nabu.home"), err());
    }
}
