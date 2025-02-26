package io.github.potjerodekool.nabu.compiler.resolve.scanner;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class JModScannerTest {

    private JModScanner createScanner() {
        final var javaHome = System.getProperty("java.home");
        final var modPath = Paths.get(javaHome, "jmods", "java.base.jmod");
        return new JModScanner(modPath);
    }


    @Test
    void findFile() {
        final var scanner = createScanner();
        final var result = scanner.findFile("java/lang/Object.class");
        final var fileMatchResult = assertInstanceOf(FileMatchResult.class, result);
        assertEquals("/classes/java/lang/Object.class", fileMatchResult.path().toString());
    }

    @Test
    void findDirectory() {
        final var scanner = createScanner();
        final var result = scanner.findDirectory("java/lang");
        final var fileMatchResult = assertInstanceOf(FileMatchResult.class, result);
        assertEquals("/classes/java/lang", fileMatchResult.path().toString());

        //scanner.walkDirectory("java/lang", path -> System.out.println(path.toString()));
    }
}