package io.github.potjerodekool.tools;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import io.github.potjerodekool.tools.model.*;
import io.github.potjerodekool.tools.model.Module;
import io.github.potjerodekool.tools.model.MutableModule;

import java.nio.file.Path;
import java.util.List;

public final class Parser {

    private Parser() {
    }

    public static Project parseProject(final Path path) {
        try (var config = FileConfig.of(path)) {
            final var project = new MutableProject();

            config.load();
            config.entrySet().forEach(entry -> {
                var key = entry.getKey();
                var value = entry.getValue();

                switch (key) {
                    case "workspace" -> project.modules(parseModules((Config) value, path));
                    case "java" -> project.jdk(parseJdk((Config) value));
                    case "repositories" -> project.repositories(parseRepositories((Config) value));
                }
            });

            return project;
        }
    }

    private static List<Module> parseModules(final Config value,
                                             final Path path) {
        final var members = (List<String>) value.get("members");

        return members.stream()
                .map(moduleName -> {
                    final var modulePath = path.resolveSibling(moduleName + "/module.toml").toAbsolutePath();
                    return parseModule(modulePath);
                })
                .toList();
    }

    //Module

    public static Module parseModule(final Path path) {
        try (var config = FileConfig.of(path)) {
            config.load();

            final var module = new MutableModule();

            config.entrySet().forEach(entry -> {
                var key = entry.getKey();
                var value = entry.getValue();

                switch (key) {
                    case "module" -> parseModule((Config) value, module);
                    case "java" -> module.jdk(parseJdk((Config) value));
                    case "dependencies" -> module.dependencies(parseDependencies((Config) value));
                    case "repositories" -> {
                        final var repositories = parseRepositories((Config) value);
                        module.repositories(repositories);
                    }
                }

            });

            return module;
        }
    }

    private static List<? extends Dependency> parseDependencies(final Config config) {
        return config.entrySet().stream()
                .map(entry -> {
                    final var groupAndArtifact = entry.getKey().split(":");
                    final var dependencyConfig = (Config) entry.getValue();
                    final String version = dependencyConfig.get("version");
                    final String scope = dependencyConfig.get("scope");
                    return new MutableDependency(
                            groupAndArtifact[0],
                            groupAndArtifact[1],
                            version,
                            scope
                    );
                }).toList();
    }

    private static void parseModule(final Config config, final MutableModule module) {
        final String name = config.get("name");
        final String version = config.get("version");

        module.name(name);
        module.version(version);
    }

    static JDK parseJdk(final Config value) {
        final var preferredDistribution = (String) value.get("preferred-distribution");
        final int minimalVersion = value.get("min");
        final Integer maximalVersion = value.get("max");
        final Integer recommendedVersion = value.get("recommended");

        return new JDK(
                preferredDistribution,
                minimalVersion,
                maximalVersion,
                recommendedVersion
        );
    }

    static List<Repository> parseRepositories(final Config config) {
        return config.entrySet().stream().map(entry -> {
            final var name = entry.getKey();
            final var url = (String) entry.getValue();
            return new Repository(name, url);
        }).toList();
    }
}
