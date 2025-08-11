package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;

public class SymbolGenerator {

    private final CompilerContextImpl compilerContext;

    public SymbolGenerator(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
    }

    public void generate(final CompilationUnit compilationUnit,
                         final ClassDeclaration classDeclaration,
                         final PackageElement packageSymbol) {
        final var enterClasses = new EnterClasses(compilerContext);
        final var globalScope = new GlobalScope(compilationUnit, null);
        globalScope.setPackageElement(packageSymbol);
        classDeclaration.accept(enterClasses, globalScope);
    }
}
