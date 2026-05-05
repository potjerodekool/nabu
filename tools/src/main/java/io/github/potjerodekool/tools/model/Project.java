package io.github.potjerodekool.tools.model;

import java.util.List;

public interface Project {

    List<Module> modules();

    JDK jdk();

    List<Repository> repositories();
}
