package io.github.potjerodekool.nabu.compiler.frontend.parser.java;

import io.github.potjerodekool.nabu.Java20Lexer;
import io.github.potjerodekool.nabu.Java20Parser;
import io.github.potjerodekool.nabu.Java20ParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.frontend.parser.MethodDeclarator;
import io.github.potjerodekool.nabu.compiler.frontend.parser.MethodHeader;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.PrimitiveTypeTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CBlockStatementTree;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.github.potjerodekool.nabu.compiler.frontend.parser.SourceVisitor.createFunction;
import static io.github.potjerodekool.nabu.compiler.frontend.parser.SourceVisitor.processImportExpression;

public class JavaCompilerVisitor extends Java20ParserBaseVisitor<Object> {

    private final FileObject fileObject;

    public JavaCompilerVisitor(final FileObject fileObject) {
        this.fileObject = fileObject;
    }

    @Override
    public Object visitOrdinaryCompilationUnit(final Java20Parser.OrdinaryCompilationUnitContext ctx) {
        final var declarations = new ArrayList<Tree>();

        if (ctx.packageDeclaration() != null) {
            declarations.add((Tree) ctx.packageDeclaration().accept(this));
        }

        final var importItems = parseImports(ctx.importDeclaration());

        declarations.addAll(ctx.topLevelClassOrInterfaceDeclaration()
                .stream()
                .map(it -> (Tree) it.accept(this))
                .toList());

        return TreeMaker.compilationUnit(
                fileObject,
                importItems,
                declarations,
                ctx.start.getLine(),
                ctx.start.getCharPositionInLine()
        );
    }

