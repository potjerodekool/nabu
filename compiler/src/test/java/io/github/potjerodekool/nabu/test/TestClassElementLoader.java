package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.Java20Lexer;
import io.github.potjerodekool.nabu.Java20Parser;
import io.github.potjerodekool.nabu.Java20ParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuFileObject;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.*;
import io.github.potjerodekool.nabu.compiler.frontend.parser.java.JavaCompilerParser;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.Types;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

public class TestClassElementLoader implements ClassSymbolLoader {

    private final SymbolTable symbolTable = Mockito.mock(
            SymbolTable.class
    );

    private final Map<String, ClassSymbol> classes = new HashMap<>();

    private final Map<String, PackageSymbol> packages = new HashMap<>();

    private final TypesImpl types = new TypesImpl(symbolTable);

    private final JavaLoader javaLoader = new JavaLoader(this);

    public TestClassElementLoader() {
        final var objectType = createObjectClass().asType();

        addClass(objectType);

        lenient()
                .when(symbolTable.getObjectType())
                .thenReturn(objectType);

        lenient()
                .when(symbolTable.getStringType())
                .thenReturn(loadClass(null, Constants.STRING).asType());

        lenient()
                .when(symbolTable.getClassType())
                .thenReturn(loadClass(null, Constants.CLAZZ).asType());

        final var enumType = createEnumClass(objectType).asType();

        addClass(enumType);

        lenient()
                .when(symbolTable.getEnumType())
                .thenReturn(enumType);

        final var recordType = createRecordClass().asType();

        addClass(recordType);

        lenient()
                .when(symbolTable.getRecordType())
                .thenReturn(recordType);

        lenient()
                .when(symbolTable.getClassSymbol(
                        any(),
                        any()
                )).thenAnswer(answer -> {
                    final String name = answer.getArgument(1);
                    return classes.get(name);
                });

        lenient()
                .when(symbolTable.lookupPackage(
                        any(),
                        any()
                )).thenAnswer(answer -> {
                    final String packageName = answer.getArgument(1);
                    return packages.get(packageName);
                });
    }

    private ClassSymbol load(final String resourceName) {
        return javaLoader.load(resourceName);
    }

    private void addClass(final TypeMirror typeMirror) {
        final var clazz = (ClassSymbol) typeMirror.asTypeElement();
        addClass(clazz);
    }

    public void addClass(final ClassSymbol classSymbol) {
        this.classes.put(classSymbol.getQualifiedName(), classSymbol);
    }

    @Override
    public ClassSymbol loadClass(final ModuleElement moduleElement, final String name) {
        var clazz = classes.get(name);

        if (clazz != null) {
            return clazz;
        }

        clazz = load("classes/" + name.replace('.', '/') + ".java");

        if (clazz != null) {
            return clazz;
        }

        clazz = createClass(name);
        addClass(clazz);

        return clazz;
    }

    private ClassSymbol createClass(final String name) {
        return createClass(name, it -> {
        });
    }

    private ClassSymbol createClass(final String name,
                                    final Consumer<ClassSymbolBuilder> adjuster) {
        final var elements = name.split("\\.");
        final var packageName = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.'))
                : null;
        final var simpleName = elements[elements.length - 1];

        final PackageSymbol packageElement = findOrCreatePackage(
                null,
                packageName
        );

        final TypeMirror superType;

        if (Constants.OBJECT.equals(name)) {
            superType = null;
        } else {
            superType = classes.get(Constants.OBJECT).asType();
        }

        final var clazzBuilder = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName(simpleName)
                .enclosingElement(packageElement)
                .superclass(superType);

        adjuster.accept(clazzBuilder);

        final var clazz = clazzBuilder
                .build();

        clazz.setMembers(new WritableScope());

        packageElement.getMembers().define(clazz);

        return clazz;
    }

    private ClassSymbol createObjectClass() {
        final var objectClass = loadClass(null, Constants.OBJECT);

        final var cloneMethod = createCloneMethod(objectClass.asType());

        objectClass.addEnclosedElement(cloneMethod);

        return objectClass;
    }

    private MethodSymbol createCloneMethod(final TypeMirror objectType) {
        return new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("clone")
                .returnType(objectType)
                .build();
    }

