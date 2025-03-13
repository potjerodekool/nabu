package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.resolve.scope.AbstractScope;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;

import java.util.LinkedHashSet;
import java.util.Set;

public class SimpleScope extends AbstractScope {

    private final Tree owner;
    private final LambdaContext lambdaContext;

    public SimpleScope(final Tree owner,
                       final LambdaContext lambdaContext) {
        this(null, owner, lambdaContext);
    }

    public SimpleScope(final SimpleScope parentScope,
                       final Tree owner,
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

    public Function getCurrentFunctionDeclaration() {
        if (owner instanceof Function function) {
            return function;
        } else  {
            final var parent = getParentScope();
            return parent != null
                    ? parent.getCurrentFunctionDeclaration()
                    : null;
        }
    }

    public ClassDeclaration getCurrentClassDeclaration() {
        if (owner instanceof ClassDeclaration classDeclaration) {
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

    public Tree getOwner() {
        return owner;
    }

    public SimpleScope childScope(final Tree owner) {
        return new SimpleScope(
                this,
                owner,
                lambdaContext
        );
    }
}
