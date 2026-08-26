package io.github.potjerodekool.nabu.compiler.resolve.impl.java;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaModuleParser;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JavaModuleParserTest extends AbstractCompilerTest {

    @Test
    void parse() {
        final var systemTable = ((CompilerContextImpl) getCompilerContext()).getSymbolTable();
        final var moduleSymbol = new ModuleSymbol(
                0,
                "java.base"
        );

        final var fileObject = new PathFileObject(
                new FileObject.Kind(
                        ".java",
                        true
                ),
                Paths.get("src/test/resources/jmods/java.base/module-info.java")
        );
        JavaModuleParser.parse(fileObject, systemTable, moduleSymbol);

        final var actual = moduleSymbol.getExports().stream()
                        .map(this::toString)
                                .collect(Collectors.joining("\n"));

        final var expected = """
            exports java.io;
            exports java.lang;
            exports java.time;
            exports java.util;""";

        assertEquals(expected, actual);
    }

    private String toString(final ModuleElement.ExportsDirective directive) {
        final var exports = "exports " + directive.getPackage().getQualifiedName();

        if (directive.getTargetModules().isEmpty()) {
            return exports + ";";
        }

        final var modules = directive.getTargetModules().stream()
                .map(QualifiedNameable::getQualifiedName)
                .collect(Collectors.joining(", ", " to ", ";"));


        return exports + modules;
    }

}