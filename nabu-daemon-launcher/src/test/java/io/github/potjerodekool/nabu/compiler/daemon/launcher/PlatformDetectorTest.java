package io.github.potjerodekool.nabu.compiler.daemon.launcher;

import org.junit.jupiter.api.NamedExecutable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class PlatformDetectorTest {

    @ParameterizedTest
    @CsvSource({
            "Linux, amd64, linux-x86_64",
            "Linux, x86_64, linux-x86_64",
            "Linux, aarch64, linux-arm64",
            "Windows 11, amd64, windows-x86_64",
            "Windows 11, aarch64, windows-arm64",
            "Mac OS X, x86_64, macosx-x86_64",
            "Mac OS X, aarch64, macosx-arm64",
            "Darwin, arm64, macosx-arm64"
    })
    void mapsKnownOsAndArchCombinationsToTheExpectedClassifier(final String osName, final String osArch, final String expectedClassifier) {
        assertEquals(expectedClassifier, PlatformDetector.classifierFor(osName, osArch));
    }

    @Test
    void throwsForAnUnsupportedOperatingSystem() {
        assertThrows(UnsupportedPlatformException.class, (NamedExecutable) () -> PlatformDetector.classifierFor("SunOS", "x86_64"));
    }

    @Test
    void throwsForAnUnsupportedArchitecture() {
        assertThrows(UnsupportedPlatformException.class, (NamedExecutable) () -> PlatformDetector.classifierFor("Linux", "mips64el"));
    }

    @Test
    void classifierReadsLiveSystemPropertiesWithoutThrowing() {
        // Smoke test: whatever CI/dev machine this runs on should resolve to
        // *some* classifier without an exception, since it's a supported
        // platform by definition.
        final var classifier = PlatformDetector.classifier();
        assertNotNull(classifier);
        assertFalse(classifier.isBlank());
    }

}