package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

import java.util.Objects;
import java.util.Optional;

/**
 * A scope for searching both instance as static fields.
 */
public class SymbolScope implements Scope {

    private final DeclaredType declaredType;

    private final Scope parentScope;

    public SymbolScope(final DeclaredType declaredType,
                       final Scope parentScope) {
        Objects.requireNonNull(declaredType);
        this.declaredType = declaredType;
        this.parentScope = parentScope;
    }

    @Override
    public void define(final Element element) {
    }

    private static final String PROBE_LOG =
            "C:/Users/evert/AppData/Local/Temp/opencode/diag.log";
    private static final java.util.Set<String> PROBED_NAMES = java.util.Set.of(
            "IParseResultHandler2", "IExceptionHandler2", "AbstractHandler", "CSI", "value");

    private static void probeResolve(final String method,
                                     final String name,
                                     final TypeElement enclosing,
                                     final Element result) {
        if (!PROBED_NAMES.contains(name)) {
            return;
        }
        final var current = enclosing == null ? "null"
                : enclosing.getQualifiedName() + "@" + System.identityHashCode(enclosing);
        final var enclosed = enclosing == null ? -1
                : enclosing.getEnclosedElements() == null ? -2
                : enclosing.getEnclosedElements().size();
        try (final var pw = new java.io.PrintWriter(
                new java.io.FileWriter(PROBE_LOG, true))) {
            pw.println("[SYMBOLSCOPE] m=" + method
                    + " name=" + name
                    + " enclosing=" + current
                    + " enclosed=" + enclosed
                    + " hit=" + (result != null ? result.getClass().getSimpleName() : "null")
                    + " qn=" + (result instanceof io.github.potjerodekool.nabu.lang.model.element.TypeElement te ? te.getQualifiedName() : (result == null ? "null" : result.toString()))
                    + " err=" + (result instanceof io.github.potjerodekool.nabu.lang.model.element.TypeElement te2 && te2.isError()));
        } catch (java.io.IOException e) {
            // ignore
        }
    }

    @Override
    public Element resolve(final String name) {
        final var symbolResolverOptional = findSymbolResolver(declaredType);

        if (symbolResolverOptional.isPresent()) {
            final var symbolResolver = symbolResolverOptional.get();
            final var element = symbolResolver.resolve(name, declaredType);

            if (element != null) {
                return element;
            }
        }

        var currentClass = getCurrentClass();

        while (currentClass != null) {
            final var fieldOptional = findField(currentClass, name);

            if (fieldOptional.isPresent()) {
                return fieldOptional.get();
            }

            final var superclass = currentClass.getSuperclass();

            if (superclass instanceof DeclaredType superType) {
                currentClass = (TypeElement) superType.asElement();
            } else {
                currentClass = null;
            }
        }

        if (currentClass == null) {
            probeResolve("resolve", name, getCurrentClass(), null);
        }

        return parentScope != null ? parentScope.resolve(name) : null;
    }

    private java.util.Optional<VariableElement> findField(final TypeElement classElement,
                                                          final String name) {
        final var fieldOptional = ElementFilter.elements(
                        classElement,
                        element ->
                                element.getKind() == ElementKind.FIELD
                                        || element.getKind() == ElementKind.ENUM_CONSTANT,
                        VariableElement.class
                ).stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD
                        || elem.getKind() == ElementKind.ENUM_CONSTANT)
                .filter(elem -> elem.getSimpleName().equals(name))
                .findFirst();

        if (fieldOptional.isPresent()) {
            return fieldOptional;
        }

        for (final var interfaceType : classElement.getInterfaces()) {
            if (interfaceType instanceof DeclaredType interfaceDeclaredType
                    && interfaceDeclaredType.asElement() instanceof TypeElement interfaceElement) {
                final var interfaceField = findField(interfaceElement, name);

                if (interfaceField.isPresent()) {
                    return interfaceField;
                }
            }
        }

        final var globalScope = getGlobalScope();

        if (globalScope == null) {
            return java.util.Optional.empty();
        }

        final var compilerContext = globalScope.getCompilerContext();
        final var loaded = compilerContext.getClassElementLoader()
                .loadClass(findModuleElement(), classElement.getQualifiedName());

        if (loaded == null || loaded == classElement) {
            return java.util.Optional.empty();
        }

        return ElementFilter.elements(
                        loaded,
                        element ->
                                element.getKind() == ElementKind.FIELD
                                        || element.getKind() == ElementKind.ENUM_CONSTANT,
                        VariableElement.class
                ).stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD
                        || elem.getKind() == ElementKind.ENUM_CONSTANT)
                .filter(elem -> elem.getSimpleName().equals(name))
                .findFirst();
    }

    private Optional<ElementResolver> findSymbolResolver(final TypeMirror searchType) {
        final var globalScope = getGlobalScope();

        if (globalScope == null) {
            return Optional.empty();
        }

        final var compilerContext = globalScope.getCompilerContext();
        return compilerContext.findSymbolResolver(
                searchType,
                globalScope
        );
    }

    @Override
    public TypeMirror resolveType(final String name) {
        final var typeVariable = declaredType.getTypeArguments().stream()
                .filter(it -> it instanceof TypeVariable)
                .filter(it -> it.asElement().getSimpleName().equals(name))
                .map(it -> (TypeMirror) it)
                .findFirst();

        if (typeVariable.isPresent()) {
            return typeVariable.get();
        }

        var enclosing = getCurrentClass();

        while (enclosing != null) {
            var ancestor = enclosing;

            while (ancestor != null) {
                final var found = findMemberType(ancestor, name);

                if (found.isPresent()) {
                    probeResolve("resolveType", name, enclosing, found.get());
                    return found.get().asType();
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

        if (enclosing == null) {
            probeResolve("resolveType", name, getCurrentClass(), null);
        }

        return Scope.super.resolveType(name);
    }

    private java.util.Optional<TypeElement> findMemberType(final TypeElement classSymbol,
                                                           final String name) {
        return ElementFilter.elements(
                        classSymbol,
                        element ->
                                element.getKind().isClass()
                                        || element.getKind().isInterface()
                                        || element.getKind() == ElementKind.ENUM
                                        || element.getKind() == ElementKind.ANNOTATION_TYPE,
                        TypeElement.class
                ).stream()
                .filter(elem -> elem.getSimpleName().contentEquals(name))
                .findFirst();
    }

    @Override
    public Scope getParent() {
        return parentScope;
    }

    @Override
    public TypeElement getCurrentClass() {
        return declaredType.asTypeElement();
    }
}
