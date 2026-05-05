package io.github.potjerodekool.tools.model;

public record MutableDependency(String groupId,
                                String artifactId,
                                String version,
                                String scope) implements Dependency {
}
