package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;

import java.util.Objects;

public class ClassScope implements Scope {

    private final ClassSymbol classSymbol;
    private final Scope parentScope;

    public ClassScope(final ClassSymbol classSymbol,
                      final Scope parentScope) {
        Objects.requireNonNull(parentScope);
        this.classSymbol = classSymbol;
        this.parentScope = parentScope;
    }

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        return classSymbol.getEnclosedElements().stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD)
                .filter(elem -> elem.getSimpleName().equals(name))
                .findFirst()
                .orElseGet(() -> parentScope.resolve(name));
    }

    @Override
    public Scope getParent() {
        return parentScope;
    }

    @Override
    public ClassSymbol getCurrentClass() {
        return classSymbol;
    }

    public ClassSymbol getClassSymbol() {
        return classSymbol;
    }
}
