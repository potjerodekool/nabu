package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.resolve.scope.AbstractScope;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CElement;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;

import java.util.LinkedHashSet;
import java.util.Set;

public class SimpleScope extends AbstractScope {

    private final CElement<?> owner;
    private final LambdaContext lambdaContext;

    public SimpleScope(final CElement<?> owner,
                       final LambdaContext lambdaContext) {
        this(null, owner, lambdaContext);
    }

    public SimpleScope(final SimpleScope parentScope,
                       final CElement<?> owner,
                       final LambdaContext lambdaContext) {
        super(parentScope);
        this.lambdaContext = lambdaContext;
        this.owner = owner;
    }

    public Set<String> locals() {
        return new LinkedHashSet<>(super.locals());
    }

    private SimpleScope getParentScope() {
        return (SimpleScope) getParent();
    }

    public CFunction getCurrentFunctionDeclaration() {
        if (owner instanceof CFunction function) {
            return function;
        } else  {
            final var parent = getParentScope();
            return parent != null
                    ? parent.getCurrentFunctionDeclaration()
                    : null;
        }
    }

    public CClassDeclaration getCurrentClassDeclaration() {
        if (owner instanceof CClassDeclaration classDeclaration) {
            return classDeclaration;
        } else  {
            final var parent = getParentScope();
            return parent != null
                    ? parent.getCurrentClassDeclaration()
                    : null;
        }
    }

    public LambdaContext getLambdaContext() {
        return lambdaContext;
    }

    public SimpleScope childScope(final CElement<?> owner) {
        return new SimpleScope(
                this,
                owner,
                lambdaContext
        );
    }
}
