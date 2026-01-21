package io.github.potjerodekool.nabu.plugin.jpa.testing;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.impl.TypeEnter;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.spi.SourceParser;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.scope.SymbolScope;
import io.github.potjerodekool.nabu.testing.InMemoryFileObject;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeUtils;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.type.DeclaredType;

public class TreeParser {

    private final CompilerContext compilerContext;

    private TreeParser(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    public static CompilationUnit parse(final String code,
                                        final String fileName,
                                        final CompilerContext context,
                                        final java.util.function.Function<Scope, Scope> scopeCreator,
                                        final SourceParser sourceParser) {
        final var treeParser = new TreeParser(context);

        final var compilationUnit = sourceParser.parse(
                new InMemoryFileObject(code, fileName),
                context
        );

        treeParser.processAst(scopeCreator, compilationUnit);

        final var typeEnter = new TypeEnter(context);

        compilationUnit.getClasses().forEach(classDeclaration -> {
            final var symbol = (ClassSymbol) classDeclaration.getClassSymbol();
            typeEnter.put(symbol, classDeclaration, compilationUnit);
            symbol.setCompleter(typeEnter);
            symbol.complete();
        });

        return compilationUnit;
    }

    private void processAst(final java.util.function.Function<Scope, Scope> scopeCreator,
                            final CompilationUnit compilationUnit) {
        final var globalScope = new GlobalScope(compilationUnit, compilerContext);
        final var scope = scopeCreator.apply(globalScope);
        final var postProcessor = new PostProcessor(compilerContext);
        postProcessor.acceptTree(compilationUnit, scope);
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