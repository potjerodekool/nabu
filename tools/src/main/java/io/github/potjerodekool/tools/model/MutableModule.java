package io.github.potjerodekool.tools.model;

import java.util.Collections;
import java.util.List;

public class MutableModule implements Module {

    private String name;
    private String version;
    private JDK jdk;
    private List<? extends Dependency> dependencies;
    private List<Repository> repositories;

    @Override
    public String name() {
        return name;
    }

    public void name(final String name) {
        this.name = name;
    }

    @Override
    public String version() {
        return version;
    }

    public void version(final String version) {
        this.version = version;
    }

    @Override
    public JDK jdk() {
        return jdk;
    }

    public void jdk(final JDK jdk) {
        this.jdk = jdk;
    }

    @Override
    public List<? extends Dependency> dependencies() {
        return dependencies != null ? dependencies : Collections.emptyList();
    }

    public void dependencies(final List<? extends Dependency> dependencies) {
        this.dependencies = dependencies;
    }

    @Override
    public List<Repository> repositories() {
        return repositories != null ? repositories : Collections.emptyList();
    }

    public void repositories(final List<Repository> repositories) {
        this.repositories = repositories;
    }
}
