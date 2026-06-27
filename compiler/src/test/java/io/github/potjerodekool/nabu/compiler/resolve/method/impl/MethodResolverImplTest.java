package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.lang.model.element.Element;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;
import io.github.potjerodekool.nabu.compiler.type.impl.UndetVarType;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.test.JavaCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLambdaExpressionTree;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.TypePrinter;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.potjerodekool.nabu.test.TestUtils.parseJavaCode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Disabled
class MethodResolverImplTest extends JavaCompilerTest {

    @Test
    void resolveMethodCurrentClass() throws IOException {
        final MethodInvocationTree methodInvocationTree = parseJavaCode("""
                hello("Evert")
                """, Java20Parser::methodInvocation);

        final var stringType = parseType("java.lang.String");

        methodInvocationTree.getArguments()
                .getFirst()
                .setType(stringType);

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

        final var methodResolver = getCompilerContext().getMethodResolver();
        final var resolvedMethod = methodResolver.resolveMethod(methodInvocationTree, scope).get();

        assertEquals(helloMethod, resolvedMethod.getMethodSymbol());
    }

    @Test
    void resolveGet() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.get(1)", Java20Parser::methodInvocation);

        final DeclaredType listOfStringType = parseType("java.util.ArrayList<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listOfStringType);

        final Scope scope = mock(Scope.class);

        final var caller = getCompilerContext().getElementBuilders().typeElementBuilder()
                .kind(ElementKind.CLASS)
                .build();

        when(scope.getCurrentClass()).thenReturn(caller);

        final var intType = getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT);

        invocation.getArguments().getFirst()
                .setType(intType);

        final var methodResolver = getCompilerContext().getMethodResolver();

        final var resolveMethod = methodResolver.resolveMethod(
                invocation,
                scope
        ).get();

        final var actual = TypePrinter.print(resolveMethod);
        final var expected = "java.lang.String get(int)";
        assertEquals(expected, actual);
    }

    @Test
    void resolveForEach() throws IOException {
        final MethodInvocationTree invocation = parseJavaCode("list.forEach(consumer)", Java20Parser::methodInvocation);
        final var listOfStringType = parseType("java.util.ArrayList<java.lang.String>");

        final var selector = (FieldAccessExpressionTree) invocation.getMethodSelector();
        selector.getSelected().setType(listOfStringType);

        final Scope scope = mock(Scope.class);

        final var caller = getCompilerContext().getElementBuilders().typeElementBuilder()
                .kind(ElementKind.CLASS)
                .build();

        when(scope.getCurrentClass()).thenReturn(caller);

        final var stringConsumerType = parseType("java.util.function.Consumer<java.lang.String>");

        invocation.getArguments().getFirst()
                .setType(stringConsumerType);

        final var methodResolver = getCompilerContext().getMethodResolver();

        final var resolveMethod = methodResolver.resolveMethod(
                invocation,
                scope
        ).get();

        final var actual = TypePrinter.print(resolveMethod);
        final var expected = "void forEach(java.util.function.Consumer<? super java.lang.String>)";
        assertEquals(expected, actual);
    }

    @Test
    void visitDeclaredType() {
        //declaredType = Consumer<? super T>

        final var methodResolver = (MethodResolverImpl) getCompilerContext().getMethodResolver();
        final var methodInvocation = mock(MethodInvocationTree.class);
        when(methodInvocation.getTypeArguments())
                .thenReturn(List.of());

        final var currentClass = getCompilerContext()
                .getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.CLASS)
                .build();

        final var scope = mock(Scope.class);
        when(scope.getCurrentClass())
                .thenReturn(currentClass);

        final var listClass = loadClass("java.util.List");
        final var integerClass = loadClass(Constants.INTEGER);
        final var integerListClass = getCompilerContext().getTypes()
                .getDeclaredType(
                        listClass,
                        integerClass.asType()
                );

        //other = UndetVarType (delegate = MethodType(Integer>
        final var methodType = new CMethodType(
                null,
                null,
                List.of(),
                null,
                List.of(integerClass.asType()),
                List.of()
        );

        final var lambda = new CLambdaExpressionTree(
                List.of(),
                null,
                -1,
                -1
        );
        lambda.setType(new UndetVarType(methodType));

        methodResolver.getPotentiallyApplicableMethods(
                methodInvocation,
                integerListClass,
                "forEach",
                List.of(lambda),
                scope,
                false
        );
    }

    @Test
    void test() {
        final var listClass = loadClass("java.util.List");
        final var stringClass = loadClass("java.lang.String");
        final var listOfStringType = getCompilerContext().getTypes()
                .getDeclaredType(
                        listClass,
                        stringClass.asType()
                );

        final var methodCollection = new ArrayList<ExecutableType>();
        collectMethods(listOfStringType, methodCollection);
        final var forEarchMethods = methodCollection.stream()
                        .filter(it -> it.getMethodSymbol().getSimpleName().equals("forEach"))
                                .toList();

        System.out.println(methodCollection);
    }

    private void collectMethods(final DeclaredType declaredType,
                                final List<ExecutableType> methodCollection) {
        final var typeElement = declaredType.asTypeElement();
        final var methods = ElementFilter.methodsIn(typeElement.getEnclosedElements()).stream()
                .toList();

        final var map = SimpleTypeMapFiller.fill(declaredType);

        final var mapper = new SimpleTypeMapApplier(map, getCompilerContext().getTypes());
        final var methodTypes = methods.stream()
                .map(Element::asType)
                .map(methodType -> (ExecutableType) methodType.accept(mapper, null))
                .toList();

        methodCollection.addAll(methodTypes);

        final var superClazz = typeElement.getSuperclass();

        if (superClazz != null) {
            final var mappedType = mapType((DeclaredType) superClazz, map);
            collectMethods(mappedType, methodCollection);
        }

        typeElement.getInterfaces().stream()
                .map(it -> (DeclaredType) it)
                .forEach(iface -> {
                    final var mappedType = mapType(iface, map);
                    collectMethods(mappedType, methodCollection);
                });
    }

    private DeclaredType mapType(final DeclaredType declaredType,
                                 final Map<String, TypeMirror> map) {
        return (DeclaredType) SimpleTypeMapApplier.apply(
                map,
                declaredType,
                getCompilerContext().getTypes()
        );
    }
}

