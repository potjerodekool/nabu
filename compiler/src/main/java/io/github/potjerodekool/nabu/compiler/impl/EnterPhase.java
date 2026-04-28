package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.impl.EnterClasses;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;


public final class EnterPhase {

    private EnterPhase() {
    }

    public static CompilationUnit enterPhase(final CompilationUnit compilationUnit,
                                             final CompilerContext compilerContext) {
        final var fileObject = compilationUnit.getFileObject();

        new EnterClasses((CompilerContextImpl) compilerContext).acceptTree(
                compilationUnit,
                null
        );

        compilationUnit.getClasses().stream()
                .map(ClassDeclaration::getClassSymbol)
                .map(classSymbol -> (ClassSymbol) classSymbol)
                .findFirst().ifPresent(classSymbol -> classSymbol.setSourceFile(fileObject));
        return compilationUnit;
    }
}
