package io.github.potjerodekool.nabu.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallPartsTest {

    @Test
    void versionsAreComparedByNumber() {
        assertTrue(Versions.compare("0.10.0", "0.9.0") > 0);
        assertTrue(Versions.compare("1.0", "1.0.0") == 0);
        assertTrue(Versions.compare("0.1.0-SNAPSHOT", "0.1.0") < 0, "a snapshot is older than its release");
        assertTrue(Versions.compare("0.2.0-SNAPSHOT", "0.1.0") > 0);
        assertEquals("1.2.3", Versions.strip("v1.2.3"));
        assertEquals("1.2.3", Versions.strip("1.2.3"));
    }

    @Test
    void platformsAreNamedLikeTheNativeLibrariesOfTheBackend() {
        assertEquals("linux-x86_64", Platform.of("Linux", "amd64"));
        assertEquals("windows-x86_64", Platform.of("Windows 11", "amd64"));
        assertEquals("macosx-arm64", Platform.of("Mac OS X", "aarch64"));
        assertEquals("macosx-x86_64", Platform.of("Mac OS X", "x86_64"));
        assertEquals("linux-arm64", Platform.of("Linux", "aarch64"));
    }

    @Test
    void theChecksumFileIsParsedWithBothFormsOfTheFileName() {
        final Map<String, String> sums = Archives.parseSums(
                "ABC123  nabu-1.zip\r\n" + "def456 *nabu-native-1.zip\n\n");

        assertEquals("abc123", sums.get("nabu-1.zip"));
        assertEquals("def456", sums.get("nabu-native-1.zip"));
    }

    @Test
    void anUnknownCommandIsAnError() {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int code = new Nabu(new Settings(null, "0.1.0", "linux-x86_64", "x"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err)).run(List.of("bogus"));

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown command: bogus"));
    }

    @Test
    void versionAndDoctorWorkWithoutTheNativeBackend() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Nabu nabu = new Nabu(new Settings(Path.of("no-such-home"), "0.1.0", "linux-x86_64", "x"),
                new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, nabu.run(List.of("version")));
        assertEquals(0, nabu.run(List.of("doctor")));

        final String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("nabu 0.1.0 (linux-x86_64)"), text);
        assertTrue(text.contains("not installed: nabu self-update --native"), text);
    }

    @Test
    void theNativeImageCommandSaysHowToGetTheBackendWhenItIsNotThere() {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int code = new Nabu(new Settings(null, "0.1.0", "linux-x86_64", "x"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err)).run(List.of("native-image", "-h"));

        assertEquals(1, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("nabu self-update --native"));
    }
}
