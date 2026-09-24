package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

/**
 * A class scope to resolve static fields and enum constants.
 */
public class ClassScope implements Scope {

    private final DeclaredType declaredType;
    private final Scope parentScope;
    private final CompilationUnit compilationUnit;
    private final ClassElementLoader loader;
    private final Types types;

    public ClassScope(final TypeMirror classType,
                      final Scope parentScope,
                      final CompilationUnit compilationUnit,
                      final CompilerContext compilerContext) {
        this.declaredType = (DeclaredType) classType;
        this.parentScope = parentScope;
        this.compilationUnit = compilationUnit;
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getTypes();
    }

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        var enclosing = getCurrentClass();

        while (enclosing != null) {
            var ancestor = enclosing;

            while (ancestor != null) {
                final var fieldOptional = ElementFilter.elements(
                                ancestor,
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
                }

                final var superclass = ancestor.getSuperclass();

                if (superclass instanceof DeclaredType superType) {
                    ancestor = (TypeElement) superType.asElement();
                } else {
                    ancestor = null;
                }
            }

            final var outerClass = enclosing.getEnclosingElement();

            if (outerClass instanceof TypeElement enclosingType) {
                enclosing = enclosingType;
            } else {
                enclosing = null;
            }
        }

        // Statische single-imports (`import static java.util.Locale.ENGLISH;`)
        // definieren een VariableSymbol in de named-import-scope van de
        // compilatie-eenheid; de ClassScope-chain bereikt die anders niet,
        // waardoor veld-referenties via single-static-imports ongebonden
        // blijven (`ENGLISH`-arg bij Style.parse in picocli).
        final var compilationUnit = getCompilationUnit();
        if (compilationUnit != null) {
            final var imported = compilationUnit.getNamedImportScope().resolve(name);
            if (imported != null) {
                return imported;
            }
        }

        return parentScope != null ? parentScope.resolve(name) : null;
    }

    @Override
    public TypeMirror resolveType(final String name) {
        //For class literal like String.class.
        if ("class".equals(name)) {
            final var module = findModuleElement();

            final var clazz = loader.loadClass(module, Constants.CLAZZ);
            return types.getDeclaredType(clazz, declaredType);
        }

        var enclosing = getCurrentClass();

        while (enclosing != null) {
            var ancestor = enclosing;

            while (ancestor != null) {
                final var memberTypeOptional = ElementFilter.elements(
                                ancestor,
                                element ->
                                        element.getKind().isClass()
                                                || element.getKind().isInterface()
                                                || element.getKind() == ElementKind.ENUM
                                                || element.getKind() == ElementKind.ANNOTATION_TYPE,
                                TypeElement.class
                        ).stream()
                        .filter(elem -> elem.getSimpleName().contentEquals(name))
                        .findFirst();

                if (memberTypeOptional.isPresent()) {
                    return memberTypeOptional.get().asType();
                }

                final var superclass = ancestor.getSuperclass();

                if (superclass instanceof DeclaredType superType) {
                    ancestor = (TypeElement) superType.asElement();
                } else {
                    ancestor = null;
                }
            }

            final var outerClass = enclosing.getEnclosingElement();

            if (outerClass instanceof TypeElement enclosingType) {
                enclosing = enclosingType;
            } else {
                enclosing = null;
            }
        }

        return parentScope != null
                ? parentScope.resolveType(name)
                : null;
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
