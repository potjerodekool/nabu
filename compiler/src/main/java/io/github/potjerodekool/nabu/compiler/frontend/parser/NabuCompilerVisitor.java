package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.NabuParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.ArrayAccessExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.IntersectionTypeTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.type.BoundKind;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class NabuCompilerVisitor extends NabuParserBaseVisitor<Object> {

    private final FileObject fileObject;

    public NabuCompilerVisitor(final FileObject fileObject) {
        this.fileObject = fileObject;
    }

    @Override
    public Object visitOrdinaryCompilationUnit(final NabuParser.OrdinaryCompilationUnitContext ctx) {
        var name = fileObject.getFileName();
        final var end = name.lastIndexOf(".");
        name = name.substring(0, end);

        final var startToken = ctx.getStart();

        final var declarations = new ArrayList<Tree>();

        if (ctx.packageDeclaration() != null) {
            declarations.add((Tree) ctx.packageDeclaration().accept(this));
        }

        final var importItems = ctx.importDeclaration().stream()
                .map(importDeclaration -> ((ImportItem) importDeclaration.accept(this)))
                .toList();

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

    @Override
    public Tree visitPackageDeclaration(final NabuParser.PackageDeclarationContext ctx) {
        final var annotations = ctx.packageModifier().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var packageName = ctx.identifier().stream()
                .map(it -> (IdentifierTree) it.accept(this))
                .reduce((left, right) -> TreeMaker.identifier(
                                left.getName() + "." + right.getName(),
                                left.getLineNumber(),
                                left.getColumnNumber()
                        )
                )
                .orElse(null);

        return TreeMaker.packageDeclaration(
                annotations,
                packageName.getName(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Tree visitSingleTypeImportDeclaration(final NabuParser.SingleTypeImportDeclarationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        return TreeMaker.singleImportItem(typeName.getName(), ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine());
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
        final var ident = (Identifier) ctx.identifier().accept(this);
        final var packageOrTypeName = (Identifier) accept(ctx.packageOrTypeName());

        if (packageOrTypeName == null) {
            return TreeMaker.identifier(
                    ident.getName(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return TreeMaker.identifier(
                    packageOrTypeName.getName()
                            + "."
                            + ident.getName(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Object visitFunctionDeclaration(final NabuParser.FunctionDeclarationContext ctx) {
        var modifiers = parseModifiers(ctx.functionModifier());

        final var functionHeader = (FunctionHeader) ctx.functionHeader().accept(this);
        final var receiverParameter = functionHeader.functionDeclarator().receiverParameter();
        final var functionName = functionHeader.functionDeclarator().name();
        final var returnType = functionHeader.result();
        final var exceptions = functionHeader.exceptions();
        final List<VariableDeclarator> params = functionHeader.functionDeclarator().parameters();

        if (!functionHeader.annotations().isEmpty()) {
            final var annotations = new ArrayList<>(modifiers.getAnnotations());
            annotations.addAll(functionHeader.annotations());
            modifiers = new CModifiers(
                    annotations,
                    modifiers.getFlags()
            );
        }

        final BlockStatement body = accept(ctx.functionBody());

        return new FunctionBuilder()
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .kind(Kind.METHOD)
                .modifiers(modifiers)
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
            } else if (result instanceof Integer flag) {
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

        final VariableDeclarator receiverParameter = accept(ctx.receiverParameter());

        final List<VariableDeclarator> parameters = acceptList(ctx.formalParameterList());

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
        final List<VariableDeclarator> parameters = acceptList(ctx.lambdaParameters());
        final var body = ctx.lambdaBody().accept(this);
        final Statement bodyStatement =
                body instanceof ExpressionTree expression
                        ? TreeMaker.expressionStatement(
                        expression,
                        expression.getLineNumber(),
                        expression.getColumnNumber()
                )
                        : (Statement) body;
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
        final ExpressionTree expression;

        final ExpressionTree pnna = accept(ctx.pNNA());

        if (ctx.literal() != null) {
            expression = (ExpressionTree) ctx.literal().accept(this);
        } else if (ctx.classLiteral() != null) {
            expression = (ExpressionTree) ctx.classLiteral().accept(this);
        } else if (ctx.THIS() != null) {
            final var thisExpression = TreeMaker.identifier(
                    Constants.THIS,
                    ctx.THIS().getSymbol().getLine(),
                    ctx.THIS().getSymbol().getCharPositionInLine()
            );

            if (pnna == null) {
                return thisExpression;
            } else {
                if (pnna instanceof MethodInvocationTree methodInvocationTree) {
                    return methodInvocationTree.builder()
                            .target(thisExpression)
                            .build();
                }

                return TreeMaker.fieldAccessExpressionTree(
                        thisExpression,
                        pnna,
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                );
            }
        } else if (ctx.functionName() != null) {
            final var functionName = (IdentifierTree) ctx.functionName().accept(this);
            final List<ExpressionTree> arguments = acceptList(ctx.argumentList());

            if (pnna != null) {
                throw new TodoException();
            } else {
                return TreeMaker.methodInvocationTree(
                        null,
                        functionName,
                        List.of(),
                        arguments,
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                );
            }

        } else {
            final var typeName = (ExpressionTree) ctx.typeName().accept(this);
            final var identifier = (ExpressionTree) ctx.identifier().accept(this);
            final List<ExpressionTree> arguments = acceptList(ctx.argumentList());

            return TreeMaker.methodInvocationTree(
                    typeName,
                    identifier,
                    List.of(),
                    arguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (pnna != null) {
            if (pnna instanceof MethodInvocationTree methodInvocationTree) {
                return methodInvocationTree.builder()
                        .target(expression)
                        .build();
            } else {
                throw new TodoException();
            }
        } else {
            return expression;
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

    @Override
    public Object visitFieldAccess(final NabuParser.FieldAccessContext ctx) {
        final ExpressionTree target;
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);

        if (ctx.primary() != null) {
            target = (ExpressionTree) ctx.primary().accept(this);

            return TreeMaker.fieldAccessExpressionTree(
                    target,
                    identifier,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            throw new TodoException();
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
            case NabuLexer.Identifier,
                 NabuLexer.DOT -> identifier(node.getSymbol());
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
            default -> throw new UnsupportedOperationException("" + type);
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
        final TypeApplyTree packageName = accept(ctx.packageName());
        final var type = (ExpressionTree) ctx.typeIdentifier().accept(this);
        final List<ExpressionTree> typeArguments = acceptList(ctx.typeArguments());

        if (packageName == null) {
            if (typeArguments != null) {
                final var identifier = (IdentifierTree) type;

                return TreeMaker.typeApplyTree(
                        identifier,
                        typeArguments,
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                );
            } else {
                return type;
            }
        } else {
            final var ident = (IdentifierTree) type;

            if (typeArguments == null) {

                return TreeMaker.identifier(
                        packageName.getName()
                                + "."
                                + ident.getName(),
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                );
            }

            return TreeMaker.typeApplyTree(
                    TreeMaker.identifier(
                            packageName.getName()
                                    + "."
                                    + ident.getName(),
                            packageName.getLineNumber(),
                            packageName.getColumnNumber()
                    ),
                    typeArguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
    }

    @Override
    public Object visitClassOrInterfaceType(final NabuParser.ClassOrInterfaceTypeContext ctx) {
        if (ctx.packageName() != null) {
            throw new TodoException();
        }

        final var annotations = ctx.annotation().stream()
                .map(it -> it.accept(this))
                .toList();

        if (!annotations.isEmpty()) {
            throw new TodoException();
        }

        final var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);

        final List<ExpressionTree> typeArguments = acceptList(ctx.typeArguments());

        if (ctx.coit() != null) {
            throw new TodoException();
        }

        if (typeArguments.isEmpty()) {
            return identifier;
        } else {
            return TreeMaker.typeApplyTree(
                    identifier,
                    typeArguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
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
    public Tree visitLocalVariableDeclaration(final NabuParser.LocalVariableDeclarationContext ctx) {
        final var modifiers = parseModifiers(ctx.variableModifier());

        final ExpressionTree type = (ExpressionTree) ctx.localVariableType().accept(this);

        final List<VariableDeclarator> variableDeclarators = acceptList(ctx.variableDeclaratorList());

        if (variableDeclarators.size() == 1) {
            return variableDeclarators.getFirst()
                    .builder()
                    .kind(Kind.LOCAL_VARIABLE)
                    .modifiers(modifiers)
                    .type(type)
                    .lineNumber(ctx.getStart().getLine())
                    .columnNumber(ctx.getStart().getCharPositionInLine())
                    .build();

        }

        throw new TodoException();
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

    private <E> List<E> acceptList(final ParserRuleContext context) {
        if (context == null) {
            return List.of();
        } else {
            return (List<E>) context.accept(this);
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
        final var annotations = ctx.annotation().stream()
                .map(a -> a.accept(this))
                .toList();

        if (!annotations.isEmpty()) {
            throw new TodoException();
        }

        final WildcardBound wildcardBound;

        if (ctx.wildcardBounds() != null) {
            wildcardBound = (WildcardBound) ctx.accept(this);
        } else {
            wildcardBound = new WildcardBound(BoundKind.UNBOUND, null);
        }

        return TreeMaker.wildcardExpressionTree(
                wildcardBound.kind(),
                wildcardBound.expression(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
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
        final var thenStatement = (Statement) ctx.statementNoShortIf().accept(this);
        final var elseStatement = (Statement) ctx.statement().accept(this);
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
        final List<Statement> statements = acceptList(ctx.blockStatements());
        return TreeMaker.blockStatement(
                statements,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitBlockStatements(final NabuParser.BlockStatementsContext ctx) {
        return ctx.blockStatement().stream()
                .map(bs -> {
                    final var statement = (Statement) bs.accept(this);
                    Objects.requireNonNull(statement);
                    return statement;
                })
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
        final var forInit = (Statement) accept(ctx.forInit());
        final var expression = (ExpressionTree) accept(ctx.expression());
        final var forUpdate = (ExpressionTree) accept(ctx.forUpdate());
        final var statement = (Statement) ctx.statement().accept(this);

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
    public Object visitNormalClassDeclaration(final NabuParser.NormalClassDeclarationContext ctx) {
        var classModifiers = parseModifiers(ctx.classModifier());

        if (!classModifiers.hasAccessModifier()) {
            classModifiers = classModifiers.with(Flags.PUBLIC);
        }

        final var simpleName = ((IdentifierTree) ctx.typeIdentifier().accept(this)).getName();

        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());
        final ExpressionTree extendion = accept(ctx.classExtends());
        final List<ExpressionTree> implementions = acceptList(ctx.classImplements());

        if (ctx.classPermits() != null) {
            ctx.classPermits().accept(this);
            throw new TodoException();
        }

        final List<Tree> enclosedElements = accept(ctx.classBody());

        return TreeMaker.classDeclaration(
                Kind.CLASS,
                classModifiers,
                simpleName,
                enclosedElements,
                typeParameters,
                implementions,
                extendion,
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

        if (ctx.typeArguments() != null) {
            throw new TodoException();
        }

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

        if (annotations.isEmpty()) {
            return identifier;
        } else {
            return TreeMaker.annotatedTypeTree(
                    annotations,
                    identifier,
                    List.of(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }
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
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitConstructorDeclarator(final NabuParser.ConstructorDeclaratorContext ctx) {
        if (ctx.typeParameters() != null) {
            throw new TodoException();
        }

        if (ctx.receiverParameter() != null) {
            throw new TodoException();
        }

        if (ctx.formalParameterList() != null) {
            throw new TodoException();
        }

        return TreeMaker.function(
                Constants.INIT,
                Kind.CONSTRUCTOR,
                new CModifiers(),
                List.of(),
                null,
                List.of(),
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
        final var statements = new ArrayList<Statement>();

        if (ctx.explicitConstructorInvocation() != null) {
            final var statement = (Statement) ctx.explicitConstructorInvocation().accept(this);
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

        if (ctx.throwsT() != null) {
            throw new TodoException();
        }

        final var body = (BlockStatement) ctx.constructorBody().accept(this);

        return constructor.builder()
                .modifiers(modifiers)
                .body(body)
                .build();
    }

    @Override
    public Object visitForStatement(final NabuParser.ForStatementContext ctx) {
        return super.visitForStatement(ctx);
    }

    @Override
    public Object visitEnhancedForStatement(final NabuParser.EnhancedForStatementContext ctx) {
        final var localVariableDeclaration = (VariableDeclarator) ctx.localVariableDeclaration().accept(this);
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var statement = (Statement) ctx.statement().accept(this);

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
        throw new TodoException();
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
        final var typeArgs = acceptList(ctx.typeArguments());

        if (!typeArgs.isEmpty()) {
            throw new TodoException();
        }

        final var clazz = (ExpressionTree) ctx.classOrInterfaceTypeToInstantiate().accept(this);
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());
        final List<Statement> classBody = accept(ctx.classBody());

        return TreeMaker.newClassExpression(
                clazz,
                arguments,
                TreeMaker.blockStatement(
                        classBody,
                        -1,
                        -1
                ),
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
        final var body = (Statement) ctx.statement().accept(this);

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
        final var body = (Statement) ctx.statementNoShortIf().accept(this);

        return TreeMaker.whileStatement(
                expression,
                body,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitFunctionInvocation(final NabuParser.FunctionInvocationContext ctx) {
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());
        final List<IdentifierTree> typeArguments = acceptList(ctx.typeArguments());
        final ExpressionTree target;
        final IdentifierTree identifier;

        if (ctx.functionName() != null) {
            final var functionName = (IdentifierTree) ctx.functionName().accept(this);
            return TreeMaker.methodInvocationTree(
                    null,
                    functionName,
                    List.of(),
                    arguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            identifier = (IdentifierTree) ctx.identifier().accept(this);

            if (ctx.typeName() != null) {
                final var typeName = (IdentifierTree) ctx.typeName().accept(this);

                if (ctx.super_ != null) {
                    target = TreeMaker.fieldAccessExpressionTree(typeName, TreeMaker.identifier(
                                    Constants.SUPER,
                                    ctx.super_.getLine(),
                                    ctx.super_.getCharPositionInLine()
                            ),
                            ctx.getStart().getLine(),
                            ctx.getStart().getCharPositionInLine()
                    );
                } else {
                    target = typeName;
                }
            } else if (ctx.expressionName() != null) {
                target = (ExpressionTree) ctx.expressionName().accept(this);
            } else if (ctx.primary() != null) {
                target = (ExpressionTree) ctx.primary().accept(this);
            } else if (ctx.super_ != null) {
                target = TreeMaker.identifier(
                        Constants.SUPER,
                        ctx.super_.getLine(),
                        ctx.super_.getCharPositionInLine()
                );
            } else {
                throw new TodoException();
            }
        }

        return TreeMaker.methodInvocationTree(
                target,
                identifier,
                typeArguments,
                arguments,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitPNNA(final NabuParser.PNNAContext ctx) {
        return visitPNNANew(ctx);
    }

    private Object visitPNNANew(final NabuParser.PNNAContext ctx) {
        if (ctx.unqualifiedClassInstanceCreationExpression() != null) {
            final var expression = (ExpressionTree) ctx.unqualifiedClassInstanceCreationExpression().accept(this);

            if (ctx.pNNA() != null) {
                throw new TodoException();
            }

            return expression;
        }

        final var firstToken = ctx.getChild(0).getText();
        final var typeArguments = new ArrayList<IdentifierTree>();
        final var arguments = new ArrayList<ExpressionTree>();
        ExpressionTree identifier = null;
        ExpressionTree indexExpression = null;
        var isMethodCall = false;
        ExpressionTree pnna = null;

        for (int c = 1; c < ctx.getChildCount(); c++) {
            final var child = ctx.getChild(c);

            if (child instanceof TerminalNode) {
                if ("(".equals(child.getText())) {
                    isMethodCall = true;
                }
            } else if (child instanceof NabuParser.TypeArgumentsContext) {
                typeArguments.addAll((List) child.accept(this));
            } else if (child instanceof NabuParser.IdentifierContext) {
                identifier = (ExpressionTree) child.accept(this);
            } else if (child instanceof NabuParser.ExpressionContext) {
                indexExpression = (ExpressionTree) child.accept(this);
            } else if (child instanceof NabuParser.ArgumentListContext) {
                arguments.addAll((List)child.accept(this));
            } else if (child instanceof NabuParser.PNNAContext) {
                pnna = (ExpressionTree) child.accept(this);
            }
        }

        if (isMethodCall) {
            return TreeMaker.methodInvocationTree(
                    null,
                    identifier,
                    typeArguments,
                    arguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            if (".".equals(firstToken)) {
                final var fieldAccess = TreeMaker.fieldAccessExpressionTree(
                        null,
                        identifier,
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                );

                if (pnna == null) {
                    return fieldAccess;
                }

                var pnnaFieldAccess = (FieldAccessExpressionTree) pnna;
                return pnnaFieldAccess.builder()
                        .target(fieldAccess)
                        .build();
            } else if ("[".equals(firstToken)) {
                final var arrayAccess = new ArrayAccessExpressionBuilder()
                        .index(indexExpression)
                        .build();

                if (pnna == null) {
                    return arrayAccess;
                } else if (pnna instanceof ArrayAccessExpression arrayAccessExpression) {
                    return fillExpression(
                            arrayAccessExpression,
                            arrayAccess
                    );
                } else {
                    throw new TodoException();
                }
            }

            throw new TodoException();
        }
    }

    private Object visitPNNAClassic(final NabuParser.PNNAContext ctx) {
        final List<IdentifierTree> typeArguments = acceptList(ctx.typeArguments());
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());

        if (ctx.unqualifiedClassInstanceCreationExpression() != null) {
            return ctx.unqualifiedClassInstanceCreationExpression().accept(this);
        } else if (ctx.identifier() != null) {
            final var identifier = (ExpressionTree) ctx.identifier().accept(this);
            final ExpressionTree pnna = accept(ctx.pNNA());

            if (ctx.argumentList() != null) {
                return TreeMaker.methodInvocationTree(
                        null,
                        identifier,
                        typeArguments,
                        arguments,
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                );
            } else if (pnna != null) {
                return combinePNNA(pnna, identifier, ctx.getStart());
            } else {
                return identifier;
            }
        } else if (ctx.expression() != null) {
            final var index = (ExpressionTree) ctx.expression().accept(this);
            final var arrayAccess = new ArrayAccessExpressionBuilder()
                    .index(index)
                    .build();

            if (ctx.pNNA() == null) {
                return arrayAccess;
            }

            final var pnna = (ArrayAccessExpression) ctx.pNNA().accept(this);

            return fillExpression(
                    pnna,
                    arrayAccess
            );
        }

        throw new TodoException();
    }

    private ExpressionTree combinePNNA(final ExpressionTree pnna,
                                       final ExpressionTree expressionTree, final Token start) {
        final var line = start.getLine();
        final var charPositionInLine = start.getCharPositionInLine();

        if (pnna instanceof ArrayAccessExpression arrayAccessExpression) {
            return fillExpression(arrayAccessExpression, expressionTree);
        } else {
            return TreeMaker.fieldAccessExpressionTree(
                    expressionTree,
                    pnna,
                    line,
                    charPositionInLine
            );
        }
    }

    private ArrayAccessExpression fillExpression(final ArrayAccessExpression arrayAccessExpression,
                                                 final ExpressionTree expressionTree) {
        if (arrayAccessExpression.getExpression() == null) {
            return arrayAccessExpression.builder()
                    .expression(expressionTree)
                    .build();
        } else if (arrayAccessExpression.getExpression() instanceof ArrayAccessExpression other) {
            final var newArray = fillExpression(other, expressionTree);
            return arrayAccessExpression.builder()
                    .expression(newArray)
                    .build();
        } else {
            return arrayAccessExpression;
        }
    }

    @Override
    public Object visitDoStatement(final NabuParser.DoStatementContext ctx) {
        final var body = (Statement) ctx.statement().accept(this);
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
        final List<VariableDeclarator> variableDeclarators = acceptList(ctx.variableDeclaratorList());

        if (variableDeclarators.size() == 1) {
            return variableDeclarators.getFirst()
                    .builder()
                    .kind(Kind.FIELD)
                    .modifiers(fieldModifiers)
                    .type(type)
                    .build();
        }

        throw new TodoException();
    }

    @Override
    public Object visitPostfixExpression(final NabuParser.PostfixExpressionContext ctx) {
        final ExpressionTree expressionTree;

        if (ctx.primary() != null) {
            expressionTree = (ExpressionTree) ctx.primary().accept(this);
        } else {
            expressionTree = (ExpressionTree) ctx.expressionName().accept(this);
        }

        final var pfe = accept(ctx.pfE());

        if (pfe != null) {
            throw new TodoException();
        }

        return expressionTree;
    }
}


record FunctionDeclarator(VariableDeclarator receiverParameter,
                          String name,
                          List<VariableDeclarator> parameters) {
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