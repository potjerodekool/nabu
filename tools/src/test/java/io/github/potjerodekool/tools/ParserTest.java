package io.github.potjerodekool.tools;

import io.github.potjerodekool.tools.model.MutableModule;
import io.github.potjerodekool.tools.model.Repository;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void parseProject() {
        final var project = Parser.parseProject(Paths.get("src/test/resources/project.toml"));
        final var moduleNames = project.modules().stream()
                .map(MutableModule::name)
                .toList();

        final var jdk = project.jdk();

        assertEquals(List.of("com.example.core", "com.example.payments", "com.example.api"), moduleNames);
        assertEquals("temurin", jdk.preferredDistribution());
        assertEquals(21, jdk.minimalVersion());

        final var repositories = project.repositories().stream()
                .collect(Collectors.toMap(
                        Repository::name,
                        Repository::url
                ));

        assertEquals(
                Map.of("central", "https://repo1.maven.org/maven2"),
                repositories
        );
    }
}