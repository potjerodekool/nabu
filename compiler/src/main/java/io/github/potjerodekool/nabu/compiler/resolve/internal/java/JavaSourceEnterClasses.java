package io.github.potjerodekool.nabu.compiler.resolve.internal.java;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.frontend.parser.java.JavaCompilerParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.compiler.resolve.internal.EnterClasses;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

import java.io.IOException;

public class JavaSourceEnterClasses {

    private final EnterClasses enterClasses;

    public JavaSourceEnterClasses(final EnterClasses enterClasses) {
        this.enterClasses = enterClasses;
    }

    public CompilationUnit enter(final ClassSymbol classSymbol) {
        return parse(classSymbol.getSourceFile());
    }

    public CompilationUnit parse(final FileObject fileObject) {
        try (var inputstream = fileObject.openInputStream()) {
            final var compilationUnit = JavaCompilerParser.parse(inputstream);
            final var compilationUnitTree = (CompilationUnit) compilationUnit.accept(new JavaCompilerVisitor(
                    fileObject
            ));
            compilationUnitTree.accept(enterClasses, null);
            return compilationUnitTree;
        } catch (IOException ignored) {
        }
        return null;
    }
}
