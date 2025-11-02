package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.Java20Lexer;
import io.github.potjerodekool.nabu.Java20Parser;
import io.github.potjerodekool.nabu.Java20ParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuFileObject;
import io.github.potjerodekool.nabu.compiler.type.impl.*;
import io.github.potjerodekool.nabu.lang.model.element.*;
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
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.CDimension;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Types;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class TestClassElementLoader implements ClassSymbolLoader {

    private final SymbolTable symbolTable;

    private final Map<String, ClassSymbol> classes = new HashMap<>();

    private final Map<String, PackageSymbol> packages = new HashMap<>();

    private final TypesImpl types;

    private final JavaLoader javaLoader = new JavaLoader(this);

    private final Map<String, String> packageToModule = new HashMap<>();

    public TestClassElementLoader(final CompilerContextImpl compilerContext) {
        this.symbolTable = SymbolTable.getInstance(compilerContext);
        types = new TypesImpl(symbolTable);
        resolveModules();
    }

    // src/test/resources/jmods

    private void resolveModules() {
        final var root = Paths.get("src/test/resources/jmods");
        final var moduleScanner = new ModuleScanner(root);

        try {
            Files.walkFileTree(root, moduleScanner);
            this.packageToModule.putAll(moduleScanner.getPackages());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ClassSymbol load(final String resourceName) {
        final var packageEnd = resourceName.lastIndexOf('/');

        if (packageEnd > -1) {
            final var packageName = resourceName.substring(0, packageEnd).replace('/', '.');
            final var moduleName = this.packageToModule.get(packageName);

            if (moduleName != null) {
                final var moduleResourcePath = "jmods/" + moduleName + "/" + resourceName;
                final var clazz = javaLoader.load(moduleResourcePath);

                if (clazz != null) {
                    return clazz;
                }
            }
        }

        return javaLoader.load("classes/" + resourceName);
    }

    public void addClass(final ClassSymbol classSymbol) {
        this.classes.put(classSymbol.getQualifiedName(), classSymbol);
    }

    @Override
    public ClassSymbol loadClass(final ModuleElement moduleElement,
                                 final String name) {
        final String className;
        final var dollarIndex = name.indexOf('$');

        if (dollarIndex < 0) {
            className = name;
        } else {
            className = name.substring(0, dollarIndex);
        }

        var clazz = classes.get(className);

        if (clazz != null) {
            if (dollarIndex > -1) {
                return findMemberClass(name, clazz);
            }
            return clazz;
        }

        clazz = load(className.replace('.', '/') + ".java");

        if (clazz != null) {
            if (dollarIndex > -1) {
                return findMemberClass(name, clazz);
            }
            return clazz;
        }

        return clazz;
    }

    private ClassSymbol findMemberClass(final String name,
                                        final ClassSymbol classSymbol) {
        final var names = name.split("\\$");
        ClassSymbol clazz = classSymbol;

        for (var i = 1; clazz != null && i < names.length; i++) {
            final var memberName = names[i];
            clazz = clazz.getEnclosedElements().stream()
                    .filter(it -> it instanceof ClassSymbol)
                    .filter(it -> memberName.equals(it.getSimpleName()))
                    .map(it -> (ClassSymbol) it)
                    .findFirst()
                    .orElse(null);
        }

        return clazz;
    }


    public void addFakeClass(final String name) {
        final var clazz = createFakeClass(name, it -> {
        });
        addClass(clazz);
    }

    private ClassSymbol createFakeClass(final String name,
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
            superType = loadClass(null, Constants.OBJECT).asType();
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

    @Override
    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    @Override
    public TypesImpl getTypes() {
        return types;
    }

    @Override
    public PackageSymbol findOrCreatePackage(final ModuleElement moduleElement,
                                             final String packageName) {
        var packageSymbol = this.packages.get(packageName);

        if (packageSymbol == null) {
            packageSymbol = createPackage(packageName);
        }

        packageSymbol.setModuleSymbol((ModuleSymbol) moduleElement);

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
        final var resource = getClass().getClassLoader().getResource(resourceName);

        if (resource == null) {
            return null;
        }

        try (var input = resource.openStream()) {
            if (input != null) {
                final var unit = JavaCompilerParser.parse(input);
                final var builder = new SimpleClassBuilder(loader, resourceName);
                final var result = (List<ClassSymbol>) unit.accept(builder);
                final var clazz = result.getFirst();
                clazz.setSourceFile(new NabuFileObject(
                        new FileObject.Kind("", true),
                        Paths.get(resource.toURI())
                ));
                return clazz;
            } else {
                return null;
            }
        } catch (final IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}

class SimpleClassBuilder extends Java20ParserBaseVisitor<Object> {

    private final TestClassElementLoader loader;
    private final String resourceName;
    private final Types types;
    private Symbol enclosingSymbol;

    private TypeParams typeParams = TypeParams.ROOT;

    public SimpleClassBuilder(final TestClassElementLoader loader,
                              final String resourceName) {
        this.loader = loader;
        this.resourceName = resourceName;
        this.types = loader.getTypes();
    }

    private ClassSymbol loadClass(final String name) {
        var clazz = loader.loadClass(null, name);

        if (clazz != null) {
            return clazz;
        }

        if (!name.contains(".")) {
            //Load classes from the same package.
            final var packageSymbol = findPackageSymbol(enclosingSymbol);
            final var fullClassName = packageSymbol.getQualifiedName() + "." + name;
            clazz = loader.loadClass(null, fullClassName);

            if (clazz != null) {
                return clazz;
            }

            final var javaLangName = "java.lang." + name;
            clazz = loader.loadClass(null, javaLangName);
        }

        return clazz;
    }

    private PackageSymbol findPackageSymbol(final Symbol symbol) {
        if (symbol instanceof PackageSymbol packageSymbol) {
            return packageSymbol;
        } else {
            return findPackageSymbol(symbol.getEnclosingElement());
        }
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
        final var name = ctx.typeIdentifier().getText();

        final var classSymbol = new ClassSymbol();

        final var nestingKind = enclosingSymbol instanceof ClassSymbol ? NestingKind.MEMBER : NestingKind.TOP_LEVEL;

        classSymbol.setNestingKind(nestingKind);
        classSymbol.setSimpleName(name);
        classSymbol.setKind(ElementKind.CLASS);
        classSymbol.setEnclosingElement(enclosingSymbol);
        this.enclosingSymbol = classSymbol;
        loader.addClass(classSymbol);

        this.typeParams = new TypeParams(this.typeParams);
        final var typeParameters = parseTypeParameters(ctx.typeParameters());

        classSymbol.addTypeParameters(typeParameters);

        var exendion = (TypeMirror) accept(ctx.classExtends());

        if (exendion == null && !Constants.OBJECT.equals(classSymbol.getQualifiedName())) {
            exendion = loadClass(Constants.OBJECT).asType();
        }

        final var interfaces = this.<TypeMirror>acceptList(ctx.classImplements());

        classSymbol.setInterfaces(interfaces);

        final var classBody = (List<Symbol>) ctx.classBody().accept(this);
        classBody.forEach(classSymbol::addEnclosedElement);

        popTypeParams();

        classSymbol.setSuperClass(exendion);

        enclosingSymbol = classSymbol.getEnclosingElement();

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
        final var identifier = ctx.typeIdentifier().getText();
        typeParams.addTypeParam(identifier);
        return new CTypeVariable(identifier);
    }

    @Override
    public Object visitNormalInterfaceDeclaration(final Java20Parser.NormalInterfaceDeclarationContext ctx) {
        final var name = ctx.typeIdentifier().getText();

        final var classSymbol = new ClassSymbol();
        classSymbol.setSimpleName(name);
        classSymbol.setKind(ElementKind.INTERFACE);
        classSymbol.setEnclosingElement(enclosingSymbol);
        loader.addClass(classSymbol);

        this.typeParams = new TypeParams(this.typeParams);

        final var typeParameters = parseTypeParameters(ctx.typeParameters());

        final var interfaces = this.<TypeMirror>acceptList(ctx.interfaceExtends());
        classSymbol.addTypeParameters(typeParameters);

        classSymbol.setInterfaces(interfaces);

        classSymbol.setSourceFile(createFileObject());

        final var classBody = (List<Symbol>) ctx.interfaceBody().accept(this);

        classBody.forEach(classSymbol::addEnclosedElement);

        popTypeParams();

        return classSymbol;
    }

    void popTypeParams() {
        if (this.typeParams.parent() != null) {
            this.typeParams = typeParams.parent();
        }
    }

    @Override
    public Object visitInterfaceExtends(final Java20Parser.InterfaceExtendsContext ctx) {
        return ctx.interfaceTypeList().accept(this);
    }

    @Override
    public Object visitInterfaceTypeList(final Java20Parser.InterfaceTypeListContext ctx) {
        return ctx.interfaceType().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitClassType(final Java20Parser.ClassTypeContext ctx) {
        final ExpressionTree packageName = accept(ctx.packageName());
        final var typeIdentifier = IdentifierTree.create(ctx.typeIdentifier().getText());
        final ExpressionTree fullIdentifier;

        if (packageName != null) {
            fullIdentifier = new CFieldAccessExpressionTree(
                    packageName,
                    typeIdentifier
            );
        } else {
            fullIdentifier = typeIdentifier;
        }

        final var className = asString(fullIdentifier);

        final var typeArguments = acceptList(ctx.typeArguments());

        typeArguments.forEach(item -> {
            if (!(item instanceof TypeMirror)) {
                throw new IllegalArgumentException();
            }
        });

        final var typeArgs = typeArguments.toArray(TypeMirror[]::new);

        final var clazz = loadClass(className);
        return loader.getTypes().getDeclaredType(clazz, typeArgs);
    }

    @Override
    public Object visitTypeIdentifier(final Java20Parser.TypeIdentifierContext ctx) {
        final var name = ctx.Identifier().getText();

        if (typeParams.isTypeParam(name)) {
            return new CTypeVariable(name);
        } else {
            final var clazz = loadClass(name);
            return clazz != null ? clazz.asType() : null;
        }
    }

    private String asString(final ExpressionTree expressionTree) {
        if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            final var selected = asString(fieldAccessExpressionTree.getSelected());
            final var field = asString(fieldAccessExpressionTree.getField());
            return selected + "." + field;
        } else {
            final var field = (IdentifierTree) expressionTree;
            return field.getName();
        }
    }

    @Override
    public Object visitTypeArguments(final Java20Parser.TypeArgumentsContext ctx) {
        return ctx.typeArgumentList().accept(this);
    }

    @Override
    public Object visitTypeArgumentList(final Java20Parser.TypeArgumentListContext ctx) {
        return ctx.typeArgument().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    private FileObject createFileObject() {
        return new NabuFileObject(
                new FileObject.Kind(".java", true),
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
        final var typeParameters = this.<TypeParameterElement>acceptList(ctx.typeParameters());
        final var result = (TypeMirror) ctx.result().accept(this);
        final var declarator = (MethodDeclarator) ctx.methodDeclarator().accept(this);
        return new MethodHeader(result, declarator, typeParameters);
    }

    @Override
    public Object visitMethodDeclarator(final Java20Parser.MethodDeclaratorContext ctx) {
        final var methodName = (IdentifierTree) ctx.identifier().accept(this);
        final var parameters = this.<VariableSymbol>acceptList(ctx.formalParameterList());
        return new MethodDeclarator(methodName.getName(), parameters);
    }

    private <T> List<T> acceptList(final ParserRuleContext context) {
        if (context == null) {
            return Collections.emptyList();
        }

        return (List<T>) context.accept(this);
    }

    private <T> T accept(final ParserRuleContext context) {
        if (context == null) {
            return null;
        } else {
            return (T) context.accept(this);
        }
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
        final var variableDeclaratorId = (IdentifierTree) ctx.variableDeclaratorId().accept(this);

        return new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName(variableDeclaratorId.getName())
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

        enclosingSymbol = this.loader.findOrCreatePackage(null, packageName);
        return enclosingSymbol;
    }

    @Override
    public Object visitIdentifier(final Java20Parser.IdentifierContext ctx) {
        if (ctx.Identifier() != null) {
            final var text = ctx.Identifier().getText();
            return IdentifierTree.create(text);
        } else {
            return ctx.contextualKeyword().accept(this);
        }
    }

    @Override
    public Object visitUnannClassOrInterfaceType(final Java20Parser.UnannClassOrInterfaceTypeContext ctx) {
        final var identifiers = new ArrayList<IdentifierTree>();

        if (ctx.packageName() != null) {
            final var packageName = (ExpressionTree) ctx.packageName().accept(this);
            collectIdentifiers(packageName, identifiers);
        }

        final var typeIdentifier = IdentifierTree.create(ctx.typeIdentifier().getText());
        identifiers.add(typeIdentifier);

        final var className = asString(toExpressionTree(identifiers));

        if (typeParams.isTypeParam(className)) {
            return new CTypeVariable(
                    className
            );
        }

        final var clazz = loadClass(className);

        final var typeArgs = this.<TypeMirror>acceptList(ctx.typeArguments());

        final var typeArguments = typeArgs.toArray(TypeMirror[]::new);

        /*
        final var typeArguments = typeArgs.stream()
                .map(typeArg -> new CTypeVariable(typeArg.getName()))
                .toArray(TypeMirror[]::new);
        */

        return types.getDeclaredType(
                clazz,
                typeArguments
        );
    }

    @Override
    public Object visitPackageName(final Java20Parser.PackageNameContext ctx) {
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);
        final ExpressionTree packageName = accept(ctx.packageName());

        if (packageName == null) {
            return identifier;
        } else if (packageName instanceof IdentifierTree packageIdentifier) {
            return new CFieldAccessExpressionTree(
                    identifier,
                    packageIdentifier
            );
        } else {
            final var identifiers = new ArrayList<IdentifierTree>();
            identifiers.add(identifier);

            final var fieldAccess = (FieldAccessExpressionTree) packageName;
            collectIdentifiers(fieldAccess, identifiers);

            return toExpressionTree(identifiers);
        }
    }

    private ExpressionTree toExpressionTree(final List<IdentifierTree> identifiers) {
        if (identifiers.size() == 1) {
            return identifiers.getFirst();
        } else {
            final var iterator = identifiers.iterator();

            final var first = iterator.next();

            CFieldAccessExpressionTree result = new CFieldAccessExpressionTree(
                    first,
                    null
            );

            while (iterator.hasNext()) {
                final var identifier = iterator.next();
                if (result.getField() == null) {
                    result.field(identifier);
                } else {
                    result = new CFieldAccessExpressionTree(
                            result,
                            identifier
                    );
                }
            }

            return result;
        }
    }

    private void collectIdentifiers(final ExpressionTree expressionTree,
                                    final ArrayList<IdentifierTree> identifiers) {
        if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            collectIdentifiers(fieldAccessExpressionTree.getSelected(), identifiers);
            identifiers.add(fieldAccessExpressionTree.getField());
        } else {
            final var identifier = (IdentifierTree) expressionTree;
            identifiers.add(identifier);
        }
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

    @Override
    public Object visitContextualKeyword(final Java20Parser.ContextualKeywordContext ctx) {
        return IdentifierTree.create(ctx.getText());
    }

    @Override
    public Object visitClassOrInterfaceType(final Java20Parser.ClassOrInterfaceTypeContext ctx) {
        final var packageName = (ExpressionTree) accept(ctx.packageName());
        final Object identifier = accept(ctx.typeIdentifier());

        if (packageName == null) {
            return identifier;
        } else {
            final var identifiers = new ArrayList<IdentifierTree>();
            collectIdentifiers(packageName, identifiers);
            identifiers.add((IdentifierTree) identifier);
            final var className = asString(toExpressionTree(identifiers));
            return loadClass(className).asType();
        }
    }

    @Override
    public Object visitTypeVariable(final Java20Parser.TypeVariableContext ctx) {
        final var name = ctx.typeIdentifier().getText();
        typeParams.addTypeParam(name);
        return new CTypeVariable(name);
    }

    @Override
    public Object visitWildcard(final Java20Parser.WildcardContext ctx) {
        if (ctx.wildcardBounds() == null) {
            return new CWildcardType(null, BoundKind.UNBOUND, null);
        } else {
            final var bounds = ctx.wildcardBounds();
            final var boundKind = "extends".equals(bounds.kind.getText())
                    ? BoundKind.EXTENDS
                    : BoundKind.SUPER;
            final var type = (TypeMirror) bounds.referenceType().accept(this);

            return new CWildcardType(type, boundKind, null);
        }
    }

    @Override
    public Object visitUnannArrayType(final Java20Parser.UnannArrayTypeContext ctx) {
        TypeMirror type;

        if (ctx.unannPrimitiveType() != null) {
            type = (TypeMirror) ctx.unannPrimitiveType().accept(this);
        } else if (ctx.unannClassOrInterfaceType() != null) {
            type = (TypeMirror) ctx.unannClassOrInterfaceType().accept(this);
        } else {
            type = (TypeMirror) ctx.unannTypeVariable().accept(this);
        }

        final var dims = (List<Dimension>) ctx.dims().accept(this);

        for (var index = 0; index < dims.size(); index++) {
            type = types.getArrayType(type);
        }

        return type;
    }

    @Override
    public Object visitDims(final Java20Parser.DimsContext ctx) {
        final var dimensions = new ArrayList<Dimension>();

        var annotations = new ArrayList<AnnotationTree>();

        for (final var child : ctx.children) {
            if (child instanceof TerminalNode terminalNode) {
                if ("]".equals(terminalNode.getText())) {
                    dimensions.add(new CDimension(annotations, -1, -1));
                    annotations = new ArrayList<>();
                }
            } else {
                final var annotation = (AnnotationTree) child.accept(this);
                annotations.add(annotation);
            }
        }

        return dimensions;
    }

    @Override
    public Object visitConstructorDeclaration(final Java20Parser.ConstructorDeclarationContext ctx) {
        final var constructorDeclarator = (MethodDeclarator) ctx.constructorDeclarator().accept(this);

        return new MethodSymbolBuilderImpl()
                .kind(ElementKind.CONSTRUCTOR)
                .simpleName(Constants.INIT)
                .parameters(constructorDeclarator.parameters())
                .returnType(new CNoType())
                .build();
    }

    @Override
    public Object visitConstructorDeclarator(final Java20Parser.ConstructorDeclaratorContext ctx) {
        final var parameters = this.<VariableSymbol>acceptList(ctx.formalParameterList());

        return new MethodDeclarator(
                Constants.INIT,
                parameters
        );
    }

    @Override
    public Object visitClassExtends(final Java20Parser.ClassExtendsContext ctx) {
        return ctx.classType().accept(this);
    }

    @Override
    public Object visitClassImplements(final Java20Parser.ClassImplementsContext ctx) {
        return ctx.interfaceTypeList().accept(this);
    }

}

record MethodHeader(TypeMirror result,
                    MethodDeclarator methodDeclarator,
                    List<TypeParameterElement> typeParameters) {

}

record MethodDeclarator(String name, List<VariableSymbol> parameters) {
}

class TypeParams {

    public static final TypeParams ROOT = new TypeParams(null);

    private final Set<String> typeParams = new HashSet<>();
    private final TypeParams parent;

    TypeParams(final TypeParams parent) {
        this.parent = parent;
    }

    public void addTypeParam(final String name) {
        this.typeParams.add(name);
    }

    public TypeParams parent() {
        return parent;
    }

    public boolean isTypeParam(final String name) {
        if (typeParams.contains(name)) {
            return true;
        } else {
            return parent != null && parent.isTypeParam(name);
        }
    }
}

class ModuleScanner implements FileVisitor<Path> {

    private final Path root;
    private final Map<String, Set<String>> modules = new HashMap<>();
    private Path moduleRoot = null;
    private Set<String> packages = null;

    public ModuleScanner(final Path root) {
        this.root = root;
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir,
                                             final BasicFileAttributes attrs) throws IOException {
        if (dir.equals(root)) {
            return FileVisitResult.CONTINUE;
        }

        if (dir.getParent().equals(root)) {
            final var moduleName = root.relativize(dir).toString();
            moduleRoot = dir;
            packages = new HashSet<>();
            modules.put(moduleName, packages);
            return FileVisitResult.CONTINUE;
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
        final var relativePath = moduleRoot.relativize(file);
        final var parent = relativePath.getParent();

        if (parent != null) {
            final var packageName = parent.toString().replace(File.separatorChar, '.');
            packages.add(packageName);
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
        return FileVisitResult.CONTINUE;
    }

    public Map<String, Set<String>> getModules() {
        return modules;
    }

    public Map<String, String> getPackages() {
        final var packages = new HashMap<String, String>();

        this.modules.forEach((module, packageSet) -> {
            packageSet.forEach(packageName -> {
                packages.put(packageName, module);
            });
        });

        return packages;
    }
}