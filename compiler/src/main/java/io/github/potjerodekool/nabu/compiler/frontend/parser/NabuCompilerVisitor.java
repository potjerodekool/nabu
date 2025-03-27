package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.NabuParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.ast.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.builder.CatchTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.*;
import io.github.potjerodekool.nabu.compiler.tree.impl.CErrorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.TryStatementTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.compiler.type.BoundKind;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class NabuCompilerVisitor extends NabuParserBaseVisitor<Object> {

    private final FileObject fileObject;

    public NabuCompilerVisitor(final FileObject fileObject) {
        this.fileObject = fileObject;
    }

    @Override
    public Object visitOrdinaryCompilationUnit(final NabuParser.OrdinaryCompilationUnitContext ctx) {
        var name = fileObject.getFileName();
        var start = name.lastIndexOf(File.separatorChar) + 1;
        final var end = name.lastIndexOf(".");
        name = name.substring(start, end);

        final var startToken = ctx.getStart();

        final var declarations = new ArrayList<Tree>();

        if (ctx.packageDeclaration() != null) {
            declarations.add((Tree) ctx.packageDeclaration().accept(this));
        }

        final var importItems = parseImports(ctx.importDeclaration());

        if (!ctx.topLevelClassOrInterfaceDeclaration().isEmpty()) {
            declarations.addAll(ctx.topLevelClassOrInterfaceDeclaration()
                    .stream()
                    .map(it -> (Tree) it.accept(this))
                    .toList());
        } else {
            final var functions = ctx.functionDeclaration()
                    .stream()
                    .map(it -> (Tree) it.accept(this))
                    .toList();

            final var clazz = TreeMaker.classDeclaration(
                    Kind.CLASS,
                    new CModifiers(List.of(), Flags.PUBLIC),
                    name,
                    functions,
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    startToken.getLine(),
                    startToken.getCharPositionInLine()
            );

            declarations.add(clazz);
        }

        return TreeMaker.compilationUnit(
                fileObject,
                importItems,
                declarations,
                ctx.start.getLine(),
                ctx.start.getCharPositionInLine()
        );
    }

    private List<ImportItem> parseImports(final List<NabuParser.ImportDeclarationContext> importsDeclarations) {
        return importsDeclarations.stream()
                .map(importDeclaration -> ((ImportItem) importDeclaration.accept(this)))
                .toList();
    }

    @Override
    public Object visitModularCompilationUnit(final NabuParser.ModularCompilationUnitContext ctx) {
        final var importItems = parseImports(ctx.importDeclaration());
        final var module = (ModuleDeclaration) ctx.moduleDeclaration().accept(this);
        return TreeMaker.compilationUnit(
                fileObject,
                importItems,
                List.of(module),
                ctx.start.getLine(),
                ctx.start.getCharPositionInLine()
        );
    }

    @Override
    public Object visitModuleDeclaration(final NabuParser.ModuleDeclarationContext ctx) {
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());

        final var identifiers = ctx.identifier();
        var identifier = (ExpressionTree) identifiers.getFirst().accept(this);

        for (var i = 1; i < identifiers.size(); i++) {
            final var expression = (ExpressionTree) identifiers.get(i).accept(this);
            identifier = new FieldAccessExpressionBuilder()
                    .lineNumber(identifier.getLineNumber())
                    .columnNumber(identifier.getColumnNumber())
                    .target(identifier)
                    .field(expression)
                    .build();
        }

        final var directives = ctx.moduleDirective().stream()
                .map(directive -> (DirectiveTree) directive.accept(this))
                .toList();

        final var kind = ctx.open != null
                ? ModuleDeclaration.ModuleKind.OPEN
                : ModuleDeclaration.ModuleKind.STRONG;

        return new CModuleDeclaration(
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine(),
                kind,
                identifier,
                directives,
                annotations
        );
    }

    @Override
    public Object visitModuleDirective(final NabuParser.ModuleDirectiveContext ctx) {
        final var lineNumber = ctx.getStart().getLine();
        final var columnNumber = ctx.getStart().getCharPositionInLine();
        final var directiveType = ctx.directive.getText();
        final var directiveKind = parseDirectiveKind(directiveType);

        return switch (directiveKind) {
            case REQUIRES -> {
                final var flags = ctx.requiresModifier().stream()
                        .mapToLong(it -> (Long) it.accept(this))
                        .sum();
                final var moduleName = (ExpressionTree) ctx.moduleName().getFirst().accept(this);

                yield new CRequiresTree(
                        flags,
                        moduleName,
                        lineNumber,
                        columnNumber
                );
            }
            case EXPORTS -> {
                final var packageName = (IdentifierTree) ctx.packageName().accept(this);
                final var moduleNames = ctx.moduleName().stream()
                        .map(moduleName -> (ExpressionTree) moduleName.accept(this))
                        .toList();

                yield new CExportsTree(
                        packageName,
                        moduleNames,
                        lineNumber,
                        columnNumber
                );
            }
            case OPENS -> {
                final var packageName = (IdentifierTree) ctx.packageName().accept(this);
                final var moduleNames = ctx.moduleName().stream()
                        .map(moduleName -> (ExpressionTree) moduleName.accept(this))
                        .toList();

                yield new COpensTree(
                        packageName,
                        moduleNames,
                        lineNumber,
                        columnNumber
                );
            }
            case USES -> {
                final var typeName = (ExpressionTree) ctx.typeName().getFirst().accept(this);

                yield new CUsesTree(
                        typeName,
                        lineNumber,
                        columnNumber
                );
            }
            case PROVIDES -> {
                final var typeNames = ctx.typeName().stream()
                        .map(it -> (ExpressionTree) it.accept(this))
                        .toList();
                final var implementations = typeNames.subList(1, typeNames.size());

                yield new CProvidesTree(
                        typeNames.getFirst(),
                        implementations,
                        lineNumber,
                        columnNumber
                );
            }
            case null -> new CErrorTree(
                    lineNumber,
                    columnNumber
            );
        };
    }

    private ModuleElement.DirectiveKind parseDirectiveKind(final String directive) {
        return Arrays.stream(ModuleElement.DirectiveKind.values())
                .filter(kind -> kind.name().toLowerCase().equals(directive))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Object visitModuleName(final NabuParser.ModuleNameContext ctx) {
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);

        if (ctx.moduleName() == null) {
            return identifier;
        } else {
            final var moduleName = (ExpressionTree) ctx.moduleName().accept(this);
            return new FieldAccessExpressionBuilder()
                    .target(identifier)
                    .field(moduleName)
                    .lineNumber(ctx.getStart().getLine())
                    .columnNumber(ctx.getStart().getCharPositionInLine())
                    .build();
        }
    }

    @Override
    public Tree visitPackageDeclaration(final NabuParser.PackageDeclarationContext ctx) {
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
                        .target(packageName)
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

    private FieldAccessExpressionTree processImportExpression(final ExpressionTree expressionTree) {
        if (expressionTree instanceof IdentifierTree identifierTree) {
            final var names = identifierTree.getName().split("\\.");

            if (names.length == 1) {
                return new FieldAccessExpressionBuilder()
                        .field(identifierTree)
                        .build();
            } else {
                ExpressionTree importExpression = TreeMaker.identifier(
                        names[0],
                        -1,
                        -1
                );

                for (int i = 1; i < names.length; i++) {
                    importExpression = new FieldAccessExpressionBuilder()
                            .target(importExpression)
                            .field(
                                    TreeMaker.identifier(
                                            names[i],
                                            -1,
                                            -1
                                    )
                            )
                            .build();
                }

                return (FieldAccessExpressionTree) importExpression;
            }
        } else {
            return (FieldAccessExpressionTree) expressionTree;
        }
    }

    @Override
    public Tree visitSingleTypeImportDeclaration(final NabuParser.SingleTypeImportDeclarationContext ctx) {
        final var qualified = processImportExpression((ExpressionTree) ctx.typeName().accept(this));
        return TreeMaker.importItem(
                qualified,
                false,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine());
    }


    @Override
    public Object visitTypeImportOnDemandDeclaration(final NabuParser.TypeImportOnDemandDeclarationContext ctx) {
        final var packageOrTypeName = (ExpressionTree) ctx.packageOrTypeName().accept(this);
        final var qualified = new FieldAccessExpressionBuilder()
                .target(packageOrTypeName)
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
    public Object visitSingleStaticImportDeclaration(final NabuParser.SingleStaticImportDeclarationContext ctx) {
        final var typeName = (ExpressionTree) ctx.typeName().accept(this);
        final var identifier = (ExpressionTree) ctx.identifier().accept(this);
        final var qualified = new FieldAccessExpressionBuilder()
                .target(typeName)
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
    public Object visitStaticImportOnDemandDeclaration(final NabuParser.StaticImportOnDemandDeclarationContext ctx) {
        final var typeName = (ExpressionTree) ctx.typeName().accept(this);
        final var qualified = new FieldAccessExpressionBuilder()
                .target(typeName)
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
    public Tree visitTypeName(final NabuParser.TypeNameContext ctx) {
        final IdentifierTree pckName = (IdentifierTree) ctx.packageName().accept(this);

        if (ctx.typeIdentifier() == null) {
            return pckName;
        } else {
            final IdentifierTree identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);
            return TreeMaker.identifier(
                    pckName.getName()
                            + "."
                            + identifier.getName(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Tree visitPackageOrTypeName(final NabuParser.PackageOrTypeNameContext ctx) {
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);
        final var packageOrTypeName = (ExpressionTree) accept(ctx.packageOrTypeName());

        if (packageOrTypeName == null) {
            return identifier;
        } else {
            return new FieldAccessExpressionBuilder()
                    .target(identifier)
                    .field(packageOrTypeName)
                    .lineNumber(ctx.getStart().getLine())
                    .columnNumber(ctx.getStart().getCharPositionInLine())
                    .build();
        }
    }

    @Override
    public Object visitFunctionDeclaration(final NabuParser.FunctionDeclarationContext ctx) {
        var modifiers = parseModifiers(ctx.functionModifier());
        final var functionHeader = (FunctionHeader) ctx.functionHeader().accept(this);
        final BlockStatementTree body = accept(ctx.functionBody());
        return createFunction(
                modifiers,
                functionHeader,
                body,
                ctx
        );
    }

    private Function createFunction(final CModifiers modifiers,
                                    final FunctionHeader functionHeader,
                                    final BlockStatementTree body,
                                    final ParserRuleContext ctx) {
        var fullModifiers = modifiers;

        final var receiverParameter = functionHeader.functionDeclarator().receiverParameter();
        final var functionName = functionHeader.functionDeclarator().name();
        final var returnType = functionHeader.result();
        final var exceptions = functionHeader.exceptions();
        final List<VariableDeclaratorTree> params = functionHeader.functionDeclarator().parameters();

        if (!functionHeader.annotations().isEmpty()) {
            final var annotations = new ArrayList<>(modifiers.getAnnotations());
            annotations.addAll(functionHeader.annotations());
            fullModifiers = new CModifiers(
                    annotations,
                    modifiers.getFlags()
            );
        }

        return new FunctionBuilder()
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .kind(Kind.METHOD)
                .modifiers(fullModifiers)
                .typeParameters(functionHeader.typeParameters())
                .returnType(returnType)
                .simpleName(functionName)
                .receiver(receiverParameter)
                .parameters(params)
                .thrownTypes(exceptions)
                .body(body)
                .build();
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
    public Object visitFunctionHeader(final NabuParser.FunctionHeaderContext ctx) {
        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());

        final var annotations = ctx.annotation().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var result = (ExpressionTree) ctx.result().accept(this);
        final var functionDeclarator = (FunctionDeclarator) ctx.functionDeclarator().accept(this);

        final List<Tree> exceptions = acceptList(ctx.throwsT());

        return new FunctionHeader(
                typeParameters,
                annotations,
                functionDeclarator,
                result,
                exceptions
        );
    }

    @Override
    public Object visitThrowsT(final NabuParser.ThrowsTContext ctx) {
        return ctx.exceptionTypeList().accept(this);
    }

    @Override
    public Object visitExceptionTypeList(final NabuParser.ExceptionTypeListContext ctx) {
        return ctx.exceptionType().stream()
                .map(exceptionType -> exceptionType.accept(this))
                .toList();
    }

    @Override
    public Object visitFunctionDeclarator(final NabuParser.FunctionDeclaratorContext ctx) {
        final var name = (IdentifierTree) ctx.identifier().accept(this);

        final VariableDeclaratorTree receiverParameter = accept(ctx.receiverParameter());

        final List<VariableDeclaratorTree> parameters = acceptList(ctx.formalParameterList());

        return new FunctionDeclarator(
                receiverParameter,
                name.getName(),
                parameters
        );
    }

    @Override
    public Object visitReceiverParameter(final NabuParser.ReceiverParameterContext ctx) {
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
    public Object visitFormalParameterList(final NabuParser.FormalParameterListContext ctx) {
        return ctx.formalParameter().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitFormalParameter(final NabuParser.FormalParameterContext ctx) {
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

    @Override
    public Tree visitReturnStatement(final NabuParser.ReturnStatementContext ctx) {
        final ExpressionTree expression = accept(ctx.expression());
        return TreeMaker.returnStatement(
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Tree visitLambdaExpression(final NabuParser.LambdaExpressionContext ctx) {
        final List<VariableDeclaratorTree> parameters = acceptList(ctx.lambdaParameters());
        final var body = ctx.lambdaBody().accept(this);
        final StatementTree bodyStatement =
                body instanceof ExpressionTree expression
                        ? TreeMaker.expressionStatement(
                        expression,
                        expression.getLineNumber(),
                        expression.getColumnNumber()
                )
                        : (StatementTree) body;
        return TreeMaker.lambdaExpressionTree(
                parameters,
                bodyStatement,
                ctx.start.getLine(),
                ctx.start.getCharPositionInLine()
        );
    }

    @Override
    public Object visitLambdaParameters(final NabuParser.LambdaParametersContext ctx) {
        if (ctx.lambdaParameterList() != null) {
            return acceptList(ctx.lambdaParameterList());
        } else {
            return ctx.identifier().accept(this);
        }
    }

    @Override
    public Object visitLambdaParameterList(final NabuParser.LambdaParameterListContext ctx) {
        if (!ctx.lambdaParameter().isEmpty()) {
            return ctx.lambdaParameter().stream()
                    .map(it -> it.accept(this))
                    .toList();
        } else {
            return ctx.identifier().stream()
                    .map(it -> it.accept(this))
                    .toList();
        }
    }

    @Override
    public Object visitLambdaParameter(final NabuParser.LambdaParameterContext ctx) {
        final var name = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
        final var type = (ExpressionTree) ctx.lambdaParameterType().accept(this);

        return TreeMaker.variableDeclarator(
                Kind.PARAMETER,
                new CModifiers(),
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

    @Override
    public Tree visitEqualityExpression(final NabuParser.EqualityExpressionContext ctx) {
        final var right = (ExpressionTree) ctx.relationalExpression().accept(this);

        if (ctx.equalityExpression() != null) {
            final ExpressionTree left = (ExpressionTree) ctx.equalityExpression().accept(this);
            final var operatorText = ctx.oper.getText();
            return TreeMaker.binaryExpressionTree(
                    left,
                    Tag.fromText(operatorText),
                    right,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return right;
        }
    }

    @Override
    public Tree visitRelationalExpression(final NabuParser.RelationalExpressionContext ctx) {
        final var shiftExpression = (ExpressionTree) accept(ctx.shiftExpression());
        final ExpressionTree relationalExpression = accept(ctx.relationalExpression());
        final ExpressionTree referenceType = accept(ctx.referenceType());

        if (referenceType != null) {
            return TreeMaker.instanceOfExpression(
                    relationalExpression,
                    referenceType,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else if (relationalExpression != null) {
            final var tag = Tag.fromText(ctx.oper.getText());

            return TreeMaker.binaryExpressionTree(
                    relationalExpression,
                    tag,
                    shiftExpression,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return shiftExpression;
        }
    }

    @Override
    public Object visitShiftExpression(final NabuParser.ShiftExpressionContext ctx) {
        return ctx.additiveExpression().accept(this);
    }

    @Override
    public Object visitAdditiveExpression(final NabuParser.AdditiveExpressionContext ctx) {
        final var multiplicativeExpression = (ExpressionTree) ctx.multiplicativeExpression().accept(this);
        final var additiveExpression = (ExpressionTree) accept(ctx.additiveExpression());

        if (additiveExpression == null) {
            return multiplicativeExpression;
        } else {
            //If one of them is a String literal and the other isn't a null literal then merge them.
            if (isStringLiteral(additiveExpression)) {
                final var left = (LiteralExpressionTree) additiveExpression;

                if (multiplicativeExpression instanceof LiteralExpressionTree right
                        && right.getLiteralKind() != LiteralExpressionTree.Kind.NULL) {
                    return mergeLiterals(left, right);
                }
            } else if (isStringLiteral(multiplicativeExpression)) {
                final var right = (LiteralExpressionTree) multiplicativeExpression;

                if (additiveExpression instanceof LiteralExpressionTree left
                        && left.getLiteralKind() != LiteralExpressionTree.Kind.NULL) {
                    return mergeLiterals(left, right);
                }
            }

            final var tag = "+".equals(ctx.oper.getText())
                    ? Tag.ADD
                    : Tag.SUB;

            return TreeMaker.binaryExpressionTree(
                    additiveExpression,
                    tag,
                    multiplicativeExpression,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    private boolean isStringLiteral(final ExpressionTree expressionTree) {
        return expressionTree instanceof LiteralExpressionTree literalExpressionTree
                && literalExpressionTree.getLiteralKind() == LiteralExpressionTree.Kind.STRING;
    }

    private LiteralExpressionTree mergeLiterals(final LiteralExpressionTree left,
                                                final LiteralExpressionTree right) {
        final var leftValue = left.getLiteral().toString();
        final var rightValue = right.getLiteral().toString();
        return TreeMaker.literalExpressionTree(
                leftValue + rightValue,
                left.getLineNumber(),
                left.getColumnNumber()
        );
    }

    @Override
    public Object visitMultiplicativeExpression(final NabuParser.MultiplicativeExpressionContext ctx) {
        return ctx.unaryExpression().accept(this);
    }

    @Override
    public Object visitUnaryExpression(final NabuParser.UnaryExpressionContext ctx) {
        if (ctx.unaryExpression() != null) {
            final var prefix = ctx.prefix.getText();
            final var expr = (ExpressionTree) ctx.unaryExpression().accept(this);

            if ("-".equals(prefix) && expr instanceof LiteralExpressionTree literalExpressionTree) {
                return literalExpressionTree.negate();
            }

            final var tag = "-".equals(prefix) ? Tag.SUB : Tag.ADD;

            return TreeMaker.unaryExpressionTree(
                    tag,
                    expr,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return ctx.unaryExpressionNotPlusMinus().accept(this);
        }
    }

    @Override
    public Object visitUnaryExpressionNotPlusMinus(final NabuParser.UnaryExpressionNotPlusMinusContext ctx) {
        if (ctx.postfixExpression() != null) {
            return ctx.postfixExpression().accept(this);
        } else if (ctx.castExpression() != null) {
            return ctx.castExpression().accept(this);
        } else {
            final var expression = (ExpressionTree) ctx.unaryExpression().accept(this);
            return TreeMaker.unaryExpressionTree(
                    Tag.NOT,
                    expression,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Object visitCastExpression(final NabuParser.CastExpressionContext ctx) {
        final ExpressionTree expressionTree;
        ExpressionTree type;

        if (ctx.primitiveType() != null) {
            type = (ExpressionTree) ctx.primitiveType().accept(this);
            expressionTree = (ExpressionTree) ctx.unaryExpression().accept(this);
        } else {
            type = (ExpressionTree) ctx.referenceType().accept(this);

            if (!ctx.additionalBound().isEmpty()) {
                final var bounds = new ArrayList<Tree>();
                bounds.add(type);

                for (final var additionalBoundContext : ctx.additionalBound()) {
                    final var bound = (Tree) additionalBoundContext.accept(this);
                    bounds.add(bound);
                }

                type = new IntersectionTypeTreeBuilder()
                        .bounds(bounds)
                        .lineNumber(type.getLineNumber())
                        .columnNumber(type.getColumnNumber())
                        .build();
            }

            if (ctx.unaryExpressionNotPlusMinus() != null) {
                expressionTree = (ExpressionTree) ctx.unaryExpressionNotPlusMinus().accept(this);
            } else {
                expressionTree = (ExpressionTree) ctx.lambdaExpression().accept(this);
            }
        }

        return TreeMaker.castExpressionTree(
                type,
                expressionTree,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitPrimary(final NabuParser.PrimaryContext ctx) {
        return ctx.primaryNoNewArray().accept(this);
    }

    @Override
    public Object visitPrimaryNoNewArray(final NabuParser.PrimaryNoNewArrayContext ctx) {
        return visitPrimaryNoNewArrayNew(ctx);
    }

    public Object visitPrimaryNoNewArrayNew(final NabuParser.PrimaryNoNewArrayContext ctx) {
        ExpressionTree lastExpression = null;
        final var typeArguments = new ArrayList<IdentifierTree>();
        final var arguments = new ArrayList<ExpressionTree>();
        boolean isMethodCall = false;

        for (int c = 0; c < ctx.getChildCount(); c++) {
            final var child = ctx.getChild(c);

            switch (child) {
                case TerminalNode terminalNode -> {
                    if ("(".equals(terminalNode.getText())
                            && c > 0) {
                        isMethodCall = true;
                    } else if (")".equals(terminalNode.getText())) {
                        final ExpressionTree target;
                        final ExpressionTree name;

                        if (lastExpression instanceof FieldAccessExpressionTree fieldAccess) {
                            target = fieldAccess.getTarget();
                            name = fieldAccess.getField();
                        } else {
                            target = null;
                            name = lastExpression;
                        }

                        lastExpression = new MethodInvocationTreeBuilder()
                                .target(target)
                                .typeArguments(typeArguments)
                                .name(name)
                                .arguments(arguments)
                                .build();
                        isMethodCall = false;
                    } else {
                        lastExpression = combineExpressions(lastExpression, (ExpressionTree) child.accept(this));
                    }
                }
                case NabuParser.ArgumentListContext ignored -> arguments.addAll(acceptList(child));
                case NabuParser.TypeArgumentsContext ignored -> typeArguments.addAll(acceptList(child));
                default -> lastExpression = combineExpressions(lastExpression, (ExpressionTree) child.accept(this));
            }
        }

        return lastExpression;
    }

    private ExpressionTree combineExpressions(final ExpressionTree first,
                                              final ExpressionTree second) {
        if (first == null) {
            return second;
        } else if (second == null) {
            return first;
        } else if (second instanceof ArrayAccessExpressionTree arrayAccessExpressionTree) {
            return fillExpression(arrayAccessExpressionTree, first);
        } else if (second instanceof MethodInvocationTree methodInvocationTree) {
            return methodInvocationTree.builder()
                    .target(first)
                    .build();
        } else {
            return new FieldAccessExpressionBuilder()
                    .target(first)
                    .field(second)
                    .build();
        }
    }

    @Override
    public Tree visitExpressionName(final NabuParser.ExpressionNameContext ctx) {
        final var identifier = (ExpressionTree) ctx.identifier().accept(this);

        if (ctx.ambiguousName() != null) {
            final var ambiguousName = (ExpressionTree) ctx.ambiguousName().accept(this);
            return TreeMaker.fieldAccessExpressionTree(
                    ambiguousName,
                    identifier,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return identifier;
        }
    }

    @Override
    public Tree visitAmbiguousName(final NabuParser.AmbiguousNameContext ctx) {
        final var identifier = ctx.identifier();
        final var identifierTree = (IdentifierTree) identifier.accept(this);

        if (ctx.ambiguousName() != null) {
            final var ambiguousName = (ExpressionTree) ctx.ambiguousName().accept(this);
            return TreeMaker.fieldAccessExpressionTree(
                    identifierTree,
                    ambiguousName,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return identifierTree;
        }
    }

    private IdentifierTree identifier(final Token token) {
        return TreeMaker.identifier(
                token.getText(),
                token.getLine(),
                token.getCharPositionInLine() + 1);
    }

    @Override
    public Object visitTerminal(final TerminalNode node) {
        final var type = node.getSymbol().getType();
        final var line = node.getSymbol().getLine();
        final var charPositionInLine = node.getSymbol().getCharPositionInLine();

        return switch (type) {
            case NabuLexer.THIS,
                 NabuLexer.SUPER -> TreeMaker.identifier(
                    node.getText(),
                    line,
                    charPositionInLine
            );
            case NabuLexer.Identifier//, NabuLexer.DOT
                    -> identifier(node.getSymbol());
            case NabuLexer.INT -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.INT, line, charPositionInLine);
            case NabuLexer.BYTE -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.BYTE, line, charPositionInLine);
            case NabuLexer.SHORT -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.SHORT, line, charPositionInLine);
            case NabuLexer.LONG -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.LONG, line, charPositionInLine);
            case NabuLexer.CHAR -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.CHAR, line, charPositionInLine);
            case NabuLexer.FLOAT -> TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.FLOAT, line, charPositionInLine);
            case NabuLexer.DOUBLE ->
                    TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.DOUBLE, line, charPositionInLine);
            case NabuLexer.BOOLEAN ->
                    TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.BOOLEAN, line, charPositionInLine);
            case NabuLexer.ABSTRACT -> Flags.ABSTRACT;
            case NabuLexer.FINAL -> Flags.FINAL;
            case NabuLexer.PRIVATE -> Flags.PRIVATE;
            case NabuLexer.PROTECTED -> Flags.PROTECTED;
            case NabuLexer.PUBLIC -> Flags.PUBLIC;
            case NabuLexer.STATIC -> Flags.STATIC;
            case NabuLexer.NATIVE -> Flags.NATIVE;
            case NabuLexer.DEFAULT -> Flags.DEFAULT;
            case NabuLexer.TRANSITIVE -> Flags.TRANSITIVE;
            default -> null;
        };
    }

    @Override
    public Object visitUnannPrimitiveType(final NabuParser.UnannPrimitiveTypeContext ctx) {
        if (ctx.numericType() != null) {
            return ctx.numericType().accept(this);
        } else {
            final var line = ctx.getStart().getLine();
            final var charPositionsInLine = ctx.getStart().getCharPositionInLine();
            return TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.BOOLEAN, line, charPositionsInLine);
        }
    }

    @Override
    public Tree visitUnannClassOrInterfaceType(final NabuParser.UnannClassOrInterfaceTypeContext ctx) {
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
            final var ucoit = (ExpressionTree) ctx.uCOIT().accept(this);

            expressionTree = TreeMaker.fieldAccessExpressionTree(
                    expressionTree,
                    ucoit,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }


        return expressionTree;
    }

    @Override
    public Object visitUCOIT(final NabuParser.UCOITContext ctx) {
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());
        var expression = (ExpressionTree) ctx.typeIdentifier().accept(this);

        if (ctx.typeArguments() != null) {
            final List<IdentifierTree> typeArguments = acceptList(ctx.typeArguments());
            expression = TreeMaker.typeApplyTree(
                    expression,
                    typeArguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (ctx.uCOIT() != null) {
            final var uCoit = (ExpressionTree) ctx.uCOIT().accept(this);

            expression = TreeMaker.fieldAccessExpressionTree(
                    expression,
                    uCoit,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (annotations.isEmpty()) {
            return expression;
        } else {
            return TreeMaker.annotatedTypeTree(
                    annotations,
                    expression,
                    List.of(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Object visitClassOrInterfaceType(final NabuParser.ClassOrInterfaceTypeContext ctx) {
        ExpressionTree packageName = accept(ctx.packageName());
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());
        ExpressionTree result = (ExpressionTree) ctx.typeIdentifier().accept(this);

        if (!annotations.isEmpty()) {
            result = TreeMaker.annotatedTypeTree(
                    annotations,
                    result,
                    List.of(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (packageName != null) {
            result = TreeMaker.fieldAccessExpressionTree(
                    packageName,
                    result,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        final List<ExpressionTree> typeArguments = acceptList(ctx.typeArguments());

        if (!typeArguments.isEmpty()) {
            result = TreeMaker.typeApplyTree(
                    result,
                    typeArguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (ctx.coit() != null) {
            final var coit = (ExpressionTree) ctx.coit().accept(this);
            result = TreeMaker.fieldAccessExpressionTree(
                    result,
                    coit,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        return result;
    }

    @Override
    public Object visitCoit(final NabuParser.CoitContext ctx) {
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());
        var result = (ExpressionTree) ctx.typeIdentifier().accept(this);
        final List<ExpressionTree> typeArguments = acceptList(ctx.typeArguments());

        if (!annotations.isEmpty()) {
            result = TreeMaker.annotatedTypeTree(
                    annotations,
                    result,
                    List.of(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (!typeArguments.isEmpty()) {
            result = TreeMaker.typeApplyTree(
                    result,
                    typeArguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (ctx.coit() != null) {
            final var coit = (ExpressionTree) ctx.coit().accept(this);
            result = TreeMaker.fieldAccessExpressionTree(
                    result,
                    coit,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        return result;
    }

    @Override
    public Object visitTypeArguments(final NabuParser.TypeArgumentsContext ctx) {
        return ctx.typeArgumentList().accept(this);
    }

    @Override
    public Object visitTypeArgumentList(final NabuParser.TypeArgumentListContext ctx) {
        return ctx.typeArgument().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Tree visitPackageName(final NabuParser.PackageNameContext ctx) {
        final IdentifierTree identifier = (IdentifierTree) ctx.identifier().accept(this);
        final IdentifierTree packageName = accept(ctx.packageName());

        if (packageName == null) {
            return TreeMaker.identifier(
                    identifier.getName(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            final var first = identifier.getName();
            final var second = packageName.getName();
            return TreeMaker.identifier(
                    first + "." + second,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Tree visitTypeIdentifier(final NabuParser.TypeIdentifierContext ctx) {
        final var name = ctx.Identifier().getText();
        return TreeMaker.identifier(
                name,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitLocalVariableDeclaration(final NabuParser.LocalVariableDeclarationContext ctx) {
        final var modifiers = parseModifiers(ctx.variableModifier());

        final ExpressionTree type = (ExpressionTree) ctx.localVariableType().accept(this);

        final List<VariableDeclaratorTree> variableDeclarators = acceptList(ctx.variableDeclaratorList());

        final var list = variableDeclarators.stream()
                .map(variableDeclarator -> variableDeclarator.builder()
                        .kind(Kind.LOCAL_VARIABLE)
                        .modifiers(modifiers)
                        .type(type)
                        .lineNumber(ctx.getStart().getLine())
                        .columnNumber(ctx.getStart().getCharPositionInLine())
                        .build())
                .toList();

        if (list.size() == 1) {
            return list.getFirst();
        } else {
            return list;
        }
    }

    @Override
    public Object visitVariableDeclaratorList(final NabuParser.VariableDeclaratorListContext ctx) {
        return ctx.variableDeclarator().stream()
                .map(varDecl -> varDecl.accept(this))
                .toList();
    }

    @Override
    public Object visitVariableDeclarator(final NabuParser.VariableDeclaratorContext ctx) {
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
    public Object visitLocalVariableType(final NabuParser.LocalVariableTypeContext ctx) {
        if (ctx.unannType() != null) {
            return ctx.unannType().accept(this);
        } else {
            return TreeMaker.variableTypeTree(
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Object visitLocalVariableDeclarationStatement(final NabuParser.LocalVariableDeclarationStatementContext ctx) {
        return ctx.localVariableDeclaration().accept(this);
    }

    @Override
    public Tree visitLiteral(final NabuParser.LiteralContext ctx) {
        final Object value;
        final TerminalNode node;

        if (ctx.IntegerLiteral() != null) {
            node = ctx.IntegerLiteral();
            final var text = node.getText();

            if (text.toLowerCase().endsWith("l")) {
                value = Long.parseLong(text.substring(0, text.length() - 1));
            } else {
                value = Integer.parseInt(text);
            }
        } else if (ctx.BooleanLiteral() != null) {
            node = ctx.BooleanLiteral();
            value = Boolean.valueOf(node.getText());
        } else if (ctx.StringLiteral() != null) {
            node = ctx.StringLiteral();
            var text = node.getText();
            value = text.substring(1, text.length() - 1);
        } else if (ctx.NullLiteral() != null) {
            node = ctx.NullLiteral();
            value = null;
        } else if (ctx.CharacterLiteral() != null) {
            node = ctx.CharacterLiteral();
            value = ctx.CharacterLiteral().getText().charAt(1);
        } else {
            return null;
        }

        return TreeMaker.literalExpressionTree(value, node.getSymbol().getLine(), node.getSymbol().getCharPositionInLine());
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

    @Override
    public Tree visitExpressionStatement(final NabuParser.ExpressionStatementContext ctx) {
        final var expression = (ExpressionTree) ctx.statementExpression().accept(this);
        return TreeMaker.expressionStatement(
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitExpression(final NabuParser.ExpressionContext ctx) {
        if (ctx.lambdaExpression() != null) {
            return ctx.lambdaExpression().accept(this);
        } else {
            return ctx.assignmentExpression().accept(this);
        }
    }

    @Override
    public Object visitAssignmentExpression(final NabuParser.AssignmentExpressionContext ctx) {
        return ctx.conditionalExpression().accept(this);
    }

    @Override
    public Object visitAssignment(final NabuParser.AssignmentContext ctx) {
        final var leftHandSide = (ExpressionTree) ctx.leftHandSide().accept(this);
        final var assignmentOperator = (Tag) ctx.assignmentOperator().accept(this);
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        return TreeMaker.binaryExpressionTree(
                leftHandSide,
                assignmentOperator,
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitAssignmentOperator(final NabuParser.AssignmentOperatorContext ctx) {
        return Tag.fromText(ctx.start.getText());
    }

    @Override
    public Object visitConditionalExpression(final NabuParser.ConditionalExpressionContext ctx) {
        return ctx.conditionalOrExpression().accept(this);
    }

    @Override
    public Object visitConditionalOrExpression(final NabuParser.ConditionalOrExpressionContext ctx) {
        final var andExpression = (ExpressionTree) ctx.conditionalAndExpression().accept(this);

        if (ctx.conditionalOrExpression() == null) {
            return andExpression;
        } else {
            final var orExpression = (ExpressionTree) ctx.conditionalOrExpression().accept(this);
            return TreeMaker.binaryExpressionTree(
                    orExpression,
                    Tag.OR,
                    andExpression,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Tree visitConditionalAndExpression(final NabuParser.ConditionalAndExpressionContext ctx) {
        final var inclusiveOrExpression = (ExpressionTree) ctx.inclusiveOrExpression().accept(this);

        if (ctx.conditionalAndExpression() == null) {
            return inclusiveOrExpression;
        }

        final var andExpression = (ExpressionTree) ctx.conditionalAndExpression().accept(this);

        return TreeMaker.binaryExpressionTree(
                andExpression,
                Tag.AND,
                inclusiveOrExpression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitInclusiveOrExpression(final NabuParser.InclusiveOrExpressionContext ctx) {
        return ctx.exclusiveOrExpression().accept(this);
    }

    @Override
    public Object visitExclusiveOrExpression(final NabuParser.ExclusiveOrExpressionContext ctx) {
        return ctx.andExpression().accept(this);
    }

    @Override
    public Object visitAndExpression(final NabuParser.AndExpressionContext ctx) {
        return ctx.equalityExpression().accept(this);
    }

    @Override
    public Object visitPrimitiveType(final NabuParser.PrimitiveTypeContext ctx) {
        if (ctx.numericType() != null) {
            return ctx.numericType().accept(this);
        } else {
            return TreeMaker.primitiveTypeTree(
                    PrimitiveTypeTree.Kind.BOOLEAN,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Object visitWildcard(final NabuParser.WildcardContext ctx) {
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());

        final WildcardBound wildcardBound;

        if (ctx.wildcardBounds() != null) {
            wildcardBound = (WildcardBound) ctx.accept(this);
        } else {
            wildcardBound = new WildcardBound(BoundKind.UNBOUND, null);
        }

        ExpressionTree result = TreeMaker.wildcardExpressionTree(
                wildcardBound.kind(),
                wildcardBound.expression(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );

        if (!annotations.isEmpty()) {
            result = TreeMaker.annotatedTypeTree(
                    annotations,
                    result,
                    List.of(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        return result;
    }

    @Override
    public Object visitWildcardBounds(final NabuParser.WildcardBoundsContext ctx) {
        final var boundKind = NabuLexer.EXTENDS == ctx.kind.getType()
                ? BoundKind.EXTENDS
                : BoundKind.SUPER;
        final var type = (ExpressionTree) ctx.referenceType().accept(this);
        return new WildcardBound(boundKind, type);
    }

    @Override
    public Object visitIfThenElseStatement(final NabuParser.IfThenElseStatementContext ctx) {
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var thenStatement = (StatementTree) ctx.statementNoShortIf().accept(this);
        final var elseStatement = (StatementTree) ctx.statement().accept(this);
        return TreeMaker.ifStatementTree(
                expression,
                thenStatement,
                elseStatement,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitBlock(final NabuParser.BlockContext ctx) {
        final List<StatementTree> statements = acceptList(ctx.blockStatements());
        return TreeMaker.blockStatement(
                statements,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitBlockStatements(final NabuParser.BlockStatementsContext ctx) {
        return ctx.blockStatement().stream()
                .flatMap(it -> asStream(it.accept(this)))
                .toList();
    }

    @Override
    public Object visitEmptyStatement_(final NabuParser.EmptyStatement_Context ctx) {
        return TreeMaker.emptyStatementTree(
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitPostIncrementExpression(final NabuParser.PostIncrementExpressionContext ctx) {
        final var expression = (ExpressionTree) ctx.postfixExpression().accept(this);
        return TreeMaker.unaryExpressionTree(
                Tag.POST_INC,
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitBasicForStatement(final NabuParser.BasicForStatementContext ctx) {
        final List<StatementTree> forInit = acceptList(ctx.forInit());
        final var expression = (ExpressionTree) accept(ctx.expression());
        final List<StatementTree> forUpdate = acceptList(ctx.forUpdate());
        final var statement = (StatementTree) ctx.statement().accept(this);

        return TreeMaker.forStatement(
                forInit,
                expression,
                forUpdate,
                statement,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitStatementExpressionList(final NabuParser.StatementExpressionListContext ctx) {
        return ctx.statementExpression().stream()
                .map(it -> (ExpressionTree) it.accept(this))
                .map(expression ->
                        TreeMaker.expressionStatement(
                                expression,
                                expression.getLineNumber(),
                                expression.getColumnNumber()
                        )
                )
                .toList();
    }

    @Override
    public Object visitNormalClassDeclaration(final NabuParser.NormalClassDeclarationContext ctx) {
        var classModifiers = parseModifiers(ctx.classModifier());

        if (!classModifiers.hasAccessModifier()) {
            classModifiers = classModifiers.with(Flags.PUBLIC);
        }

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
    public Object visitClassExtends(final NabuParser.ClassExtendsContext ctx) {
        return ctx.classType().accept(this);
    }

    @Override
    public Object visitClassImplements(final NabuParser.ClassImplementsContext ctx) {
        return ctx.interfaceTypeList().accept(this);
    }

    @Override
    public Object visitInterfaceTypeList(final NabuParser.InterfaceTypeListContext ctx) {
        return ctx.interfaceType().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitTypeParameters(final NabuParser.TypeParametersContext ctx) {
        return ctx.typeParameterList().accept(this);
    }

    @Override
    public Object visitClassType(final NabuParser.ClassTypeContext ctx) {
        final var annotations = ctx.annotation().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final List<IdentifierTree> typeArguments = acceptList(ctx.typeArguments());

        IdentifierTree prefix = null;

        if (ctx.packageName() != null) {
            prefix = (IdentifierTree) ctx.packageName().accept(this);
        } else if (ctx.classOrInterfaceType() != null) {
            prefix = (IdentifierTree) ctx.classOrInterfaceType().accept(this);
        }

        var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);

        if (prefix != null) {
            identifier = TreeMaker.identifier(
                    prefix.getName() + "." +
                            identifier.getName(),
                    prefix.getLineNumber(),
                    prefix.getColumnNumber()
            );
        }

        ExpressionTree result;

        if (annotations.isEmpty()) {
            result = identifier;
        } else {
            result = TreeMaker.annotatedTypeTree(
                    annotations,
                    identifier,
                    List.of(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (!typeArguments.isEmpty()) {
            result = TreeMaker.typeApplyTree(
                    result,
                    typeArguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        return result;
    }

    @Override
    public Object visitTypeParameterList(final NabuParser.TypeParameterListContext ctx) {
        return ctx.typeParameter().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitClassBody(final NabuParser.ClassBodyContext ctx) {
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
    public Object visitConstructorDeclarator(final NabuParser.ConstructorDeclaratorContext ctx) {
        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());

        final VariableDeclaratorTree receiverParameter = accept(ctx.receiverParameter());

        final List<VariableDeclaratorTree> parameters = acceptList(ctx.formalParameterList());

        return TreeMaker.function(
                Constants.INIT,
                Kind.CONSTRUCTOR,
                new CModifiers(),
                typeParameters,
                receiverParameter,
                parameters,
                TreeMaker.primitiveTypeTree(
                        PrimitiveTypeTree.Kind.VOID,
                        -1,
                        -1
                ),
                List.of(),
                null,
                null,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitConstructorBody(final NabuParser.ConstructorBodyContext ctx) {
        final var statements = new ArrayList<StatementTree>();

        if (ctx.explicitConstructorInvocation() != null) {
            final var statement = (StatementTree) ctx.explicitConstructorInvocation().accept(this);
            statements.add(statement);
        }

        if (ctx.blockStatements() != null) {
            statements.addAll(acceptList(ctx.blockStatements()));
        }

        return TreeMaker.blockStatement(
                statements,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitConstructorDeclaration(final NabuParser.ConstructorDeclarationContext ctx) {
        final var modifiers = parseModifiers(ctx.constructorModifier());
        final var constructor = (Function) ctx.constructorDeclarator().accept(this);
        final List<Tree> thrownTypes = acceptList(ctx.throwsT());
        final var body = (BlockStatementTree) ctx.constructorBody().accept(this);

        return constructor.builder()
                .thrownTypes(thrownTypes)
                .modifiers(modifiers)
                .body(body)
                .build();
    }

    @Override
    public Object visitEnhancedForStatement(final NabuParser.EnhancedForStatementContext ctx) {
        final var localVariableDeclaration = (VariableDeclaratorTree) ctx.localVariableDeclaration().accept(this);
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var statement = (StatementTree) ctx.statement().accept(this);

        return TreeMaker.enhancedForStatement(
                localVariableDeclaration,
                expression,
                statement,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitEnhancedForStatementNoShortIf(final NabuParser.EnhancedForStatementNoShortIfContext ctx) {
        final var localVariable = (VariableDeclaratorTree) ctx.localVariableDeclaration().accept(this);
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var statement = (StatementTree) ctx.statementNoShortIf().accept(this);

        return TreeMaker.enhancedForStatement(
                localVariable,
                expression,
                statement,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitExplicitConstructorInvocation(final NabuParser.ExplicitConstructorInvocationContext ctx) {
        final List<IdentifierTree> typeArguments = acceptList(ctx.typeArguments());
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());
        ExpressionTree target = null;

        if (ctx.expressionName() != null) {
            target = (ExpressionTree) ctx.expressionName().accept(this);
        } else if (ctx.primary() != null) {
            target = (ExpressionTree) ctx.primary().accept(this);
        }

        final var methodName = TreeMaker.identifier(
                ctx.name.getText(),
                ctx.name.getLine(),
                ctx.name.getCharPositionInLine()
        );

        return TreeMaker.expressionStatement(
                TreeMaker.methodInvocationTree(
                        target,
                        methodName,
                        typeArguments,
                        arguments,
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                ),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitArgumentList(final NabuParser.ArgumentListContext ctx) {
        return ctx.expression().stream()
                .filter(it -> !(it instanceof TerminalNode))
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitMarkerAnnotation(final NabuParser.MarkerAnnotationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        return TreeMaker.annotationTree(
                typeName,
                List.of(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitUnqualifiedClassInstanceCreationExpression(final NabuParser.UnqualifiedClassInstanceCreationExpressionContext ctx) {
        final List<ExpressionTree> typeArgs = acceptList(ctx.typeArguments());

        final var clazz = (ExpressionTree) ctx.classOrInterfaceTypeToInstantiate().accept(this);
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());
        final ClassDeclaration classDeclaration;

        if (ctx.classBody() != null) {
            final List<Tree> classBody = (List<Tree>) ctx.classBody().accept(this);
            classDeclaration = TreeMaker.classDeclaration(
                    null,
                    new CModifiers(),
                    null,
                    classBody,
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            classDeclaration = null;
        }

        return TreeMaker.newClassExpression(
                clazz,
                typeArgs,
                arguments,
                classDeclaration,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitClassOrInterfaceTypeToInstantiate(final NabuParser.ClassOrInterfaceTypeToInstantiateContext ctx) {
        ExpressionTree expressionTree = null;

        for (final var child : ctx.children) {
            final var expr = child.accept(this);

            if (expressionTree == null) {
                expressionTree = (ExpressionTree) expr;
            } else if (expr instanceof List<?>) {
                final var typeParameters = (List<ExpressionTree>) expr;
                expressionTree = TreeMaker.typeApplyTree(
                        expressionTree,
                        typeParameters,
                        expressionTree.getLineNumber(),
                        expressionTree.getColumnNumber()
                );
            }
        }

        return expressionTree;
    }

    @Override
    public Object visitWhileStatement(final NabuParser.WhileStatementContext ctx) {
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var body = (StatementTree) ctx.statement().accept(this);

        return TreeMaker.whileStatement(
                expression,
                body,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitWhileStatementNoShortIf(final NabuParser.WhileStatementNoShortIfContext ctx) {
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var body = (StatementTree) ctx.statementNoShortIf().accept(this);

        return TreeMaker.whileStatement(
                expression,
                body,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitFunctionInvocation(final NabuParser.FunctionInvocationContext ctx) {
        return visitFunctionInvocationNew(ctx);
    }

    public Object visitFunctionInvocationNew(final NabuParser.FunctionInvocationContext ctx) {
        ExpressionTree expression = null;
        final var arguments = new ArrayList<ExpressionTree>();
        final var typeArguments = new ArrayList<IdentifierTree>();

        for (int c = 0; c < ctx.getChildCount(); c++) {
            final var child = ctx.getChild(c);

            if (child instanceof TerminalNode terminalNode) {
                final var text = terminalNode.getText();

                if ("(".equals(text)
                        || ")".equals(text)
                        || ".".equals(text)) {
                    continue;
                }
            } else if (child instanceof NabuParser.ArgumentListContext) {
                arguments.addAll((List<ExpressionTree>) child.accept(this));
                continue;
            } else if (child instanceof NabuParser.TypeArgumentsContext) {
                typeArguments.addAll((List<IdentifierTree>) child.accept(this));
                continue;
            }

            final var result = (ExpressionTree) child.accept(this);
            expression = combineExpressions(expression, result);
        }

        final var methodInvocationBuilder = new MethodInvocationTreeBuilder()
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .arguments(arguments)
                .typeArguments(typeArguments);

        if (expression instanceof IdentifierTree) {
            methodInvocationBuilder.name(expression);
        } else if (expression instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            methodInvocationBuilder.target(fieldAccessExpressionTree.getTarget());
            methodInvocationBuilder.name(fieldAccessExpressionTree.getField());
        }

        return methodInvocationBuilder.build();
    }

    @Override
    public Object visitPNNA(final NabuParser.PNNAContext ctx) {
        ExpressionTree lastExpression = null;
        final var typeArguments = new ArrayList<IdentifierTree>();
        final var arguments = new ArrayList<ExpressionTree>();
        var isMemberReference = false;

        for (int c = 0; c < ctx.getChildCount(); c++) {
            final var child = ctx.getChild(c);

            switch (child) {
                case TerminalNode terminalNode -> {
                    final var text = terminalNode.getText();

                    if (c == 0 && "::".equals(text)) {
                        isMemberReference = true;
                    }

                    if (")".equals(text)) {
                        lastExpression = new MethodInvocationTreeBuilder()
                                .typeArguments(typeArguments)
                                .name(lastExpression)
                                .arguments(arguments)
                                .build();
                    } else if ("]".equals(text)) {
                        lastExpression = new ArrayAccessExpressionBuilder()
                                .index(lastExpression)
                                .build();
                    }
                }
                case NabuParser.TypeArgumentsContext ignored -> typeArguments.addAll(acceptList(child));
                case NabuParser.ArgumentListContext ignored -> arguments.addAll(acceptList(child));
                case null, default -> {
                    if (child instanceof NabuParser.PNNAContext
                            && isMemberReference) {
                        lastExpression = createMemberReference(
                                typeArguments,
                                (IdentifierTree) lastExpression,
                                ctx
                        );
                        isMemberReference = false;
                    }

                    final var result = child.accept(this);

                    if (result instanceof ExpressionTree currentExpression) {
                        lastExpression = combineExpressions(lastExpression, currentExpression);
                    }
                }
            }
        }

        if (isMemberReference) {
            return createMemberReference(
                    typeArguments,
                    (IdentifierTree) lastExpression,
                    ctx
            );
        }

        return lastExpression;
    }

    private MemberReference createMemberReference(final List<IdentifierTree> typeArguments,
                                                  final IdentifierTree identifier,
                                                  final ParserRuleContext ctx) {
        final IdentifierTree expression;
        final MemberReference.ReferenceKind mode;

        if (Constants.NEW.equals(identifier.getName())) {
            expression = TreeMaker.identifier(
                    Constants.INIT,
                    identifier.getLineNumber(),
                    identifier.getColumnNumber()
            );
            mode = MemberReference.ReferenceKind.NEW;
        } else {
            expression = identifier;
            mode = MemberReference.ReferenceKind.INVOKE;
        }


        return new MemberReferenceBuilder()
                .typeArguments(typeArguments)
                .expression(expression)
                .mode(mode)
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .build();
    }

    private ArrayAccessExpressionTree fillExpression(final ArrayAccessExpressionTree arrayAccessExpressionTree,
                                                     final ExpressionTree expressionTree) {
        if (arrayAccessExpressionTree.getExpression() == null) {
            return arrayAccessExpressionTree.builder()
                    .expression(expressionTree)
                    .build();
        } else if (arrayAccessExpressionTree.getExpression() instanceof ArrayAccessExpressionTree other) {
            final var newArray = fillExpression(other, expressionTree);
            return arrayAccessExpressionTree.builder()
                    .expression(newArray)
                    .build();
        } else {
            final var exp = arrayAccessExpressionTree.getExpression();
            return arrayAccessExpressionTree.builder()
                    .expression(
                            TreeMaker.fieldAccessExpressionTree(
                                    expressionTree,
                                    exp,
                                    expressionTree.getLineNumber(),
                                    expressionTree.getColumnNumber()
                            )
                    )
                    .build();
        }
    }

    @Override
    public Object visitDoStatement(final NabuParser.DoStatementContext ctx) {
        final var body = (StatementTree) ctx.statement().accept(this);
        final var condition = (ExpressionTree) ctx.expression().accept(this);

        return TreeMaker.doWhileStatement(
                body,
                condition,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitNormalAnnotation(final NabuParser.NormalAnnotationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        final List<ExpressionTree> arguments = acceptList(ctx.elementValuePairList());

        return TreeMaker.annotationTree(
                typeName,
                arguments,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitElementValuePairList(final NabuParser.ElementValuePairListContext ctx) {
        return ctx.elementValuePair().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitElementValuePair(final NabuParser.ElementValuePairContext ctx) {
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);
        final var elementValue = (ExpressionTree) ctx.elementValue().accept(this);

        return TreeMaker.assignmentExpression(
                identifier,
                elementValue,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitSingleElementAnnotation(final NabuParser.SingleElementAnnotationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        final var elementValue = (ExpressionTree) ctx.elementValue().accept(this);

        return TreeMaker.annotationTree(
                typeName,
                List.of(elementValue),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitTypeParameter(final NabuParser.TypeParameterContext ctx) {
        final var annotations = ctx.typeParameterModifier().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);

        final List<ExpressionTree> typeBound = acceptList(ctx.typeBound());

        return TreeMaker.typeParameterTree(
                annotations,
                identifier,
                typeBound,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitTypeBound(final NabuParser.TypeBoundContext ctx) {
        if (ctx.typeVariable() != null) {
            final var expression = (ExpressionTree) ctx.typeVariable().accept(this);
            return List.of(expression);
        } else if (ctx.classOrInterfaceType() != null) {
            final var list = new ArrayList<ExpressionTree>();
            final var classOrInterfaceType = (ExpressionTree) ctx.classOrInterfaceType().accept(this);
            list.add(classOrInterfaceType);

            for (final var additionalBoundContext : ctx.additionalBound()) {
                final var additionalBound = (ExpressionTree) additionalBoundContext.accept(this);
                list.add(additionalBound);
            }

            return list;
        } else {
            return List.of();
        }
    }

    @Override
    public Object visitAdditionalBound(final NabuParser.AdditionalBoundContext ctx) {
        return ctx.interfaceType().accept(this);
    }

    @Override
    public Object visitPostDecrementExpression(final NabuParser.PostDecrementExpressionContext ctx) {
        final var expression = (ExpressionTree) ctx.postfixExpression().accept(this);
        return TreeMaker.unaryExpressionTree(
                Tag.POST_DEC,
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitTypeVariable(final NabuParser.TypeVariableContext ctx) {
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());

        final var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);

        return TreeMaker.typeVariableTree(
                annotations,
                identifier,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitArrayType(final NabuParser.ArrayTypeContext ctx) {
        final Tree typeTree;

        if (ctx.primitiveType() != null) {
            typeTree = (Tree) ctx.primitiveType().accept(this);
        } else if (ctx.classType() != null) {
            typeTree = (Tree) ctx.classType().accept(this);
        } else {
            typeTree = (Tree) ctx.typeVariable().accept(this);
        }

        final List<Dimension> dimensions = acceptList(ctx.dims());

        return dimensions.stream()
                .map(dimension -> {
                    final var arrayType = TreeMaker.arrayTypeTree(
                            typeTree,
                            ctx.getStart().getLine(),
                            ctx.getStart().getCharPositionInLine()
                    );

                    if (dimension.annotations().isEmpty()) {
                        return arrayType;
                    } else {
                        return TreeMaker.annotatedTypeTree(
                                dimension.annotations(),
                                arrayType,
                                List.of(),
                                ctx.getStart().getLine(),
                                ctx.getStart().getCharPositionInLine()
                        );
                    }
                })
                .reduce((first, second) -> {
                    if (second instanceof AnnotatedTypeTree annotatedTypeTree) {
                        return annotatedTypeTree.builder()
                                .clazz(TreeMaker.arrayTypeTree(first, first.getLineNumber(), first.getColumnNumber()))
                                .build();
                    } else if (first instanceof AnnotatedTypeTree annotatedTypeTree) {
                        return annotatedTypeTree.builder()
                                .clazz(TreeMaker.arrayTypeTree(second, second.getLineNumber(), second.getColumnNumber()))
                                .build();
                    } else {
                        return TreeMaker.arrayTypeTree(second, second.getLineNumber(), second.getColumnNumber());
                    }
                })
                .orElseGet(() -> TreeMaker.errorTree(
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                ));
    }

    @Override
    public Object visitDims(final NabuParser.DimsContext ctx) {
        final var dimensions = new ArrayList<Dimension>();

        var annotations = new ArrayList<AnnotationTree>();

        for (final var child : ctx.children) {
            if (child instanceof TerminalNode terminalNode) {
                if ("]".equals(terminalNode.getText())) {
                    dimensions.add(new Dimension(annotations));
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
    public Object visitResult(final NabuParser.ResultContext ctx) {
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
    public Object visitArrayInitializer(final NabuParser.ArrayInitializerContext ctx) {
        final List<ExpressionTree> elements = acceptList(ctx.variableInitializerList());
        return TreeMaker.newArrayExpression(
                elements,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitClassLiteral(final NabuParser.ClassLiteralContext ctx) {
        ExpressionTree type = null;

        for (final var child : ctx.children) {
            if (child instanceof TerminalNode terminalNode) {
                if ("]".equals(terminalNode.getText())) {
                    type = TreeMaker.arrayTypeTree(
                            type,
                            type.getLineNumber(),
                            type.getColumnNumber()
                    );
                } else if (Constants.PRIMITIVE_BOOLEAN.equals(terminalNode.getText())) {
                    type = TreeMaker.primitiveTypeTree(
                            PrimitiveTypeTree.Kind.BOOLEAN,
                            terminalNode.getSymbol().getLine(),
                            terminalNode.getSymbol().getCharPositionInLine()
                    );
                } else if (Constants.VOID.equals(terminalNode.getText())) {
                    type = TreeMaker.primitiveTypeTree(
                            PrimitiveTypeTree.Kind.VOID,
                            terminalNode.getSymbol().getLine(),
                            terminalNode.getSymbol().getCharPositionInLine()
                    );
                }
            } else {
                type = (ExpressionTree) child.accept(this);
            }
        }

        return TreeMaker.fieldAccessExpressionTree(
                type,
                TreeMaker.identifier(
                        "class",
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                ),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitFieldDeclaration(final NabuParser.FieldDeclarationContext ctx) {
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
    public Object visitPostfixExpression(final NabuParser.PostfixExpressionContext ctx) {
        ExpressionTree result;

        if (ctx.primary() != null) {
            result = (ExpressionTree) ctx.primary().accept(this);
        } else {
            result = (ExpressionTree) ctx.expressionName().accept(this);
        }

        final ExpressionTree pfe = accept(ctx.pfE());

        if (pfe != null) {
            result = TreeMaker.fieldAccessExpressionTree(
                    result,
                    pfe,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        return result;
    }

    @Override
    public Object visitTryWithResourcesStatement(final NabuParser.TryWithResourcesStatementContext ctx) {
        final var resources = (List<Tree>) ctx.resourceSpecification().accept(this);
        final var block = (BlockStatementTree) ctx.block().accept(this);
        final List<CatchTree> catches = acceptList(ctx.catches());
        final BlockStatementTree finallyBlock = accept(ctx.finallyBlock());

        return new TryStatementTreeBuilder()
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .resources(resources)
                .body(block)
                .catchers(catches)
                .finalizer(finallyBlock)
                .build();
    }

    @Override
    public Object visitResourceSpecification(final NabuParser.ResourceSpecificationContext ctx) {
        return ctx.resourceList().accept(this);
    }

    @Override
    public Object visitResourceList(final NabuParser.ResourceListContext ctx) {
        return ctx.resource().stream()
                .flatMap(it -> asStream(it.accept(this)))
                .map(it -> (VariableDeclaratorTree) it)
                .map(variableDeclarator -> variableDeclarator.builder()
                        .kind(Kind.RESOURCE_VARIABLE)
                        .build()).toList();
    }


    @Override
    public Object visitCatchClause(final NabuParser.CatchClauseContext ctx) {
        final var param = (VariableDeclaratorTree) ctx.catchFormalParameter().accept(this);
        final var block = (BlockStatementTree) ctx.block().accept(this);

        return new CatchTreeBuilder()
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .variable(param)
                .body(block)
                .build();
    }

    @Override
    public Object visitCatchFormalParameter(final NabuParser.CatchFormalParameterContext ctx) {
        final var modifiers = parseModifiers(ctx.variableModifier());
        final var catchType = (ExpressionTree) ctx.catchType().accept(this);
        final var variableDeclarator = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
        return new VariableDeclaratorTreeBuilder()
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .modifiers(modifiers)
                .type(catchType)
                .name(variableDeclarator)
                .build();
    }

    @Override
    public Object visitCatchType(final NabuParser.CatchTypeContext ctx) {
        final var unannClassType = (ExpressionTree) ctx.unannClassType().accept(this);
        final List<ExpressionTree> classTypes = acceptList(ctx.classType());

        if (classTypes.isEmpty()) {
            return unannClassType;
        } else {
            final var alternatives = CollectionUtils.headAndTailList(
                    unannClassType,
                    classTypes
            );

            return new TypeUnionExpressionBuilder()
                    .lineNumber(ctx.getStart().getLine())
                    .columnNumber(ctx.getStart().getCharPositionInLine())
                    .alternatives(alternatives)
                    .build();
        }
    }

    @Override
    public Object visitFinallyBlock(final NabuParser.FinallyBlockContext ctx) {
        return ctx.block().accept(this);
    }

    @Override
    public Object visitNormalInterfaceDeclaration(final NabuParser.NormalInterfaceDeclarationContext ctx) {
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
    public Object visitInterfaceExtends(final NabuParser.InterfaceExtendsContext ctx) {
        return ctx.interfaceTypeList().accept(this);
    }

    @Override
    public Object visitClassPermits(final NabuParser.ClassPermitsContext ctx) {
        return ctx.typeName().stream()
                .map(typeName -> typeName.accept(this))
                .toList();
    }

    @Override
    public Object visitInterfacePermits(final NabuParser.InterfacePermitsContext ctx) {
        return ctx.typeName().stream()
                .map(typeName -> typeName.accept(this))
                .toList();
    }

    @Override
    public Object visitInterfaceBody(final NabuParser.InterfaceBodyContext ctx) {
        return ctx.interfaceMemberDeclaration().stream()
                .map(decl -> decl.accept(this))
                .toList();
    }

    @Override
    public Object visitInterfaceFunctionDeclaration(final NabuParser.InterfaceFunctionDeclarationContext ctx) {
        var modifiers = parseModifiers(ctx.interfaceFunctionModifier());

        if (!modifiers.hasFlag(Flags.DEFAULT) && !modifiers.hasFlag(Flags.ABSTRACT)) {
            modifiers = modifiers.with(Flags.ABSTRACT);
        }

        final var functionHeader = (FunctionHeader) ctx.functionHeader().accept(this);
        final BlockStatementTree body = accept(ctx.functionBody());
        return createFunction(
                modifiers,
                functionHeader,
                body,
                ctx
        );
    }
}


record FunctionDeclarator(VariableDeclaratorTree receiverParameter,
                          String name,
                          List<VariableDeclaratorTree> parameters) {
}

record FunctionHeader(List<TypeParameterTree> typeParameters,
                      List<AnnotationTree> annotations,
                      FunctionDeclarator functionDeclarator,
                      ExpressionTree result, List<Tree> exceptions) {
}

record WildcardBound(BoundKind kind, ExpressionTree expression) {
}

record Dimension(List<AnnotationTree> annotations) {
}
