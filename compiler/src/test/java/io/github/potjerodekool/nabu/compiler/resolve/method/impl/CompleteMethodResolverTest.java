package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;
import io.github.potjerodekool.nabu.compiler.type.impl.UndetVarType;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.test.JavaCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLambdaExpressionTree;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.TypePrinter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.github.potjerodekool.nabu.test.TestUtils.parseJavaCode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompleteMethodResolverTest extends JavaCompilerTest {

    private CompleteMethodResolver createResolver() {
        return new CompleteMethodResolver(
                getCompilerContext().getElements(),
                getCompilerContext().getTypes(),
                getCompilerContext().getTreeUtils()
        );
    }

    @Test
    void resolveSimpleMethodInCurrentClass() throws IOException {
        final MethodInvocationTree methodInvocationTree = parseJavaCode("""
                hello("Evert")
                """, Java20Parser::methodInvocation);

        final var stringType = parseType("java.lang.String");
        methodInvocationTree.getArguments().getFirst().setType(stringType);

        final Scope scope = mock(Scope.class);
        final var caller = getCompilerContext().getElementBuilders().typeElementBuilder()
                .kind(ElementKind.CLASS)
                .enclosingElement(PackageSymbol.UNNAMED_PACKAGE)
                .build();

        final var helloMethod = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("hello")
                .parameter(
                        getCompilerContext().getElementBuilders()
                                .variableElementBuilder()
                                .kind(ElementKind.PARAMETER)
                                .simpleName("message")
                                .type(stringType)
                                .build()
                )
                .returnType(getCompilerContext().getTypes().getNoType(TypeKind.VOID))
                .build();

        caller.addEnclosedElement(helloMethod);
        when(scope.getCurrentClass()).thenReturn(caller);

        final var resolvedMethod = createResolver()
                .resolveMethod(methodInvocationTree, scope).get();

        assertEquals(helloMethod, resolvedMethod.getMethodSymbol());
    }

    @Test
    void resolveGetOnParameterizedType() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.get(1)", Java20Parser::expression);
        final DeclaredType listOfStringType = parseType("java.util.ArrayList<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listOfStringType);

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        invocation.getArguments().getFirst().setType(
                getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT)
        );

        final var resolvedMethod = createResolver()
                .resolveMethod(invocation, scope).get();

        assertEquals("java.lang.String get(int)", TypePrinter.print(resolvedMethod));
    }

    @Test
    void resolveForEachWithConsumer() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.forEach(consumer)", Java20Parser::methodInvocation);
        final var listOfStringType = parseType("java.util.ArrayList<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listOfStringType);

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        invocation.getArguments().getFirst().setType(
                parseType("java.util.function.Consumer<java.lang.String>")
        );

        final var resolvedMethod = createResolver()
                .resolveMethod(invocation, scope).get();

        assertEquals("void forEach(java.util.function.Consumer<? super java.lang.String>)",
                TypePrinter.print(resolvedMethod));
    }

    @Test
    void getPotentiallyApplicableWithLambdaArgument() {
        final var resolver = createResolver();
        final var methodInvocation = mock(MethodInvocationTree.class);
        when(methodInvocation.getTypeArguments()).thenReturn(List.of());

        final var currentClass = getCompilerContext().getElementBuilders()
                .typeElementBuilder().kind(ElementKind.CLASS).build();

        final var scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(currentClass);

        final var listClass = loadClass("java.util.List");
        final var integerClass = loadClass(Constants.INTEGER);
        final var integerListClass = getCompilerContext().getTypes()
                .getDeclaredType(listClass, integerClass.asType());

        final var methodType = new CMethodType(
                null, null, List.of(), null,
                List.of(integerClass.asType()), List.of()
        );

        final var lambda = new CLambdaExpressionTree(List.of(), null, -1, -1);
        lambda.setType(new UndetVarType(methodType));

        var results = resolver.getPotentiallyApplicableMethods(
                methodInvocation, integerListClass,
                "forEach", List.of(lambda), scope, false
        );

        assertFalse(results.isEmpty());
    }

    @Test
    void collectMethodsCollectsFromTypeHierarchy() {
        final var listClass = loadClass("java.util.List");
        final var stringClass = loadClass("java.lang.String");
        final var listOfStringType = getCompilerContext().getTypes()
                .getDeclaredType(listClass, stringClass.asType());

        final var methodCollection = new ArrayList<ExecutableType>();
        createResolver().collectMethods(listOfStringType, methodCollection, false);

        final var forEachMethods = methodCollection.stream()
                .filter(it -> "forEach".equals(it.getMethodSymbol().getSimpleName()))
                .toList();

        assertFalse(forEachMethods.isEmpty());
    }

    @Test
    void resolveMethodWithPrimitiveArg() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.indexOf(42)", Java20Parser::methodInvocation);
        final var listOfIntegerType = parseType("java.util.ArrayList<java.lang.Integer>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listOfIntegerType);

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        invocation.getArguments().getFirst().setType(
                getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT)
        );

        assertNotNull(createResolver().resolveMethod(invocation, scope).get());
    }

    @Test
    void resolveLengthOnString() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("\"hello\".length()", Java20Parser::expression);
        final var stringType = parseType("java.lang.String");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(stringType);

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        final var resolvedMethod = createResolver()
                .resolveMethod(invocation, scope).get();

        assertEquals(TypeKind.INT, resolvedMethod.getReturnType().getKind());
    }

    @Test
    void resolveSizeOnList() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.size()", Java20Parser::methodInvocation);
        final var listType = parseType("java.util.ArrayList<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listType);

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        final var resolvedMethod = createResolver()
                .resolveMethod(invocation, scope).get();

        assertEquals(TypeKind.INT, resolvedMethod.getReturnType().getKind());
    }

    @Test
    void resolveToStringOnList() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.toString()", Java20Parser::methodInvocation);
        final var listType = parseType("java.util.ArrayList<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listType);

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        final var resolvedMethod = createResolver()
                .resolveMethod(invocation, scope).get();

        assertEquals(Constants.STRING, resolvedMethod.getReturnType().getClassName());
    }

    @Test
    void resolveWithExplicitTypeArgument() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode(
                "list.<String>get(1)", Java20Parser::methodInvocation
        );

        final var typeArg = invocation.getTypeArguments().getFirst();
        typeArg.setType(loadClass(Constants.STRING).asType());

        final var listType = parseType("java.util.ArrayList<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listType);

        invocation.getArguments().getFirst().setType(
                getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT)
        );

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        final var resolvedMethod = createResolver()
                .resolveMethod(invocation, scope).get();

        assertEquals("java.lang.String get(int)", TypePrinter.print(resolvedMethod));
    }

    @Test
    void isPotentiallyApplicableNameMatches() {
        final var packageSymbol = PackageSymbol.UNNAMED_PACKAGE;

        final var caller = getCompilerContext().getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.CLASS)
                .enclosingElement(packageSymbol)
                .build();

        final var method = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("foo")
                .returnType(getCompilerContext().getTypes().getNoType(TypeKind.VOID))
                .enclosingElement(caller)
                .build();

        assertTrue(createResolver().isPotentiallyApplicable(
                "foo", List.of(), (ExecutableType) method.asType(), caller, false
        ));
    }

    @Test
    void isPotentiallyApplicableNameDoesNotMatch() {
        final var caller = getCompilerContext().getElementBuilders()
                .typeElementBuilder().kind(ElementKind.CLASS).build();

        final var method = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("foo")
                .returnType(getCompilerContext().getTypes().getNoType(TypeKind.VOID))
                .build();

        assertFalse(createResolver().isPotentiallyApplicable(
                "bar", List.of(), (ExecutableType) method.asType(), caller, false
        ));
    }

    @Test
    void wideningPrimitiveByteToShort() {
        final var types = getCompilerContext().getTypes();
        assertTrue(createResolver().isWideningPrimitive(
                types.getPrimitiveType(TypeKind.BYTE),
                types.getPrimitiveType(TypeKind.SHORT)
        ));
    }

    @Test
    void wideningPrimitiveIntToDouble() {
        final var types = getCompilerContext().getTypes();
        assertTrue(createResolver().isWideningPrimitive(
                types.getPrimitiveType(TypeKind.INT),
                types.getPrimitiveType(TypeKind.DOUBLE)
        ));
    }

    @Test
    void wideningPrimitiveDoubleToIntNotAllowed() {
        final var types = getCompilerContext().getTypes();
        assertFalse(createResolver().isWideningPrimitive(
                types.getPrimitiveType(TypeKind.DOUBLE),
                types.getPrimitiveType(TypeKind.INT)
        ));
    }

    @Test
    void boxingCompatibleIntToInteger() {
        final var types = getCompilerContext().getTypes();
        final var integerClass = loadClass(Constants.INTEGER);
        assertTrue(createResolver().isBoxingCompatible(
                types.getPrimitiveType(TypeKind.INT),
                integerClass.asType()
        ));
    }

    @Test
    void boxingCompatibleBooleanToBoolean() {
        final var types = getCompilerContext().getTypes();
        final var booleanClass = loadClass(Constants.BOOLEAN);
        assertTrue(createResolver().isBoxingCompatible(
                types.getPrimitiveType(TypeKind.BOOLEAN),
                booleanClass.asType()
        ));
    }

    @Test
    void unboxingCompatibleIntegerToInt() {
        final var types = getCompilerContext().getTypes();
        final var integerClass = loadClass(Constants.INTEGER);
        assertTrue(createResolver().isUnboxingCompatible(
                integerClass.asType(),
                types.getPrimitiveType(TypeKind.INT)
        ));
    }

    @Test
    void resolveAddOnCollectionOfStrings() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.add(\"hello\")", Java20Parser::expression);
        final var collectionType = parseType("java.util.Collection<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(collectionType);

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        invocation.getArguments().getFirst().setType(
                parseType("java.lang.String")
        );

        final var resolvedMethod = createResolver()
                .resolveMethod(invocation, scope);

        assertTrue(resolvedMethod.isPresent(), "Expected to resolve add(String) on Collection<String>");
    }

    @Test
    void collectMethodsFindsInheritedMethods() throws IOException {
        final DeclaredType collectionType = parseType("java.util.Collection<java.lang.String>");
        final var methodCollection = new ArrayList<ExecutableType>();
        createResolver().collectMethods(collectionType, methodCollection, false);

        final var addMethods = methodCollection.stream()
                .filter(it -> "add".equals(it.getMethodSymbol().getSimpleName()))
                .toList();

        assertFalse(addMethods.isEmpty(), "Expected to find add() method in Collection<String> hierarchy");
        assertEquals(1, addMethods.size(), "Expected exactly one add method");

        final var addMethod = addMethods.getFirst();
        final var paramTypes = addMethod.getParameterTypes();
        assertEquals(1, paramTypes.size());
        assertEquals("java.lang.String", TypePrinter.print(paramTypes.getFirst()));
    }

    @Test
    void isAssignableVariableTypeToTypeVariable() {
        final var types = getCompilerContext().getTypes();
        final var stringClass = loadClass("java.lang.String");

        final var stringType = stringClass.asType();
        final var typeVariable = types.getTypeVariable("T", stringType, null);

        assertTrue(types.isAssignable(stringType, (TypeMirror) typeVariable),
                "String should be assignable to T where T has upper bound String");
    }

    @Test
    void isAssignableToTypeVariableWithIndirectUpperBound() {
        final var types = getCompilerContext().getTypes();
        final var stringClass = loadClass("java.lang.String");
        final var objectClass = loadClass("java.lang.Object");

        final var stringType = stringClass.asType();
        final var objectType = objectClass.asType();

        final var tVar = types.getTypeVariable("T", objectType, null);
        final var sVar = types.getTypeVariable("S", tVar, null);

        assertTrue(types.isAssignable(stringType, (TypeMirror) sVar),
                "String should be assignable to S where S extends T and T extends Object");
    }

    @Test
    void resolveMethodWithMethodTypeVariableAsParam() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.add(\"hello\")", Java20Parser::expression);
        final DeclaredType listOfStringType = parseType("java.util.ArrayList<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listOfStringType);

        final Scope scope = mock(Scope.class);
        when(scope.getCurrentClass()).thenReturn(
                getCompilerContext().getElementBuilders().typeElementBuilder()
                        .kind(ElementKind.CLASS).build()
        );

        invocation.getArguments().getFirst().setType(
                parseType("java.lang.String")
        );

        final var resolvedMethod = createResolver()
                .resolveMethod(invocation, scope);

        assertTrue(resolvedMethod.isPresent(), "Expected to resolve add(String) on ArrayList<String>");
    }

    @Test
    void transformSubstitutesTypeVariables() {
        final var types = getCompilerContext().getTypes();
        final var stringClass = loadClass(Constants.STRING);
        final var objectClass = loadClass(Constants.OBJECT);

        final var method = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("identity")
                .returnType(objectClass.asType())
                .build();

        final var result = createResolver().transform(
                types.getDeclaredType(objectClass),
                method,
                List.of(),
                List.of()
        );

        assertNotNull(result.first());
    }
}
