package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.frontend.parser.java.JavaCompilerParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

import java.io.IOException;

public class SourceEnterClasses {

    private final EnterClasses enterClasses;

    public SourceEnterClasses(final EnterClasses enterClasses) {
        this.enterClasses = enterClasses;
    }

    public CompilationUnit enter(final ClassSymbol classSymbol) {
        try (var inputstream = classSymbol.getSourceFile().openInputStream()) {
            final var compilationUnit = JavaCompilerParser.parse(inputstream);
            final var compilationUnitTree = (CompilationUnit) compilationUnit.accept(new JavaCompilerVisitor(
                    classSymbol.getSourceFile()
            ));
            compilationUnitTree.accept(enterClasses, null);
            return compilationUnitTree;
        } catch (IOException ignored) {
        }
        return null;
    }
}
