package io.github.potjerodekool.tools;

import io.github.potjerodekool.tools.model.JDK;
import io.github.potjerodekool.tools.model.Module;
import io.github.potjerodekool.tools.model.Project;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class Tool {

    public Set<JDK> findJdkRange(final Project project) {
        final var allJdks = new HashSet<JDK>();

        if (project.jdk() != null)  {
            allJdks.add(project.jdk());
        }

        project.modules().stream()
                .map(Module::jdk)
                .filter(Objects::nonNull)
                .forEach(allJdks::add);

        return allJdks;
    }

    public static void main(final String[] args) {
        final var path = Paths.get("project.toml");
        final var project = Parser.parseProject(path);

    }
}
