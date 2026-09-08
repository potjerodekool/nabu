package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressietest: geneste (member) klassen uit een classpath-jar moeten ook
 * via hun DOTTED naan ("picocli.CommandLine.Command") laadbaar zijn — en
 * de annotation interface-methoden (element-waarden) moeten resolvable zijn.
 */
class NestedAnnotationResolutionTest extends AbstractCompilerTest {

    @Override
    protected String getClassPath() {
        return createClassPath(getLocationOfClass(picocli.CommandLine.class));
    }

    @Override
    protected void configureOptions(final CompilerOptions.CompilerOptionsBuilder optionsBuilder) {
        super.configureOptions(optionsBuilder);
    }

    @Test
    void nestedAnnotationClassFromClasspathJarIsLoadableByDottedName() {
        final var unknown = getUnnamedModule();
        final var loader = getCompilerContext().getClassElementLoader();

        final var commandLine = loader.loadClass(unknown, "picocli.CommandLine");
        assertNotNull(commandLine, "picocli.CommandLine moet laden");

        final var command = loader.loadClass(unknown, "picocli.CommandLine.Command");
        assertNotNull(command, "picocli.CommandLine.Command moet via dot-naam laden");
        command.complete();

        final var memberTypeNames = ElementFilter.typesIn(commandLine.getEnclosedElements()).stream()
                .map(it -> it.getSimpleName())
                .toList();
        assertTrue(memberTypeNames.contains("Command"), "Command moet na load+complete lid zijn: " + memberTypeNames);

        final var methods = ElementFilter.methodsIn(command.getEnclosedElements()).stream()
                .map(it -> it.getSimpleName())
                .toList();

        assertTrue(methods.contains("name"), "element-waarde 'name': " + methods);
        assertTrue(methods.contains("mixinStandardHelpOptions"), "element-waarde 'mixinStandardHelpOptions': " + methods);
        assertTrue(methods.contains("version"), "element-waarde 'version': " + methods);
        assertTrue(methods.contains("description"), "element-waarde 'description': " + methods);
    }
}