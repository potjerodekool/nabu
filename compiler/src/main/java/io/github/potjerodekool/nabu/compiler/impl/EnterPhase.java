package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.internal.EnterClasses;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;


public final class EnterPhase {

    private EnterPhase() {
    }

    public static FileObjectAndCompilationUnit enterPhase(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                          final CompilerContextImpl compilerContext) {
        final var fileObject = fileObjectAndCompilationUnit.fileObject();
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();

        final var enterClasses = new EnterClasses(compilerContext);
        compilationUnit.accept(enterClasses, null);

        compilationUnit.getClasses().stream()
                .map(ClassDeclaration::getClassSymbol)
                .map(classSymbol -> (ClassSymbol) classSymbol)
                .findFirst().ifPresent(classSymbol -> classSymbol.setSourceFile(fileObject));
        return fileObjectAndCompilationUnit;
    }
}