    private ClassSymbol createEnumClass(final TypeMirror objectType) {
        final var enumClass = createClass(Constants.ENUM);

        enumClass.addEnclosedElement(createConstructor(
                createParameter(createDeclaredType(Constants.STRING), "name"),
                createParameter(new CPrimitiveType(TypeKind.INT), "ordinal")
        ));

        enumClass.addEnclosedElement(createCloneMethod(objectType));

        final var typeVariable = new CTypeVariable("T",
                null,
                new CClassType(
                        null,
                        enumClass,
                        List.of(
                                new CTypeVariable(
                                        "T"
                                )
                        )
                ),
                null
        );

        final var valueOfMethod = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("valueOf")
                .parameter(createParameter(symbolTable.getClassType(), "type"))
                .parameter(createParameter(symbolTable.getStringType(), "name"))
                .typeParameter(typeVariable.asElement())
                .returnType(typeVariable)
                .build();

        enumClass.addEnclosedElement(valueOfMethod);

        final var ordinalMethod = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("ordinal")
                .returnType(types.getPrimitiveType(TypeKind.INT))
                .build();

        enumClass.addEnclosedElement(ordinalMethod);

        enumClass.setSuperClass(objectType);

        return enumClass;
    }

    private ClassSymbol createRecordClass() {
        final var recordClass = createDeclaredType(Constants.RECORD);
        final var recordClassElement = (ClassSymbol) recordClass.asTypeElement();
        recordClassElement.addEnclosedElement(createConstructor());
        return recordClassElement;
    }

    private DeclaredType createDeclaredType(final String className) {
        final var sepIndex = className.lastIndexOf(".");
        final var simpleName = className.substring(sepIndex + 1);
        final var packageName = className.substring(0, sepIndex);
        final var packageSymbol = createPackage(packageName);

        final var classSymbol = new ClassSymbolBuilder()
                .simpleName(simpleName)
                .enclosingElement(packageSymbol)
                .build();

        classSymbol.setMembers(new WritableScope());

        classSymbol.setType(
                new CClassType(
                        null,
                        classSymbol,
                        List.of()
                )
        );
        return (DeclaredType) classSymbol.asType();
    }

    private PackageSymbol createPackage(final String name) {
        if (name == null) {
            return PackageSymbol.UNNAMED_PACKAGE;
        }

        PackageSymbol packageSymbol = null;

        for (final var packageName : name.split("\\.")) {
            packageSymbol = new PackageSymbol(
                    packageSymbol,
                    packageName
            );
            packageSymbol.setMembers(new WritableScope());

            packages.put(
                    packageSymbol.getQualifiedName(),
                    packageSymbol
            );
        }

        return packageSymbol;
    }

    public MethodSymbol createConstructor(final VariableSymbol... parameters) {
        final var builder = new MethodSymbolBuilderImpl()
                .kind(ElementKind.CONSTRUCTOR)
                .simpleName(Constants.INIT);

        for (final var parameter : parameters) {
            builder.parameter(parameter);
        }

        return builder.build();
    }

    private VariableSymbol createParameter(final TypeMirror type,
                                           final String name) {
        return new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .type(type)
                .simpleName(name)
                .build();

    }

    @Override
    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    @Override
    public TypesImpl getTypes() {
        return types;
    }

    @Override
    public PackageSymbol findOrCreatePackage(final ModuleElement moduleElement, final String packageName) {
        var packageSymbol = this.packages.get(packageName);

        if (packageSymbol == null) {
            packageSymbol = createPackage(packageName);
        }

        return packageSymbol;
    }

    @Override
    public void importJavaLang(final ImportScope importScope) {
        //throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
    }

}

class JavaLoader {

    private final TestClassElementLoader loader;

    JavaLoader(final TestClassElementLoader loader) {
        this.loader = loader;
    }

