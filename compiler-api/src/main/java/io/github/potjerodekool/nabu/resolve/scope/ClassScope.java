package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class ClassScope implements Scope {

    private final DeclaredType declaredType;
    private final Scope parentScope;
    private final CompilationUnit compilationUnit;
    private final ClassElementLoader loader;

    public ClassScope(final TypeMirror classType,
                      final Scope parentScope,
                      final CompilationUnit compilationUnit,
                      final ClassElementLoader loader) {
        this.declaredType = (DeclaredType) classType;
        this.parentScope = parentScope;
        this.compilationUnit = compilationUnit;
        this.loader = loader;
    }

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        final var classSymbol = getCurrentClass();
        final var fieldOptional = ElementFilter.elements(
                        classSymbol,
                        element ->
                                element.getKind() == ElementKind.FIELD
                                        || element.getKind() == ElementKind.ENUM_CONSTANT,
                        VariableElement.class
                ).stream()
                .filter(Element::isStatic)
                .filter(elem -> elem.getSimpleName().equals(name))
                .findFirst();

        if (fieldOptional.isPresent()) {
            return fieldOptional.get();
        } else if (parentScope != null) {
            return parentScope.resolve(name);
        } else {
            return null;
        }
    }

    @Override
    public TypeMirror resolveType(final String name) {
        //For class literal like String.class.
        if ("class".equals(name)) {
            final var module = findModuleElement();

            final var clazz = loader.loadClass(
                    module,
                    Constants.CLAZZ
            );
            return loader.getTypes()
                    .getDeclaredType(
                            clazz,
                            declaredType
                    );
        } else {
            return parentScope != null
                    ? parentScope.resolveType(name)
                    : null;
        }
    }

    @Override
    public Scope getParent() {
        return parentScope;
    }

    @Override
    public TypeElement getCurrentClass() {
        return declaredType.asTypeElement();
    }

    @Override
    public Element getCurrentElement() {
        return getCurrentClass();
    }

    @Override
    public CompilationUnit getCompilationUnit() {
        return compilationUnit;
    }
}
