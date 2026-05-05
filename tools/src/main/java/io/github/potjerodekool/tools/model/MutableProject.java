package io.github.potjerodekool.tools.model;

import java.util.Collections;
import java.util.List;

public class MutableProject implements Project {

    private List<Module> modules;
    private JDK jdk;
    private List<Repository> repositories;

    public void modules(final List<Module> modules) {
        this.modules = modules;
    }

    public void jdk(final JDK jdk) {
        this.jdk = jdk;
    }

    public void repositories(final List<Repository> repositories) {
        this.repositories = repositories;
    }

    @Override
    public List<Module> modules() {
        return modules != null ? modules : Collections.emptyList();
    }

    @Override
    public JDK jdk() {
        return jdk;
    }

    @Override
    public List<Repository> repositories() {
        return repositories != null ? repositories : Collections.emptyList();
    }
}
