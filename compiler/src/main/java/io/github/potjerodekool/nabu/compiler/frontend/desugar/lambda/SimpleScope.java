package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.Tree;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class SimpleScope extends LambdaScope {

    private final Map<String, Element> locals = new LinkedHashMap<>();

    public SimpleScope(final Tree owner,
                       final LambdaContext lambdaContext) {
        this(null, owner, lambdaContext);
    }

    public SimpleScope(final LambdaScope parentScope,
                       final Tree owner,
                       final LambdaContext lambdaContext) {
        super(parentScope, owner, lambdaContext);
    }

    @Override
    public Set<String> locals() {
        return locals.keySet();
    }

    @Override
    public void define(final Element element) {
        locals.put(element.getSimpleName(), element);
    }

    @Override
    public Element resolve(String name) {
        var element = this.locals.get(name);

        if (element != null) {
            return element;
        }

        final var parent = getParent();

        if (parent != null) {
            return parent.resolve(name);
        }

        return null;
    }

    @Override
    public SimpleScope childScope(final Tree owner) {
        return new SimpleScope(
                this,
                owner,
                getLambdaContext()
        );
    }

}
