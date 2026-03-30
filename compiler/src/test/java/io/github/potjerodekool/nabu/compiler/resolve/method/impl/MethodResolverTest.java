package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.UndetVarType;
import io.github.potjerodekool.nabu.resolve.scope.ClassScope;
import io.github.potjerodekool.nabu.resolve.scope.FunctionScope;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.test.NabuTreeParser;
import io.github.potjerodekool.nabu.testing.TreePaths;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.builder.MethodInvocationTreeBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLambdaExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.TypePrinter;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodResolverTest extends AbstractCompilerTest {

    private Types types;
    private MethodResolverImpl methodResolver;

    @BeforeEach
    void setup() {
        types = getCompilerContext().getTypes();
        methodResolver = (MethodResolverImpl) getCompilerContext().getMethodResolver();
    }

    @Test
    void resolveVarargsOverload() {
        final var javaBase = getCompilerContext().getModules().getJavaBase();
        final var module = getCompilerContext().getModules().getUnnamedModule();
        final var stringClass = getCompilerContext().getClassElementLoader().loadClass(javaBase, "java.lang.String");

        final var utilClass = getCompilerContext().getClassElementLoader()
                .loadClass(module, "foo.Util");

        final var utilVar = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("u")
                .type(utilClass.asType())
                .build();

        final var target = IdentifierTree.create("u");
        target.setSymbol(utilVar);

        final var arg1 = new CLiteralExpressionTree("x");
        arg1.setType(stringClass.asType());
        final var arg2 = new CLiteralExpressionTree("y");
        arg2.setType(stringClass.asType());
        final var arg3 = new CLiteralExpressionTree("z");
        arg3.setType(stringClass.asType());

        final var methodInvocation = new MethodInvocationTreeBuilder()
                .methodSelector(new CFieldAccessExpressionTree(target, new CIdentifierTree("sum")))
                .arguments(List.of(arg1, arg2, arg3))
                .build();

        final var resolved = methodResolver.resolveMethod(methodInvocation).orElse(null);
        assertNotNull(resolved);
        // Expect the varargs overload, i.e., parameters: String, String[]
        assertEquals(2, resolved.getParameterTypes().size());
        assertEquals(stringClass.asType(), resolved.getParameterTypes().getFirst());
        //assertEquals(varargArrayType, resolved.getParameterTypes().get(1));
    }

    @Test
    void resolveGenericInferenceWithoutExplicitTypeArgs() {
        final var module = getCompilerContext().getModules().getJavaBase();
        final var stringClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.String");
        final var objectType = new ClassSymbolBuilder().kind(ElementKind.CLASS).simpleName("Object").build().asType();

        // class Box { <T> T id(T x); }
        final var boxClass = new ClassSymbolBuilder().kind(ElementKind.CLASS).simpleName("Box")
                .typeParameter((TypeParameterElement) getCompilerContext().getTypes().getTypeVariable("T", objectType, null).asElement())
                .build();

        final var typeVarT = getCompilerContext().getTypes().getTypeVariable("T", objectType, null);

        final var idMethod = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .enclosingElement(boxClass)
                .simpleName("id")
                .typeParameter((TypeParameterElement) typeVarT.asElement())
                .parameter(new VariableSymbolBuilderImpl().simpleName("x").type(typeVarT).build())
                .returnType(typeVarT)
                .build();

        boxClass.addEnclosedElement(idMethod);

        final var stringBoxType = types.getDeclaredType(
                boxClass,
                stringClass.asType()
        );

        final var boxVar = new VariableSymbolBuilderImpl().kind(ElementKind.LOCAL_VARIABLE).simpleName("b").type(stringBoxType).build();
        final var target = IdentifierTree.create("b");
        target.setSymbol(boxVar);

        final var arg = new CLiteralExpressionTree("hello");
        arg.setType(stringClass.asType());

        final var methodInvocation = new MethodInvocationTreeBuilder()
                .methodSelector(new CFieldAccessExpressionTree(target, new CIdentifierTree("id")))
                .arguments(List.of(arg))
                .build();

        final var resolved = methodResolver.resolveMethod(methodInvocation).orElse(null);
        assertNotNull(resolved);
        // After inference, return type should be String

        final var expected = TypePrinter.print(stringClass.asType());
        final var actual = TypePrinter.print(resolved.getReturnType());

        assertEquals(expected, actual);
    }

    @Test
    void resolveMostSpecificOverload() {
        final var javaBase = getCompilerContext().getModules().getJavaBase();
        final var module = getCompilerContext().getModules().getUnnamedModule();
        final var integerClass = getCompilerContext().getClassElementLoader().loadClass(javaBase, "java.lang.Integer");

        final var svcClass = getCompilerContext().getClassElementLoader()
                .loadClass(module, "foo.Svc");

        final var svcVar = new VariableSymbolBuilderImpl().kind(ElementKind.LOCAL_VARIABLE).simpleName("s").type(svcClass.asType()).build();
        final var target = IdentifierTree.create("s");
        target.setSymbol(svcVar);

        final var arg = new CLiteralExpressionTree(1);
        arg.setType(integerClass.asType());

        final var methodInvocation = new MethodInvocationTreeBuilder()
                .methodSelector(new CFieldAccessExpressionTree(target, new CIdentifierTree("m")))
                .arguments(List.of(arg))
                .build();

        final var resolved = methodResolver.resolveMethod(methodInvocation).orElse(null);
        assertNotNull(resolved);
        // Most specific should be the Integer overload
        assertEquals(integerClass.asType(), resolved.getParameterTypes().getFirst());
    }

    @Test
    void resolveInheritedMethodFromSuperclass() {
        final var module = getCompilerContext().getModules().getJavaBase();
        final var stringClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.String");

        final var superClass = new ClassSymbolBuilder().kind(ElementKind.CLASS).simpleName("Super").build();
        final var subClass = new ClassSymbolBuilder().kind(ElementKind.CLASS).simpleName("Sub").build();
        subClass.setSuperClass(superClass.asType());

        final var m = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .enclosingElement(superClass)
                .simpleName("name")
                .returnType(stringClass.asType())
                .build();
        superClass.addEnclosedElement(m);

        final var subVar = new VariableSymbolBuilderImpl().kind(ElementKind.LOCAL_VARIABLE).simpleName("x").type(subClass.asType()).build();
        final var target = IdentifierTree.create("x");
        target.setSymbol(subVar);

        final var invocation = new MethodInvocationTreeBuilder()
                .methodSelector(new CFieldAccessExpressionTree(target, new CIdentifierTree("name")))
                .arguments(List.of())
                .build();

        final var resolved = methodResolver.resolveMethod(invocation).orElse(null);
        assertNotNull(resolved);

        final var expected = TypePrinter.print(stringClass.asType());
        final var actual = TypePrinter.print(resolved.getReturnType());

        assertEquals(expected, actual);
    }

    @Test
    void resolveMethod() {
        final var module = getCompilerContext().getModules().getJavaBase();

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

        final var methodType = methodResolver.resolveMethod(methodInvocationTree).orElse(null);
        assertNotNull(methodType);

        final var actual = TypePrinter.print(methodType);

        assertEquals("""
                void add(int, java.lang.String)""", actual);

    }

    @Test
    void resolveMethod2() {
        final var loader = getCompilerContext().getClassElementLoader();
        final var javaBase = getCompilerContext().getModules().getJavaBase();
        final var unnamedModule = getCompilerContext().getModules().getUnnamedModule();

        final var localDateClass = (ClassSymbol) loader.loadClass(javaBase, "java.time.LocalDate");
        localDateClass.complete();

        final var pathClass = (ClassSymbol) loader.loadClass(unnamedModule, "jakarta.persistence.criteria.Path");
        pathClass.complete();

        final var personClass = (ClassSymbol) loader.loadClass(unnamedModule, "foo.Person");

        final var pathVariable = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("e")
                .type(types.getDeclaredType(
                        pathClass,
                        personClass.asType()
                ))
                .build();

        final var scope = new FunctionScope(
                null,
                null
        );

        scope.define(pathVariable);

        final MethodInvocationTree methodInvocationTree = NabuTreeParser.parse(
                        """
                        e.<java.time.LocalDate>get("birthDay")""",
                NabuParser::functionInvocation,
                getCompilerContext(),
                scope
        );

        final var methodType = methodResolver.resolveMethod(methodInvocationTree).orElse(null);
        assertNotNull(methodType);

        final var actual = TypePrinter.print(methodType);

        assertEquals("""
                <Y:java.lang.Object> jakarta.persistence.criteria.Path<java.time.LocalDate> get(java.lang.String)""", actual);
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
                getCompilerContext()
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
        final var javaBase = getCompilerContext().getModules().getJavaBase();

        final var target = new CIdentifierTree("out");

        final var printStreamClass = getCompilerContext().getClassElementLoader().loadClass(javaBase, "java.io.PrintStream");
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
        final var resolvedMethod = resolvedMethodOptional.orElse(null);
        assertNotNull(resolvedMethod);
        final var paramType = resolvedMethod.getParameterTypes().getFirst();
        assertEquals(stringClass.asType(), paramType);
    }

    @Disabled
    @Test
    void resolveCollectMethod() {
        final var methodInvocation = NabuTreeParser.<MethodInvocationTree>parse(
                "stream.collect(collector)",
                NabuParser::expression,
                getCompilerContext(),
                new FunctionScope(null, null)
        );

        TreePaths.<IdentifierTree>select(methodInvocation, "stream")
                .setSymbol(
                        localVariableSymbol("stream", "java.util.stream.Stream<java.lang.String>")
                );

        final var variableSymbol = localVariableSymbol(
                "collector",
                "java.util.stream.Collector<java.lang.String,?,java.util.List<java.lang.String>>"
        );

        methodInvocation.getArguments().getFirst().setSymbol(variableSymbol);

        final var resolvedMethod = methodResolver.resolveMethod(methodInvocation).orElse(null);
        assertNotNull(resolvedMethod);
        final var actual = TypePrinter.print(resolvedMethod);
        final var expected = "<R:java.lang.Object, A:java.lang.Object> java.util.List<java.lang.String> collect(java.util.stream.Collector<? super java.lang.String, java.lang.Object, java.util.List<java.lang.String>>)";
        assertEquals(expected, actual);
    }

    @Disabled
    @Test
    void resolveCollectMethod2() {
        final var methodInvocation = NabuTreeParser.<MethodInvocationTree>parse(
                "stream.collect(Collectors.toSet())",
                NabuParser::expression,
                getCompilerContext(),
                new FunctionScope(null, null)
        );

        TreePaths.<IdentifierTree>select(methodInvocation, "stream")
                .setSymbol(
                        localVariableSymbol("stream", "java.util.stream.Stream<java.lang.String>")
                );

        final var toSetInvocation = (MethodInvocationTree) methodInvocation.getArguments().getFirst();
        TreePaths.select(toSetInvocation, "Collectors")
                .setType(loadClass("java.util.stream.Collectors").asType());

        final var toSetMethod = methodResolver.resolveMethod(toSetInvocation);
        toSetInvocation.setMethodType(toSetMethod.orElse(null));

        final var resolvedMethod = methodResolver.resolveMethod(methodInvocation).orElse(null);
        assertNotNull(resolvedMethod);
        final var actual = TypePrinter.print(resolvedMethod);
        final var expected = "<R:java.lang.Object, A:java.lang.Object> java.util.Set<java.lang.String> collect(java.util.stream.Collector<? super java.lang.String, java.lang.Object, java.util.Set<java.lang.String>>)";
        assertEquals(expected, actual);
    }

    @Disabled
    @Test
    void testTransform() {
        final var targetType = (DeclaredType) loadClass("java.util.stream.Stream<java.lang.String>").asType();

        final var rTypeVariable = new CTypeVariable("R");
        final var aTypeVariable = new CTypeVariable("A");

        final var method = new MethodSymbolBuilderImpl()
                .simpleName("collect")
                .parameter(
                        variableSymbol(ElementKind.PARAMETER, "collector", "java.util.stream.Collector<? super T, A, R>")
                )
                .typeParameters(
                        List.of(
                                rTypeVariable.asElement(),
                                aTypeVariable.asElement()
                        )
                )
                .returnType(rTypeVariable)
                .build();
        final var typeArguments = new ArrayList<TypeMirror>();
        // "java.util.stream.Collector<T extends java.lang.Object, ?, java.util.Set<T extends java.lang.Object>>"

        final var collectorType = (DeclaredType) loadClass("java.util.stream.Collector").asType();

        final var setType = types.getDeclaredType(
                loadClass("java.util.Set"),
                new CTypeVariable("T", null, null, loadClass("java.lang.Object").asType())
        );

        final var argumentType = types.getDeclaredType(
                collectorType.asTypeElement(),
                loadClass("T extends java.lang.Object").asType(),
                loadClass("java.lang.Object").asType(),
                setType
        );

        final var transformedType = methodResolver.transform(
                targetType,
                method,
                typeArguments,
                List.of(argumentType)
        ).first();

        final var actual = TypePrinter.print(transformedType);
        final var expected = "<R, A> java.util.Set<java.lang.String> collect(java.util.stream.Collector<? super java.lang.String, java.lang.Object, java.util.Set<java.lang.String>>)";
        assertEquals(expected, actual);
    }

    @Disabled
    @Test
    void testTransform2() {
        final var targetType = (DeclaredType) loadClass("jakarta.persistence.criteria.CriteriaBuilder").asType();
        final var pathType = loadClass("jakarta.persistence.criteria.Path<java.lang.String>").asType();
        final var stringType = loadClass("java.lang.String").asType();
        final var method = targetType.asTypeElement().getEnclosedElements().stream()
                .filter(it -> it.getSimpleName().equals("equal"))
                .map(it -> (MethodSymbol) it)
                .findFirst()
                .orElse(null);

        final var transformedType = methodResolver.transform(
                targetType,
                method,
                List.of(),
                List.of(pathType, stringType)
        ).first();

        final var result = TypePrinter.print(transformedType);
        System.out.println(result);
    }

    @Test
    void test1() {
        final var module = getCompilerContext().getModules().getUnnamedModule();

        final var companyType = getCompilerContext().getClassElementLoader().loadClass(module, "foo.Company").asType();
        final var employeeType = getCompilerContext().getClassElementLoader().loadClass(module, "foo.Employee").asType();
        final var joinClass = getCompilerContext().getClassElementLoader().loadClass(module, "jakarta.persistence.criteria.Join");
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
        final var expected = "<Y:java.lang.Object> jakarta.persistence.criteria.Path<java.lang.String> get(java.lang.String)";
        assertEquals(expected, actual);
    }

    @Test
    void test() {
        final var module = getCompilerContext().getModules().getUnnamedModule();
        final var javaBase = getCompilerContext().getModules().getJavaBase();
        final var loader = getCompilerContext().getClassElementLoader();
        final var repositoryTypeElement = loader.loadClass(module, "org.springframework.data.jpa.repository.JpaRepository");
        final var uuidType = loader.loadClass(javaBase, "java.util.UUID").asType();
        final var companyType = loader.loadClass(module, "foo.Company").asType();

        final var scope = new FunctionScope(null, null);

        final var repositoryType = types.getDeclaredType(
                repositoryTypeElement,
                companyType,
                uuidType
        );

        final var repositoryVariable = new VariableSymbolBuilderImpl()
                .simpleName("repository")
                .type(repositoryType)
                        .build();

        scope.define(repositoryVariable);

        final var methodInvocation = (MethodInvocationTree)  NabuTreeParser.parse(
                "repository.findAll()",
                NabuParser::functionInvocation,
                getCompilerContext(),
                scope
        );

        final var resolvedMethodOptional = methodResolver.resolveMethod(methodInvocation);
        final var resolvedMethod = resolvedMethodOptional.orElse(null);
        final var actual = TypePrinter.print(resolvedMethod);

        System.out.println(actual);
    }

    @Test
    void resolveMethodWithLambda() {
        final var loader = getCompilerContext().getClassElementLoader();
        final var javaBase = getCompilerContext().getModules().getJavaBase();

        final var stringType = loader.loadClass(javaBase, "java.lang.String").asType();
        final var optionalString = types.getDeclaredType(
                loader.loadClass(javaBase, "java.util.Optional"),
                stringType
        );

        final var variable = IdentifierTree.create("optionalString");
        variable.setType(optionalString);

        final var lambda = new CLambdaExpressionTree(
                List.of(),
                new CBlockStatementTree(List.of()),
                -1,
                -1
        );

        final var partialType = new UndetVarType(
                new CMethodType(
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(stringType),
                        List.of()
                )
        );
        lambda.setType(partialType);

        final var methodInvocationTree = new MethodInvocationTreeBuilder()
                .methodSelector(
                        new CFieldAccessExpressionTree(
                                variable,
                                IdentifierTree.create("ifPresent")
                        )
                ).arguments(List.of(lambda))
                .build();

        final var methodType = methodResolver.resolveMethod(methodInvocationTree).orElse(null);
        assertNotNull(methodType);

        final var actual = TypePrinter.print(methodType);

        assertEquals("""
                void ifPresent(java.util.function.Consumer<? super java.lang.String>)""", actual);
    }

}
