package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class ClassScope implements Scope {

    private final DeclaredType declaredType;
    private final ClassScope parentScope;
    private final CompilationUnit compilationUnit;
    private final ClassElementLoader loader;

    public ClassScope(final TypeMirror classType,
                      final ClassScope parentScope,
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
        return null;
    }

    @Override
    public TypeMirror resolveType(final String name) {
        if ("class".equals(name)) {
            final var clazz = loader.loadClass(Constants.CLAZZ);
            return loader.getTypes()
                    .getDeclaredType(
                            clazz,
                            declaredType
                    );
        } else {
            return null;
        }
    }

    @Override
    public Scope getParent() {
        return parentScope;
    }

    @Override
    public TypeElement getCurrentClass() {
        return declaredType.getTypeElement();
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
