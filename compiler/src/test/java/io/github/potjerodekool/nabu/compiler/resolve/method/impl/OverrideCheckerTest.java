package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OverrideCheckerTest extends AbstractCompilerTest {

    private OverrideChecker createChecker() {
        return new OverrideChecker(getCompilerContext().getTypes());
    }

    private TypeElement createClass(final String name) {
        final var packageSymbol = getCompilerContext()
                .getElementBuilders()
                .packageElementBuilder()
                .createUnnamed();

        return getCompilerContext().getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.CLASS)
                .simpleName(name)
                .enclosingElement(packageSymbol)
                .build();
    }

    private ExecutableElement createMethod(final String name,
                                            final TypeElement returnType,
                                            final TypeElement... paramTypes) {
        final var builder = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName(name)
                .returnType(returnType != null ? returnType.asType()
                        : getCompilerContext().getTypes().getNoType(TypeKind.VOID));

        for (int i = 0; i < paramTypes.length; i++) {
            final var param = getCompilerContext().getElementBuilders()
                    .variableElementBuilder()
                    .kind(ElementKind.PARAMETER)
                    .simpleName("p" + i)
                    .type(paramTypes[i].asType())
                    .build();
            builder.parameter(param);
        }

        return builder.build();
    }

    private void makeStatic(final ExecutableElement method) {
        final var sym = (Symbol) method;
        sym.setFlags(sym.getFlags() | Flags.STATIC);
    }

    private void makePrivate(final ExecutableElement method) {
        final var sym = (Symbol) method;
        sym.setFlags(sym.getFlags() | Flags.PRIVATE);
    }

    private void makeFinal(final ExecutableElement method) {
        final var sym = (Symbol) method;
        sym.setFlags(sym.getFlags() | Flags.FINAL);
    }

    private void makeProtected(final ExecutableElement method) {
        final var sym = (Symbol) method;
        sym.setFlags(sym.getFlags() | Flags.PROTECTED);
    }

    @Test
    void sameMethodDoesNotOverrideItself() {
        final var checker = createChecker();
        final var method = createMethod("foo", null);
        final var type = createClass("Test");
        type.addEnclosedElement(method);

        assertFalse(checker.overrides(method, method, type));
    }

    @Test
    void methodWithDifferentNameDoesNotOverride() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");

        final var parentMethod = createMethod("foo", null);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("bar", null);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void methodWithSameNameAndParamsOverrides() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");
        final var stringClass = loadClass("java.lang.String");

        final var parentMethod = createMethod("foo", null, stringClass);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("foo", null, stringClass);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertTrue(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void staticMethodDoesNotOverride() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");

        final var parentMethod = createMethod("foo", null);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("foo", null);
        makeStatic(childMethod);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void privateMethodCannotBeOverridden() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");

        final var parentMethod = createMethod("foo", null);
        makePrivate(parentMethod);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("foo", null);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void finalMethodCannotBeOverridden() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");

        final var parentMethod = createMethod("foo", null);
        makeFinal(parentMethod);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("foo", null);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void differentParameterTypesDoNotOverride() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");
        final var stringClass = loadClass("java.lang.String");
        final var intClass = loadClass("java.lang.Integer");

        final var parentMethod = createMethod("foo", null, stringClass);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("foo", null, intClass);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void differentNumberOfParametersDoNotOverride() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");
        final var stringClass = loadClass("java.lang.String");

        final var parentMethod = createMethod("foo", null, stringClass);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("foo", null);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void covariantReturnTypeOverrides() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");
        final var objectClass = loadClass("java.lang.Object");
        final var stringClass = loadClass("java.lang.String");

        final var parentMethod = createMethod("foo", objectClass);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("foo", stringClass);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertTrue(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void methodInNonSubclassDoesNotOverride() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var unrelatedClass = createClass("Unrelated");

        final var parentMethod = createMethod("foo", null);
        parentClass.addEnclosedElement(parentMethod);

        final var unrelatedMethod = createMethod("foo", null);
        unrelatedClass.addEnclosedElement(unrelatedMethod);

        assertFalse(checker.overrides(unrelatedMethod, parentMethod, unrelatedClass));
    }

    @Test
    void protectedMethodCanBeOverriddenInDifferentPackage() {
        final var checker = createChecker();
        final var parentClass = createClass("Parent");
        final var childClass = createClass("Child");

        final var parentMethod = createMethod("foo", null);
        makeProtected(parentMethod);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createMethod("foo", null);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertTrue(checker.overrides(childMethod, parentMethod, childClass));
    }
}