    @Override
    public Object visitPackageDeclaration(final Java20Parser.PackageDeclarationContext ctx) {
        final var annotations = ctx.packageModifier().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var packageNameIdentifiers = ctx.identifier().stream()
                .map(it -> (IdentifierTree) it.accept(this))
                .toList();

        ExpressionTree packageName;

        if (packageNameIdentifiers.isEmpty()) {
            packageName = TreeMaker.errorTree(
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            packageName = packageNameIdentifiers.getFirst();

            for (var i = 1; i < packageNameIdentifiers.size(); i++) {
                final var field = packageNameIdentifiers.get(i);
                packageName = new FieldAccessExpressionBuilder()
                        .selected(packageName)
                        .field(field)
                        .lineNumber(packageName.getLineNumber())
                        .columnNumber(packageName.getColumnNumber())
                        .build();
            }
        }

        return TreeMaker.packageDeclaration(
                annotations,
                packageName,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    private List<ImportItem> parseImports(final List<Java20Parser.ImportDeclarationContext> importsDeclarations) {
        return importsDeclarations.stream()
                .map(importDeclaration -> ((ImportItem) importDeclaration.accept(this)))
                .toList();
    }

    @Override
    public Object visitStaticImportOnDemandDeclaration(final Java20Parser.StaticImportOnDemandDeclarationContext ctx) {
        final var typeName = (ExpressionTree) ctx.typeName().accept(this);
        final var qualified = new FieldAccessExpressionBuilder()
                .selected(typeName)
                .field(TreeMaker.identifier(
                        "*",
                        -1,
                        -1
                ))
                .build();

        return TreeMaker.importItem(
                qualified,
                true,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitTypeImportOnDemandDeclaration(final Java20Parser.TypeImportOnDemandDeclarationContext ctx) {
        final var packageOrTypeName = (ExpressionTree) ctx.packageOrTypeName().accept(this);
        final var qualified = new FieldAccessExpressionBuilder()
                .selected(packageOrTypeName)
                .field(TreeMaker.identifier(
                        "*",
                        -1,
                        -1
                ))
                .build();

        return TreeMaker.importItem(
                qualified,
                false,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitSingleStaticImportDeclaration(final Java20Parser.SingleStaticImportDeclarationContext ctx) {
        final var typeName = (ExpressionTree) ctx.typeName().accept(this);
        final var identifier = (ExpressionTree) ctx.identifier().accept(this);
        final var qualified = new FieldAccessExpressionBuilder()
                .selected(typeName)
                .field(identifier)
                .build();

        return TreeMaker.importItem(
                qualified,
                true,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitSingleTypeImportDeclaration(final Java20Parser.SingleTypeImportDeclarationContext ctx) {
        final var qualified = processImportExpression((ExpressionTree) ctx.typeName().accept(this));
        return TreeMaker.importItem(
                qualified,
                false,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine());
    }

    @Override
    public Object visitTypeName(final Java20Parser.TypeNameContext ctx) {
        final var pckName = (ExpressionTree) ctx.packageName().accept(this);

        if (ctx.typeIdentifier() == null) {
            return pckName;
        } else {
            final IdentifierTree identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);
            return TreeMaker.fieldAccessExpressionTree(
                    pckName,
                    identifier,
                    pckName.getLineNumber(),
                    pckName.getColumnNumber()
            );
        }
    }

    @Override
    public Object visitPackageName(final Java20Parser.PackageNameContext ctx) {
        final IdentifierTree identifier = (IdentifierTree) ctx.identifier().accept(this);
        final ExpressionTree packageName = accept(ctx.packageName());

        if (packageName == null) {
            return TreeMaker.identifier(
                    identifier.getName(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return TreeMaker.fieldAccessExpressionTree(
                    identifier,
                    packageName,
                    identifier.getLineNumber(),
                    identifier.getColumnNumber()
            );
        }
    }

    @Override
    public Object visitNormalInterfaceDeclaration(final Java20Parser.NormalInterfaceDeclarationContext ctx) {
        var modifiers = parseModifiers(ctx.interfaceModifier());
        final var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);
        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());
        final List<ExpressionTree> extensions = acceptList(ctx.interfaceExtends());
        final List<IdentifierTree> permits = acceptList(ctx.interfacePermits());
        final List<Tree> body = acceptList(ctx.interfaceBody());

        if (!modifiers.hasFlag(Flags.ABSTRACT)) {
            modifiers = modifiers.with(Flags.ABSTRACT);
        }

        return new ClassDeclarationBuilder()
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .kind(Kind.INTERFACE)
                .modifiers(modifiers)
                .simpleName(identifier.getName())
                .typeParameters(typeParameters)
                .implemention(extensions)
                .permits(permits)
                .enclosedElements(body)
                .build();
    }

    @Override
    public Object visitInterfaceExtends(final Java20Parser.InterfaceExtendsContext ctx) {
        return ctx.interfaceTypeList().accept(this);
    }

    @Override
    public Object visitInterfacePermits(final Java20Parser.InterfacePermitsContext ctx) {
        return ctx.typeName().stream()
                .map(typeName -> typeName.accept(this))
                .toList();
    }

    @Override
    public Object visitInterfaceBody(final Java20Parser.InterfaceBodyContext ctx) {
        return ctx.interfaceMemberDeclaration().stream()
                .map(decl -> decl.accept(this))
                .toList();
    }

    @Override
    public Object visitInterfaceMethodDeclaration(final Java20Parser.InterfaceMethodDeclarationContext ctx) {
        var modifiers = parseModifiers(ctx.interfaceMethodModifier());

        if (!modifiers.hasFlag(Flags.DEFAULT) && !modifiers.hasFlag(Flags.ABSTRACT)) {
            modifiers = modifiers.with(Flags.ABSTRACT);
        }

        final var functionHeader = (MethodHeader) ctx.methodHeader().accept(this);
        final BlockStatementTree body = accept(ctx.methodBody());
        return createFunction(
                modifiers,
                functionHeader,
                body,
                ctx
        );
    }

    @Override
    public Object visitNormalClassDeclaration(final Java20Parser.NormalClassDeclarationContext ctx) {
        final var classModifiers = parseModifiers(ctx.classModifier());
        final var simpleName = ((IdentifierTree) ctx.typeIdentifier().accept(this)).getName();
        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());
        final ExpressionTree extending = accept(ctx.classExtends());
        final List<ExpressionTree> implementations = acceptList(ctx.classImplements());
        final List<IdentifierTree> permits = acceptList(ctx.classPermits());
        final List<Tree> enclosedElements = accept(ctx.classBody());

        return TreeMaker.classDeclaration(
                Kind.CLASS,
                classModifiers,
                simpleName,
                enclosedElements,
                typeParameters,
                implementations,
                extending,
                permits,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitClassBody(final Java20Parser.ClassBodyContext ctx) {
        return ctx.classBodyDeclaration().stream()
                .flatMap(it -> asStream(it.accept(this)))
                .toList();
    }

    private Stream<Object> asStream(final Object object) {
        if (object instanceof List<?> list) {
            return (Stream<Object>) list.stream();
        } else {
            return Stream.of(object);
        }
    }

    @Override
    public Object visitFieldDeclaration(final Java20Parser.FieldDeclarationContext ctx) {
        final var fieldModifiers = parseModifiers(ctx.fieldModifier());
        final var type = (ExpressionTree) ctx.unannType().accept(this);
        final List<VariableDeclaratorTree> variableDeclarators = acceptList(ctx.variableDeclaratorList());

        return variableDeclarators.stream()
                .map(fieldDeclaration -> fieldDeclaration.builder()
                        .kind(Kind.FIELD)
                        .modifiers(fieldModifiers)
                        .type(type)
                        .build())
                .toList();
    }

    @Override
    public Object visitVariableDeclaratorList(final Java20Parser.VariableDeclaratorListContext ctx) {
        return ctx.variableDeclarator().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitVariableDeclarator(final Java20Parser.VariableDeclaratorContext ctx) {
        final var variableDeclaratorId = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
        final var init = (ExpressionTree) accept(ctx.variableInitializer());

        return TreeMaker.variableDeclarator(
                null,
                new CModifiers(),
                null,
                variableDeclaratorId,
                null,
                init,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitIdentifier(final Java20Parser.IdentifierContext ctx) {
        if (ctx.Identifier() != null) {
            return TreeMaker.identifier(
                    ctx.Identifier().getText(),
                    -1,
                    -1
            );
        }

        throw new TodoException();
    }

    @Override
    public Object visitUnannPrimitiveType(final Java20Parser.UnannPrimitiveTypeContext ctx) {
        if (ctx.numericType() != null) {
            return ctx.numericType().accept(this);
        } else {
            final var line = ctx.getStart().getLine();
            final var charPositionsInLine = ctx.getStart().getCharPositionInLine();
            return TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.BOOLEAN, line, charPositionsInLine);
        }
    }

    @Override
    public Object visitUnannClassOrInterfaceType(final Java20Parser.UnannClassOrInterfaceTypeContext ctx) {
        ExpressionTree packageName = accept(ctx.packageName());
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());
        final var identifier = (ExpressionTree) ctx.typeIdentifier().accept(this);
        ExpressionTree expressionTree;

        if (packageName == null) {
            expressionTree = identifier;
        } else {
            if (!annotations.isEmpty()) {
                expressionTree = TreeMaker.annotatedTypeTree(
                        annotations,
                        identifier,
                        List.of(),
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                );
            } else {
                expressionTree = identifier;
            }

            expressionTree = TreeMaker.fieldAccessExpressionTree(
                    packageName,
                    expressionTree,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (ctx.typeArguments() != null) {
            final List<ExpressionTree> typeArguments = acceptList(ctx.typeArguments());
            expressionTree = TreeMaker.typeApplyTree(
                    expressionTree,
                    typeArguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (ctx.uCOIT() != null) {
            throw new TodoException();
        }

        return expressionTree;
    }

    @Override
    public Object visitTypeIdentifier(final Java20Parser.TypeIdentifierContext ctx) {
        if (ctx.Identifier() != null) {
            return TreeMaker.identifier(
                    ctx.Identifier().getText(),
                    -1,
                    -1
            );
        } else {
            throw new TodoException();
        }
    }

    @Override
    public Object visitMethodDeclaration(final Java20Parser.MethodDeclarationContext ctx) {
        var modifiers = parseModifiers(ctx.methodModifier());
        final var functionHeader = (MethodHeader) ctx.methodHeader().accept(this);
        final BlockStatementTree body = new CBlockStatementTree(List.of());
        return createFunction(
                modifiers,
                functionHeader,
                body,
                ctx
        );
    }

    @Override
    public Object visitMethodHeader(final Java20Parser.MethodHeaderContext ctx) {
        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());

        final var annotations = ctx.annotation().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var result = (ExpressionTree) ctx.result().accept(this);
        final var functionDeclarator = (MethodDeclarator) ctx.methodDeclarator().accept(this);

        final List<Tree> exceptions = acceptList(ctx.throwsT());

        return new MethodHeader(
                typeParameters,
                annotations,
                functionDeclarator,
                result,
                exceptions
        );
    }

    @Override
    public Object visitResult(final Java20Parser.ResultContext ctx) {
        if (ctx.unannType() != null) {
            return ctx.unannType().accept(this);
        } else {
            return TreeMaker.primitiveTypeTree(
                    PrimitiveTypeTree.Kind.VOID,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Object visitMethodDeclarator(final Java20Parser.MethodDeclaratorContext ctx) {
        final var name = (IdentifierTree) ctx.identifier().accept(this);

        final VariableDeclaratorTree receiverParameter = accept(ctx.receiverParameter());

        final List<VariableDeclaratorTree> parameters = acceptList(ctx.formalParameterList());

        return new MethodDeclarator(
                receiverParameter,
                name.getName(),
                parameters
        );
    }

    @Override
    public Object visitReceiverParameter(final Java20Parser.ReceiverParameterContext ctx) {
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());
        final ExpressionTree type = (ExpressionTree) ctx.unannType().accept(this);
        final ExpressionTree identifier = accept(ctx.identifier());
        final ExpressionTree nameExpression;

        if (identifier != null) {
            nameExpression = TreeMaker.fieldAccessExpressionTree(
                    identifier,
                    IdentifierTree.create(Constants.THIS),
                    identifier.getLineNumber(),
                    identifier.getColumnNumber()
            );
        } else {
            nameExpression = IdentifierTree.create(Constants.THIS);
        }

        return TreeMaker.variableDeclarator(
                Kind.PARAMETER,
                new CModifiers(
                        annotations,
                        0L
                ),
                type,
                IdentifierTree.create(Constants.THIS),
                nameExpression,
                null,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitFormalParameterList(final Java20Parser.FormalParameterListContext ctx) {
        return ctx.formalParameter().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitFormalParameter(final Java20Parser.FormalParameterContext ctx) {
        final var modifiers = parseModifiers(ctx.variableModifier());

        final var name = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
        final var type = (ExpressionTree) ctx.unannType().accept(this);

        return TreeMaker.variableDeclarator(
                Kind.PARAMETER,
                modifiers,
                type,
                TreeMaker.identifier(
                        name.getName(),
                        name.getLineNumber(),
                        name.getColumnNumber()
                ),
                null,
                null,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }


    private CModifiers parseModifiers(final List<? extends ParserRuleContext> modifierList) {
        final List<AnnotationTree> annotations = new ArrayList<>();
        long flags = 0L;

        for (final var modifier : modifierList) {
            final var result = modifier.accept(this);

            if (result instanceof AnnotationTree annotationTree) {
                annotations.add(annotationTree);
            } else if (result instanceof Long flag) {
                flags += flag;
            }
        }

        return new CModifiers(annotations, flags);
    }

    @Override
    public Object visitAnnotation(final Java20Parser.AnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitTerminal(final TerminalNode node) {
        final var type = node.getSymbol().getType();
        final var line = node.getSymbol().getLine();
        final var charPositionInLine = node.getSymbol().getCharPositionInLine();

        return switch (type) {
            case Java20Lexer.INT -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.INT, line, charPositionInLine);
            case Java20Lexer.BYTE -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.BYTE, line, charPositionInLine);
            case Java20Lexer.SHORT ->
                    TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.SHORT, line, charPositionInLine);
            case Java20Lexer.LONG -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.LONG, line, charPositionInLine);
            case Java20Lexer.CHAR -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.CHAR, line, charPositionInLine);
            case Java20Lexer.FLOAT ->
                    TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.FLOAT, line, charPositionInLine);
            case Java20Lexer.DOUBLE ->
                    TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.DOUBLE, line, charPositionInLine);
            case Java20Lexer.BOOLEAN ->
                    TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.BOOLEAN, line, charPositionInLine);
            case Java20Lexer.PUBLIC -> Flags.PUBLIC;
            case Java20Lexer.PROTECTED -> Flags.PROTECTED;
            case Java20Lexer.PRIVATE -> Flags.PRIVATE;
            case Java20Lexer.ABSTRACT -> Flags.ABSTRACT;
            case Java20Lexer.STATIC -> Flags.STATIC;
            case Java20Lexer.FINAL -> Flags.FINAL;
            case Java20Lexer.SEALED -> null; //TODO
            case Java20Lexer.NONSEALED -> null; //TODO
            case Java20Lexer.STRICTFP -> null; //TODO
            default -> null;
        };
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

    private <T> T accept(final ParserRuleContext parserRuleContext) {
        if (parserRuleContext == null) {
            return null;
        } else {
            return (T) parserRuleContext.accept(this);
        }
    }

    private <E> List<E> acceptList(final List<? extends ParserRuleContext> contextList) {
        if (contextList == null) {
            return List.of();
        } else {
            return contextList.stream()
                    .map(it -> (E) accept(it))
                    .toList();
        }
    }

    private <E> List<E> acceptList(final ParseTree context) {
        if (context == null) {
            return List.of();
        } else {
            final var result = context.accept(this);

            if (result instanceof List<?> list) {
                return (List<E>) list;
            } else {
                return (List<E>) List.of(result);
            }
        }
    }
}
