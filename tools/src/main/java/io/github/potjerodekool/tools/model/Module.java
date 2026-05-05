package io.github.potjerodekool.tools.model;

import java.util.List;

public interface Module {

    String name();

    String version();

    JDK jdk();

    List<? extends Dependency> dependencies();

    List<Repository> repositories();
}
