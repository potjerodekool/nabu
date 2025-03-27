package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;

import java.util.Set;

public abstract class LambdaScope {

    private final LambdaScope parent;
    private final Tree owner;
    private final LambdaContext lambdaContext;

    public LambdaScope(final LambdaScope parent,
                       final Tree owner,
                       final LambdaContext lambdaContext) {
        this.parent = parent;
        this.owner = owner;
        this.lambdaContext = lambdaContext;
    }

    public LambdaScope getParent() {
        return parent;
    }

    public Tree getOwner() {
        return owner;
    }

    public LambdaContext getLambdaContext() {
        return lambdaContext;
    }

    public abstract Set<String> locals();

    public abstract void define(final Element element);

    public abstract Element resolve(String name);

    public Function getCurrentFunctionDeclaration() {
        if (owner instanceof Function function) {
            return function;
        } else  {
            final var parent = getParent();
            return parent != null
                    ? parent.getCurrentFunctionDeclaration()
                    : null;
        }
    }

    public ClassDeclaration getCurrentClassDeclaration() {
        if (owner instanceof ClassDeclaration classDeclaration) {
            return classDeclaration;
        } else  {
            final var parent = getParent();
            return parent != null
                    ? parent.getCurrentClassDeclaration()
                    : null;
        }
    }

    public abstract LambdaScope childScope(final Tree owner);
}
