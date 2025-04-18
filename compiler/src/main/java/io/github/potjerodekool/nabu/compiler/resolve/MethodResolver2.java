package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;

import java.util.Optional;
import java.util.function.Predicate;

public class MethodResolver2 {

    public Optional<ExecutableType> resolveMethod(final MethodInvocationTree methodInvocation,
                                                  final Scope scope) {
        final String methodName;

        final var selector = methodInvocation.getMethodSelector();

        if (selector instanceof IdentifierTree identifierTree) {
            methodName = identifierTree.getName();
        } else {
            methodName = "";
        }

        return resolveMethodInScope(methodName, scope);
    }

    private Optional<ExecutableType> resolveMethodInScope(final String methodName,
                                                          final Scope scope) {
        final var namedImportScope = scope.getCompilationUnit().getNamedImportScope();
        final var resolvedMethodOptional = resolveMethodInScope(methodName, namedImportScope);

        if (resolvedMethodOptional.isPresent()) {
            return resolvedMethodOptional;
        } else {
            final var startImportScope = scope.getCompilationUnit().getStartImportScope();
            return resolveMethodInScope(methodName, startImportScope);
        }
    }

    private Optional<ExecutableType> resolveMethodInScope(final String methodName,
                                                          final ImportScope importScope) {
        final var symbols = importScope.resolveByName(
                methodName,
                methodFilter()
        );

        final var iterator = symbols.iterator();

        if (iterator.hasNext()) {
            final var first = iterator.next();

            if (iterator.hasNext()) {
                return Optional.empty();
            } else {
                return Optional.of((ExecutableType) first.asType());
            }
        }

        return Optional.empty();
    }

    private Predicate<Symbol> methodFilter() {
        return symbol -> symbol instanceof MethodSymbol;
    }
}
