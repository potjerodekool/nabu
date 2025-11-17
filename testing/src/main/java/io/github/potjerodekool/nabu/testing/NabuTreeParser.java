package io.github.potjerodekool.nabu.testing;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuFileObject;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ResolverPhase;
import io.github.potjerodekool.nabu.compiler.resolve.internal.TypeEnter;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.scope.SymbolScope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.TreeUtils;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.type.DeclaredType;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.nio.file.Paths;

public class NabuTreeParser {

    private final CompilerContext compilerContext;
    private ParseTree parseTree;
    private Tree tree;
    private Scope scope;

    private NabuTreeParser(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    public Tree getTree() {
        return tree;
    }

    private CodePointCharStream createInputStream(final String code) {
        return CharStreams.fromString(code);
    }

    private NabuParser createParser(final CodePointCharStream inputSteam) {
        return new NabuParser(new CommonTokenStream(new NabuLexer(inputSteam)));
    }

    public static <T extends Tree> T parse(final String code,
                                           final java.util.function.Function<NabuParser, ParseTree> parseTreeCreator,
                                           final CompilerContext context,
                                           final java.util.function.Function<Scope, Scope> scopeCreator) {
        final var treeParser = new NabuTreeParser(context);
        final var parser = treeParser.createParser(treeParser.createInputStream(code));
        treeParser.parseTree = parseTreeCreator.apply(parser);
        treeParser.toAst(scopeCreator);

        final var contextImpl = (CompilerContextImpl) context;

        final var typeEnter = new TypeEnter(contextImpl);

        if (treeParser.tree instanceof CompilationUnit compilationUnit) {
            compilationUnit.getClasses().forEach(classDeclaration -> {
                final var symbol = (ClassSymbol) classDeclaration.getClassSymbol();
                typeEnter.put(symbol, classDeclaration, compilationUnit);
                symbol.setCompleter(typeEnter);
                symbol.complete();
            });
        }

        final var phase2Resolver = new ResolverPhase(contextImpl);
        treeParser.tree.accept(phase2Resolver, treeParser.scope);

        return (T) treeParser.tree;
    }

    private void toAst(final java.util.function.Function<Scope, Scope> scopeCreator) {
        final var file = new NabuFileObject(
                new FileObject.Kind(
                        ".nabu",
                        true
                ),
                Paths.get("MyClass.nabu")
        );

        final var visitor = new NabuCompilerVisitor(file);
        final var result = (Tree) parseTree.accept(visitor);

        final Scope scope;

        if (result instanceof CompilationUnit compilationUnit) {
            final var globalScope = new GlobalScope(compilationUnit, compilerContext);
            scope = scopeCreator.apply(globalScope);
        } else {
            scope = scopeCreator.apply(null);
        }

        final var postProcessor = new PostProcessor(compilerContext);
        result.accept(postProcessor, scope);
        this.tree = result;
        this.scope = scope;
    }
}

class PostProcessor extends AbstractTreeVisitor<Tree, Scope> {

    private final CompilerContext compilerContext;

    PostProcessor(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    @Override
    public Tree visitClass(final ClassDeclaration classDeclaration,
                           final Scope scope) {
        final var clazz = (TypeElement) scope.resolve(classDeclaration.getSimpleName());
        final var classDecl = (CClassDeclaration) classDeclaration;
        classDecl.setClassSymbol(clazz);

        final var classScope = new SymbolScope(
                (DeclaredType) clazz.asType(),
                scope
        );

        return super.visitClass(classDeclaration, classScope);
    }

    @Override
    public Tree visitFunction(final Function function, final Scope param) {
        final var clazz = param.getCurrentClass();

        final var method = compilerContext.getElementBuilders()
                .executableElementBuilder()
                .enclosingElement(clazz)
                .build();
        function.setMethodSymbol(method);
        return super.visitFunction(function, param);
    }

    @Override
    public Tree visitLiteralExpression(final LiteralExpressionTree literalExpression,
                                       final Scope scope) {
        if (literalExpression.getLiteralKind() == LiteralExpressionTree.Kind.STRING) {
            final var stringType = compilerContext.getClassElementLoader().loadClass(
                    null,
                    Constants.STRING
            ).asType();
            literalExpression.setType(stringType);
        }

        return super.visitLiteralExpression(literalExpression, scope);
    }

    @Override
    public Tree visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final Scope scope) {
        final var name = TreeUtils.getClassName(fieldAccessExpression);

        final var clazz = compilerContext.getClassElementLoader().loadClass(
                null,
                name
        );

        if (clazz != null) {
            fieldAccessExpression.setType(clazz.asType());
            fieldAccessExpression.getField().setType(clazz.asType());
        }

        return super.visitFieldAccessExpression(fieldAccessExpression, scope);
    }

    @Override
    public Tree visitIdentifier(final IdentifierTree identifier, final Scope scope) {
        final var name = identifier.getName();
        final var symbol = scope.resolve(name);

        if (symbol != null) {
            identifier.setSymbol(symbol);
        }

        return super.visitIdentifier(identifier, scope);
    }
}