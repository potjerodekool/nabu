package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CWildcardType;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.ClassScope;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.builder.MethodInvocationTreeBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit;
import io.github.potjerodekool.nabu.type.BoundKind;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodResolverTest extends AbstractCompilerTest {

    private final Types types = getCompilerContext().getClassElementLoader().getTypes();
    private final MethodResolver methodResolver = getCompilerContext().getMethodResolver();

    @Test
    void resolveMethod() {
        final var module = getCompilerContext().getSymbolTable().getJavaBase();

        final var target = IdentifierTree.create("list");
        final var listClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.util.List");
        final var stringClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.String");
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
        final var module = getCompilerContext().getSymbolTable().getJavaBase();
        final var stringClass = (ClassSymbol) getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.String");

        stringClass.getMembers();

        final var localDateClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.time.LocalDate");

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
                        (Symbol) new VariableSymbolBuilderImpl()
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
                        (VariableSymbol) new VariableSymbolBuilderImpl()
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
        final var module = getCompilerContext().getSymbolTable().getJavaBase();
        final var comparableClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.Comparable");

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

        final var localDateClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.time.LocalDate");

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
                .simpleName("MyTestClass")
                .build();

        final var methodOptional = methodResolver.resolveMethod(methodInvocation, clazz, classScope);

        assertTrue(methodOptional.isPresent());

        final var resolvedMethod = methodOptional.get();

        assertEquals(ofMethod.asType(), resolvedMethod);
    }

    @Test
    void resolveExactMethod() {
        final var target = new CIdentifierTree("out");

        final var printStreamClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.io.PrintStream");
        final var outField = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("out")
                .type(printStreamClass.asType())
                .build();

        target.setSymbol(outField);

        final var stringClass = getCompilerContext().getClassElementLoader().loadClass(null, Constants.STRING);
        final var argument = new CLiteralExpressionTree("Hello world!");
        argument.setType(stringClass.asType());

        final var methodInvocation = new MethodInvocationTreeBuilder()
                .methodSelector(new CFieldAccessExpressionTree(
                        target,
                        new CIdentifierTree("println")
                ))
                .arguments(List.of(argument))
                .build();

        final var resolvedMethodOptional = methodResolver.resolveMethod(methodInvocation);
        final var resolvedMethod = resolvedMethodOptional.get();
        final var paramType = resolvedMethod.getParameterTypes().getFirst();
        assertEquals(stringClass.asType(), paramType);
    }

    @Test
    void resolveCollectMethod() {
        final var streamClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.util.stream.Stream");
        final var collectorClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.util.stream.Collector");
        final var listClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.util.List");
        final var stringClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.String");
        final var objectClass = getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Object");

        final var streamOfStringType = types.getDeclaredType(
                streamClass,
                stringClass.asType()
        );

        final var streamIdentifier = new CIdentifierTree("stream");
        streamIdentifier.setSymbol(
                new VariableSymbolBuilderImpl()
                        .kind(ElementKind.LOCAL_VARIABLE)
                        .simpleName("stream")
                        .type(streamOfStringType)
                        .build()
        );

        final var methodSelector = new CFieldAccessExpressionTree(
                streamIdentifier,
                new CIdentifierTree("collect")
        );

        final var collectorsIdentifier = new CIdentifierTree("collectors");

        final var stringListType = types.getDeclaredType(
                listClass,
                stringClass.asType()
        );

        final var wildcardType = new CWildcardType(
                objectClass.asType(),
                BoundKind.UNBOUND,
                null
        );

        final var collectorType = types.getDeclaredType(
                collectorClass,
                stringClass.asType(),
                wildcardType,
                stringListType
        );

        final var variableSymbol = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("collector")
                .type(collectorType)
                .build();

        collectorsIdentifier.setSymbol(variableSymbol);

        final var methodInvocation = new MethodInvocationTreeBuilder()
                .methodSelector(methodSelector)
                .arguments(List.of(collectorsIdentifier))
                .build();

        final var resolvedMethod = methodResolver.resolveMethod(methodInvocation).get();
        System.out.println(resolvedMethod);
    }

    @Test
    void test1() {
        addFakeClass("Company");
        addFakeClass("Employee");

        final var companyType = getCompilerContext().getClassElementLoader().loadClass(null, "Company").asType();
        final var employeeType = getCompilerContext().getClassElementLoader().loadClass(null, "Employee").asType();
        final var joinClass = getCompilerContext().getClassElementLoader().loadClass(null, "jakarta.persistence.criteria.Join");
        final var joinType = types.getDeclaredType(
                joinClass,
                companyType,
                employeeType
        );

        final var selectedVariable = new VariableSymbolBuilderImpl()
                .type(joinType)
                .simpleName("e")
                .build();

        final var selected = IdentifierTree.create("e");
        selected.setSymbol(selectedVariable);

        final var methodSelector = new FieldAccessExpressionBuilder()
                .selected(selected)
                .field(IdentifierTree.create("get"))
                .build();

        final var stringType = getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.String").asType();
        final var stringIdentifier = IdentifierTree.create("String");
        stringIdentifier.setType(stringType);

        final var argument = IdentifierTree.create("name");
        argument.setType(stringType);

        final var methodInvocation = new MethodInvocationTreeBuilder()
                .methodSelector(methodSelector)
                .typeArguments(List.of(stringIdentifier))
                .arguments(List.of(argument))
                .build();

        final var resolvedMethod = methodResolver.resolveMethod(methodInvocation);
        assertTrue(resolvedMethod.isPresent());
        final var actual = TypePrinter.print(resolvedMethod.get());
        final var expected = "jakarta.persistence.criteria.Path<java.lang.String> (java.lang.String)";
        assertEquals(expected, actual);
    }
}
