package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl.Modules;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.testing.InMemoryFileObject;
import io.github.potjerodekool.nabu.tools.*;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.TypeApplyTree;
import io.github.potjerodekool.nabu.tree.expression.WildcardExpressionTree;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ServiceLoader;

public abstract class AbstractCompilerTest {

    private final CompilerContextImpl compilerContext = createCompilerContext();

    private Compiler createCompiler() {
        return ServiceLoader.load(Compiler.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("No compiler implementation found"));
    }

    private CompilerContextImpl createCompilerContext() {
        final var rootDirectory = resolveCompilerDirectory();
        final var rootPath = rootDirectory.getAbsolutePath()
                .replace('\\', '/');

        final var optionsBuilder = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                .option(CompilerOption.MODULE_SOURCE_PATH, rootPath + "/src/test/resources/jmods");

        final var classPath = getClassPath();

        if (classPath != null && !classPath.isEmpty()) {
            optionsBuilder.option(CompilerOption.CLASS_PATH, classPath);
        }
        configureOptions(optionsBuilder);

        final var options = optionsBuilder.build();
        final var compiler = createCompiler();
        final var compilerContext = (CompilerContextImpl) compiler.configure(options);

        Modules.getInstance(compilerContext).initAllModules();
        return compilerContext;
    }

    protected void configureOptions(final CompilerOptions.CompilerOptionsBuilder optionsBuilder) {
        final var classPath = getClassPath();

        if (classPath != null && !classPath.isEmpty()) {
            optionsBuilder.option(CompilerOption.CLASS_PATH, classPath);
        }
    }

    protected CompilerContextImpl getCompilerContext() {
        return compilerContext;
    }

    protected String loadResource(final String resourceName) {
        try (var resource = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (resource == null) {
                throw new NullPointerException("Failed to load resource " + resourceName);
            }
            return new String(resource.readAllBytes());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected TypeElement loadClass(final String className) {
        final var javaBase = getCompilerContext().getModules().getJavaBase();
        return getCompilerContext().getClassElementLoader().loadClass(javaBase, className);
    }

    protected TypeMirror loadType(final String typeName) {
        return TypeLoader.loadType(typeName, getCompilerContext());
    }

    protected VariableSymbol localVariableSymbol(final String name,
                                                 final String type) {
        return new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName(name)
                .type(loadType(type))
                .build();
    }

    protected VariableSymbol variableSymbol(final ElementKind kind,
                                            final String name,
                                            final String type) {
        return new VariableSymbolBuilderImpl()
                .kind(kind)
                .simpleName(name)
                .type(loadType(type))
                .build();
    }

    protected FileObject createFileObject(final String source) {
        return new InMemoryFileObject(source, "MyClass.nabu");
    }

    private File resolveCompilerDirectory() {
        var current = new File(".").getAbsoluteFile();

        while (!current.isDirectory() || !"nabu".equals(current.getName())) {
            current = current.getParentFile();
        }

        return new File(current, "compiler");
    }

    protected String getClassPath() {
        return null;
    }

    protected String createClassPath(String... pathElements) {
        return String.join(File.pathSeparator, pathElements);
    }

    protected String getLocationOfClass(final Class<?> clazz) {
        try {
            return Paths.get(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (final URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

}

class TypeLoader {

    static TypeMirror loadType(final String typeName,
                               final CompilerContext context) {
        final Tree typeTree;
        final var scope = new GlobalScope(
                null,
                context
        );

        if (typeName.startsWith("?")) {
            typeTree = NabuTreeParser.parse(
                    typeName,
                    NabuParser::wildcard,
                    context,
                    scope
            );
        } else {
            typeTree = NabuTreeParser.parse(
                    typeName,
                    NabuParser::unannType,
                    context,
                    scope
            );
        }

        final var toTypeLoader = new ToTypeLoader(context);
        return toTypeLoader.acceptTree(typeTree, null);
    }
}

class ToTypeLoader extends AbstractTreeVisitor<TypeMirror, Object> implements TreeVisitor<TypeMirror, Object> {

    private final CompilerContext context;

    public ToTypeLoader(final CompilerContext context) {
        this.context = context;
    }

    @Override
    public TypeMirror visitUnknown(final Tree tree,
                                   final Object param) {
        throw new TodoException();
    }

    @Override
    public TypeMirror visitIdentifier(final IdentifierTree identifier,
                                      final Object param) {
        final var name = identifier.getName();

        if (name.length() == 1) {
            return new CTypeVariable(name);
        }

        return super.visitIdentifier(identifier, param);
    }

    @Override
    public TypeMirror visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                          final Object param) {
        final var clazz = (CClassType) acceptTree(typeIdentifier.getClazz(), param);
        final var typeParameters = typeIdentifier.getTypeParameters();
        final var typeParams = typeParameters.stream()
                .map(it -> acceptTree(it, param))
                .toArray(TypeMirror[]::new);

        return context.getTypes()
                .getDeclaredType(
                        clazz.asTypeElement(),
                        typeParams
                );
    }

    @Override
    public TypeMirror visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                                 final Object param) {
        final var className = resolveClassName(fieldAccessExpression);
        final var javaBase = context.getModules().getJavaBase();
        return context.getClassElementLoader().loadClass(
                javaBase,
                className
        ).asType();
    }

    private String resolveClassName(final Tree tree) {
        if (tree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            final var packageName = resolveClassName(fieldAccessExpressionTree.getSelected());
            final var simpleName = resolveClassName(fieldAccessExpressionTree.getField());
            return packageName + "." + simpleName;
        } else if (tree instanceof IdentifierTree identifierTree) {
            return identifierTree.getName();
        }

        throw new TodoException("" + tree);
    }

    @Override
    public TypeMirror visitWildCardExpression(final WildcardExpressionTree wildcardExpression,
                                              final Object param) {
        return switch (wildcardExpression.getBoundKind()) {
            case EXTENDS -> {
                final var extendsBound = acceptTree(wildcardExpression.getBound(), param);
                yield context.getTypes()
                        .getWildcardType(extendsBound, null);
            }
            case SUPER -> {
                final var superBound = acceptTree(wildcardExpression.getBound(), param);
                yield context.getTypes()
                        .getWildcardType(null, superBound);
            }
            case UNBOUND -> context.getTypes().getWildcardType(
                    null,
                    null
            );
        };
    }
}