    public ClassSymbol load(final String resourceName) {
        try (var input = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (input != null) {
                final var unit = JavaCompilerParser.parse(input);
                final var builder = new SimpleClassBuilder(loader, resourceName);
                final var result = (List<ClassSymbol>) unit.accept(builder);

                return result.getFirst();
            } else {
                return null;
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}

class SimpleClassBuilder extends Java20ParserBaseVisitor<Object> {

    private final TestClassElementLoader loader;
    private final String resourceName;
    private final Types types;

    public SimpleClassBuilder(final TestClassElementLoader loader,
                              final String resourceName) {
        this.loader = loader;
        this.resourceName = resourceName;
        this.types = loader.getTypes();
    }

    @Override
    public Object visitOrdinaryCompilationUnit(final Java20Parser.OrdinaryCompilationUnitContext ctx) {
        final var pck = (PackageSymbol) ctx.packageDeclaration().accept(this);
        final var classes = ctx.topLevelClassOrInterfaceDeclaration().stream()
                .map(it -> it.accept(this))
                .map(it -> (ClassSymbol) it)
                .toList();

        classes.forEach(classSymbol -> {
            classSymbol.setEnclosingElement(pck);
            pck.addEnclosedElement(classSymbol);
            classSymbol.setNestingKind(NestingKind.TOP_LEVEL);
            loader.addClass(classSymbol);
        });

        return classes;
    }

    @Override
    public Object visitNormalClassDeclaration(final Java20Parser.NormalClassDeclarationContext ctx) {
        final var name = (String) ctx.typeIdentifier().accept(this);

        final var typeParameters = parseTypeParameters(ctx.typeParameters());

        ClassSymbol classSymbol = new ClassSymbolBuilder()
                .simpleName(name)
                .kind(ElementKind.CLASS)
                .typeParameters(typeParameters)
                .build();

        final var classBody = (List<Symbol>) ctx.classBody().accept(this);

        classBody.forEach(classSymbol::addEnclosedElement);

        return classSymbol;
    }

    private List<TypeParameterElement> parseTypeParameters(final Java20Parser.TypeParametersContext typeParametersContext) {
        if (typeParametersContext == null) {
            return Collections.emptyList();
        }

        return (List<TypeParameterElement>) typeParametersContext.typeParameterList().accept(this);
    }

    @Override
    public Object visitTypeParameterList(final Java20Parser.TypeParameterListContext ctx) {
        return ctx.typeParameter().stream()
                .map(it -> it.accept(this))
                .map(it -> (TypeVariable) it)
                .map(TypeVariable::asElement)
                .toList();
    }

    @Override
    public Object visitTypeParameter(final Java20Parser.TypeParameterContext ctx) {
        final var identifier = (String) ctx.typeIdentifier().accept(this);
        return new CTypeVariable(identifier);
    }

    @Override
    public Object visitNormalInterfaceDeclaration(final Java20Parser.NormalInterfaceDeclarationContext ctx) {
        final var name = (String) ctx.typeIdentifier().accept(this);
        final var typeParameters = parseTypeParameters(ctx.typeParameters());

        ClassSymbol classSymbol = new ClassSymbolBuilder()
                .simpleName(name)
                .kind(ElementKind.INTERFACE)
                .typeParameters(typeParameters)
                .build();

        classSymbol.setSourceFile(createFileObject());
        loader.addClass(classSymbol);

        final var classBody = (List<Symbol>) ctx.interfaceBody().accept(this);

        classBody.forEach(classSymbol::addEnclosedElement);

        return classSymbol;
    }

    private FileObject createFileObject() {
        return new NabuFileObject(
                new FileObject.Kind(".java", true ),
                Paths.get(this.resourceName)
        );
    }

    @Override
    public Object visitClassBody(final Java20Parser.ClassBodyContext ctx) {
        return ctx.classBodyDeclaration().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitInterfaceBody(final Java20Parser.InterfaceBodyContext ctx) {
        return ctx.interfaceMemberDeclaration().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitMethodDeclaration(final Java20Parser.MethodDeclarationContext ctx) {
        final var header = (MethodHeader) ctx.methodHeader().accept(this);

        return new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName(header.methodDeclarator().name())
                .returnType(header.result())
                .parameters(header.methodDeclarator().parameters())
                .typeParameters(header.typeParameters())
                .build();
    }

    @Override
    public Object visitInterfaceMethodDeclaration(final Java20Parser.InterfaceMethodDeclarationContext ctx) {
        final var header = (MethodHeader) ctx.methodHeader().accept(this);
        final var typeParameters = header.typeParameters();

        return new MethodSymbolBuilderImpl()
                .simpleName(header.methodDeclarator().name())
                .returnType(header.result())
                .parameters(header.methodDeclarator().parameters())
                .typeParameters(typeParameters)
                .kind(ElementKind.METHOD)
                .build();
    }

    @Override
    public Object visitMethodHeader(final Java20Parser.MethodHeaderContext ctx) {
        final List<TypeParameterElement> typeParameters = (List<TypeParameterElement>) acceptList(ctx.typeParameters());
        final var result = (TypeMirror) ctx.result().accept(this);
        final var declarator = (MethodDeclarator) ctx.methodDeclarator().accept(this);
        return new MethodHeader(result, declarator, typeParameters);
    }

    @Override
    public Object visitMethodDeclarator(final Java20Parser.MethodDeclaratorContext ctx) {
        final var methodName = (String) ctx.identifier().accept(this);
        final var parameters = (List<VariableSymbol>) acceptList(ctx.formalParameterList());
        return new MethodDeclarator(methodName, parameters);
    }

    private <T> List<? extends T> acceptList(final ParserRuleContext context) {
        if (context == null) {
            return Collections.emptyList();
        }

        return (List<T>) context.accept(this);
    }

    @Override
    public Object visitFormalParameterList(final Java20Parser.FormalParameterListContext ctx) {
        return ctx.formalParameter().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitFormalParameter(final Java20Parser.FormalParameterContext ctx) {
        final var type = (TypeMirror) ctx.unannType().accept(this);
        final var variableDeclaratorId = (String) ctx.variableDeclaratorId().accept(this);

        return new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName(variableDeclaratorId)
                .type(type)
                .build();
    }

    @Override
    public Object visitTypeParameters(final Java20Parser.TypeParametersContext ctx) {
        return ctx.typeParameterList().accept(this);
    }

    @Override
    public Object visitResult(final Java20Parser.ResultContext ctx) {
        if (ctx.unannType() != null) {
            return ctx.unannType().accept(this);
        } else {
            return types.getNoType(TypeKind.VOID);
        }
    }

    @Override
    public Object visitUnannPrimitiveType(final Java20Parser.UnannPrimitiveTypeContext ctx) {
        if (ctx.numericType() != null) {
            return ctx.numericType().accept(this);
        } else {
            return types.getPrimitiveType(TypeKind.BOOLEAN);
        }
    }

    @Override
    public Object visitPackageDeclaration(final Java20Parser.PackageDeclarationContext ctx) {
        final var packageName = ctx.identifier().stream()
                .map(it -> it.accept(this).toString())
                .collect(Collectors.joining("."));

        return this.loader.findOrCreatePackage(
                null,
                packageName
        );
    }

    @Override
    public Object visitIdentifier(final Java20Parser.IdentifierContext ctx) {
        return super.visitIdentifier(ctx);
    }

    @Override
    public Object visitUnannClassOrInterfaceType(final Java20Parser.UnannClassOrInterfaceTypeContext ctx) {
        final var nameJoiner = new StringJoiner(".");

        if (ctx.packageName() != null) {
            final var name = (String) ctx.packageName().accept(this);
            nameJoiner.add(name);
        }

        final var typeIdentifier = (String) ctx.typeIdentifier().accept(this);
        nameJoiner.add(typeIdentifier);

        final var className = nameJoiner.toString();
        final var clazz = loader.loadClass(null, className);
        return clazz.asType();
    }

    @Override
    public Object visitPackageName(final Java20Parser.PackageNameContext ctx) {
        final var nameJoiner = new StringJoiner(".");
        nameJoiner.add(ctx.identifier().accept(this).toString());

        if (ctx.packageName() != null) {
            nameJoiner.add(ctx.packageName().accept(this).toString());
        }

        return nameJoiner.toString();
    }

    @Override
    public Object visitTerminal(final TerminalNode node) {
        final var type = node.getSymbol().getType();

        return switch (type) {
            case Java20Lexer.Identifier -> node.getText();
            case Java20Lexer.BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case Java20Lexer.SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case Java20Lexer.INT -> types.getPrimitiveType(TypeKind.INT);
            case Java20Lexer.LONG -> types.getPrimitiveType(TypeKind.LONG);
            case Java20Lexer.CHAR -> types.getPrimitiveType(TypeKind.CHAR);
            case Java20Lexer.FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case Java20Lexer.DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            default -> throw new TodoException();
        };
    }
}

record MethodHeader(TypeMirror result,
                    MethodDeclarator methodDeclarator,
                    List<TypeParameterElement> typeParameters) {

}

record MethodDeclarator(String name, List<VariableSymbol> parameters) {

}