package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.ClassScope;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.builder.MethodInvocationTreeBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodResolverTest extends AbstractCompilerTest {

    private final ClassElementLoader loader = getCompilerContext().getClassElementLoader();
    private final Types types = loader.getTypes();
    private final MethodResolver methodResolver = getCompilerContext().getMethodResolver();

    @Test
    void resolveMethod() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();

        final var target = IdentifierTree.create("list");
        final var listClass = loader.loadClass(module, "java.util.List");
        final var stringClass = loader.loadClass(module, "java.lang.String");
        final var stringListType = types.getDeclaredType(
                listClass,
                stringClass.asType()
        );

        final var intType = types.getPrimitiveType(TypeKind.INT);

        final var listVariable = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("list")
                .type(stringListType)
                .build();

        target.setSymbol(listVariable);


        final var indexArg = TreeMaker.literalExpressionTree(0, -1, -1);
        indexArg.setType(intType);

        final var elementArg = IdentifierTree.create("value");
        final var variable = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("value")
                .type(stringClass.asType())
                .build();

        elementArg.setSymbol(variable);


        final var methodInvocationTree = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        target,
                        IdentifierTree.create("add")
                ),
                List.of(),
                List.of(indexArg, elementArg),
                -1,
                -1
        );

        final var methodType = methodResolver.resolveMethod(methodInvocationTree)
                        .get();
        assertNotNull(methodType);

        final var printer = new TypePrinter();
        methodType.accept(printer, null);
        final var actual = printer.getText();

        assertEquals("""
                void (int, java.lang.String)""", actual);

    }

    @Test
    void resolveMethod2() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();
        final var stringClass = loader.loadClass(module, "java.lang.String");
        final var localDateClass = loader.loadClass(module, "java.time.LocalDate");

        final var objectType = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Object")
                .build()
                .asType();

        final var typeVar = types.getTypeVariable("Y", objectType, null);


        final var pathClass = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Path")
                .typeParameter((TypeParameterElement) types.getTypeVariable("X", objectType, null).asElement())
                .build();

        final var personClass = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Person")
                .enclosedElement(
                        new VariableSymbolBuilderImpl()
                                .kind(ElementKind.FIELD)
                                .simpleName("birthDay")
                                .type(localDateClass.asType())
                                .build()
                ).build();

        final var returnType = types.getDeclaredType(
                pathClass,
                typeVar
        );

        final var getMethod = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .enclosingElement(pathClass)
                .returnType(returnType)
                .typeParameter((TypeParameterElement) typeVar.asElement())
                .simpleName("get")
                .parameter(
                        new VariableSymbolBuilderImpl()
                                .simpleName("attributeName")
                                .type(stringClass.asType())
                                .build()
                )
                .build();

        pathClass.addEnclosedElement(getMethod);

        final var target = IdentifierTree.create("e");
        target.setType(types.getDeclaredType(
                pathClass,
                personClass.asType()
        ));

        final var pathVariable = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("list")
                .type(types.getDeclaredType(
                        pathClass,
                        personClass.asType()
                ))
                .build();

        target.setSymbol(pathVariable);

        final var typeArg = IdentifierTree.create("LocalDate");
        typeArg.setType(localDateClass.asType());

        final var literal = TreeMaker.literalExpressionTree("birthDay", -1, -1);
        literal.setType(stringClass.asType());

        final var methodInvocationTree = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        target,
                    IdentifierTree.create("get")
                ),
                List.of(typeArg),
                List.of(literal),
                -1,
                -1
        );

        final var methodType = methodResolver.resolveMethod(methodInvocationTree)
                        .get();
        assertNotNull(methodType);

        final var printer = new TypePrinter();
        methodType.accept(printer, null);
        final var actual = printer.getText();

        assertEquals("""
                Path<java.time.LocalDate> (java.lang.String)""", actual);
    }

    @Test
    void test() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();
        final var comparableClass = loader.loadClass(module, "java.lang.Comparable");

        final var objectType = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Object")
                .build()
                .asType();

        final var typeVariable = types.getTypeVariable(
                "Y",
                types.getDeclaredType(
                        null,
                        comparableClass,
                        types.getWildcardType(
                                null,
                                types.getTypeVariable("Y", objectType, null)
                        )
                ),
                null
        );

        final var predicateClass = new ClassSymbolBuilder()
                .kind(ElementKind.INTERFACE)
                .simpleName("Predicate")
                .build();

        final var expressionClass = new ClassSymbolBuilder()
                .kind(ElementKind.INTERFACE)
                .simpleName("Expression")
                .typeParameter((TypeParameterElement) types.getTypeVariable("T", objectType, null).asElement())
                .build();

        final var methodType = types.getExecutableType(
                new MethodSymbolBuilderImpl()
                        .build(),
                List.of(typeVariable),
                types.getDeclaredType(
                        null,
                        predicateClass
                ),
                List.of(
                        types.getDeclaredType(
                                null,
                                expressionClass,
                                types.getWildcardType(
                                        types.getTypeVariable("Y", objectType, null),
                                        null
                                )
                        ),
                        types.getTypeVariable("Y", objectType, null)
                ),
                List.of()
        );

        final var localDateClass = loader.loadClass(module, "java.time.LocalDate");

        final var pathClass = new ClassSymbolBuilder()
                .kind(ElementKind.INTERFACE)
                .simpleName("Path")
                .typeParameter((TypeParameterElement) types.getTypeVariable("T", objectType, null).asElement())
                .build();

        final var argTypes = List.<TypeMirror>of(
                types.getDeclaredType(
                        null,
                        pathClass,
                        types.getDeclaredType(
                                null,
                                localDateClass
                        )
                ),
                types.getDeclaredType(
                        null,
                        localDateClass
                )
        );


        final var result = methodResolver.transform(
                methodType,
                List.of(),
                argTypes);

        final var printer = new TypePrinter();
        result.accept(printer, null);
        final var actual = printer.getText();
        assertEquals("Predicate (Expression<java.time.LocalDate>, java.time.LocalDate)", actual);
    }

    @Test
    void resolveMethodStaticImport() {
        final var methodInvocation = new MethodInvocationTreeBuilder()
                .methodSelector(new CIdentifierTree("of"))
                .build();

        final var compilationUnit = new CCompilationTreeUnit(
                null,
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var ofMethod = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("of")
                .build();

        compilationUnit.getNamedImportScope().define(ofMethod);

        final var globalScope = new GlobalScope(
                compilationUnit,
                null
        );

        final var classScope = new ClassScope(
                null,
                globalScope,
                compilationUnit,
                null
        );

        final var clazz = new ClassSymbolBuilder()
                .build();

        final var methodOptional = methodResolver.resolveMethod(methodInvocation, clazz, classScope);

        assertTrue(methodOptional.isPresent());

        final var resolvedMethod = methodOptional.get();

        assertEquals(ofMethod.asType(), resolvedMethod);
    }

    @Test
    void resolveExactMethod() {
        final var target = new CIdentifierTree("out");

        final var printStreamClass = loader.loadClass(null, "java.io.PrintStream");
        final var outField = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("out")
                .type(printStreamClass.asType())
                .build();

        target.setSymbol(outField);

        final var stringClass = loader.loadClass(null, Constants.STRING);
        final var argument = new CLiteralExpressionTree("Hello world!");
        argument.setType(stringClass.asType());

        final var methodInvocation = new MethodInvocationTreeBuilder()
                .methodSelector(new CFieldAccessExpressionTree(
                        target,
                        new CIdentifierTree("println")
                ))
                        .arguments(List.of(argument))
                                .build();

        final var resolvedMethod =  methodResolver.resolveMethod(methodInvocation).get();
        final var paramType = resolvedMethod.getParameterTypes().getFirst();

        assertEquals(stringClass.asType(), paramType);
    }
}