package io.github.potjerodekool.tools.model;

public interface Dependency {

    String groupId();
    String artifactId();
    String version();
    String scope();
}
