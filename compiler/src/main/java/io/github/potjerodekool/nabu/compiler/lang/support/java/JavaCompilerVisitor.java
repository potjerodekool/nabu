package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.VariableArityParameter;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.frontend.parser.MethodDeclarator;
import io.github.potjerodekool.nabu.compiler.frontend.parser.MethodHeader;
import io.github.potjerodekool.nabu.compiler.frontend.parser.WildcardBound;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.builder.ArrayAccessExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.builder.IntersectionTypeTreeBuilder;
import io.github.potjerodekool.nabu.tree.expression.builder.MethodInvocationTreeBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CArrayTypeTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CDimension;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CTypePattern;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CIfStatementTree;
import io.github.potjerodekool.nabu.type.BoundKind;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.github.potjerodekool.nabu.compiler.frontend.parser.SourceVisitor.createFunction;
import static io.github.potjerodekool.nabu.compiler.frontend.parser.SourceVisitor.processImportExpression;
import static io.github.potjerodekool.nabu.compiler.lang.support.shared.CompilerVisitorHelper.*;

public class JavaCompilerVisitor extends Java20ParserBaseVisitor<Object> {

    private final FileObject fileObject;
    private final boolean skipBody;
    private boolean isTopLevel = true;

    public JavaCompilerVisitor(final FileObject fileObject) {
        this(fileObject, true);
    }

    public JavaCompilerVisitor(final FileObject fileObject,
                               final boolean skipBody) {
        this.fileObject = fileObject;
        this.skipBody = skipBody;
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
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);
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
            return createFieldAccessExpression(
                    identifier,
                    packageName
            );
        }
    }

    private ExpressionTree createFieldAccessExpression(final ExpressionTree first,
                                                       final ExpressionTree second) {
        CFieldAccessExpressionTree result;

        if (second instanceof IdentifierTree selectorIdentifier) {
            result = new CFieldAccessExpressionTree(
                    first,
                    selectorIdentifier
            );
        } else {
            final var expressions = new ArrayList<ExpressionTree>();
            expressions.add(first);
            collectExpressions(second, expressions);

            ExpressionTree newExpression = null;

            for (final var expression : expressions) {
                if (newExpression != null) {
                    newExpression = new CFieldAccessExpressionTree(
                            newExpression,
                            (IdentifierTree) expression
                    );
                } else {
                    newExpression = expression;
                }
            }
            result = (CFieldAccessExpressionTree) newExpression;
        }

        return result.builder()
                .lineNumber(first.getLineNumber())
                .columnNumber(first.getColumnNumber())
                .build();
    }

    private void collectExpressions(final ExpressionTree expressionTree,
                                    final List<ExpressionTree> expressions) {
        if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            collectExpressions(fieldAccessExpressionTree.getSelected(), expressions);
            expressions.add(fieldAccessExpressionTree.getField());
        } else {
            expressions.add(expressionTree);
        }
    }

    @Override
    public Object visitNormalInterfaceDeclaration(final Java20Parser.NormalInterfaceDeclarationContext ctx) {
        final NestingKind nestingKind;

        if (isTopLevel) {
            nestingKind = NestingKind.TOP_LEVEL;
            isTopLevel = false;
        } else {
            nestingKind = NestingKind.MEMBER;
        }

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
                .nestingKind(nestingKind)
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

        return createFunction(
                modifiers,
                functionHeader,
                null,
                ctx
        );
    }

    @Override
    public Object visitNormalClassDeclaration(final Java20Parser.NormalClassDeclarationContext ctx) {
        final NestingKind nestingKind;

        if (isTopLevel) {
            nestingKind = NestingKind.TOP_LEVEL;
            isTopLevel = false;
        } else {
            nestingKind = NestingKind.MEMBER;
        }

        final var classModifiers = parseModifiers(ctx.classModifier());
        final var simpleName = ((IdentifierTree) ctx.typeIdentifier().accept(this)).getName();
        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());
        final ExpressionTree extending = accept(ctx.classExtends());
        final List<ExpressionTree> implementations = acceptList(ctx.classImplements());
        final List<IdentifierTree> permits = acceptList(ctx.classPermits());
        final List<Tree> enclosedElements = accept(ctx.classBody());

        return TreeMaker.classDeclaration(
                Kind.CLASS,
                nestingKind,
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
                        .variableType(type)
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
                new Modifiers(),
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
            return ctx.Identifier().accept(this);
        } else {
            return identifier(ctx.contextualKeyword().getStart());
        }
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
        final var identifier = (Tree) ctx.typeIdentifier().accept(this);
        Tree expressionTree;

        if (packageName == null) {
            expressionTree = identifier;
        } else {
            if (!annotations.isEmpty()) {
                expressionTree = TreeMaker.annotatedTypeTree(
                        annotations,
                        (ExpressionTree) identifier,
                        List.of(),
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                );
            } else {
                expressionTree = identifier;
            }

            expressionTree = TreeMaker.fieldAccessExpressionTree(
                    packageName,
                    (IdentifierTree) expressionTree,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (ctx.typeArguments() != null) {
            final List<ExpressionTree> typeArguments = acceptList(ctx.typeArguments());
            expressionTree = TreeMaker.typeApplyTree(
                    (ExpressionTree) expressionTree,
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
        final String text;

        if (ctx.Identifier() != null) {
            text = ctx.Identifier().getText();
        } else {
            text = ctx.contextualKeywordMinusForTypeIdentifier().getText();
        }

        return TreeMaker.identifier(
                text,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitMethodDeclaration(final Java20Parser.MethodDeclarationContext ctx) {
        final var modifiers = parseModifiers(ctx.methodModifier());
        final var functionHeader = (MethodHeader) ctx.methodHeader().accept(this);
        final BlockStatementTree body;

        if (skipBody) {
            body = new CBlockStatementTree(List.of());
        } else {
            body = (BlockStatementTree) ctx.methodBody().accept(this);
        }

        return createFunction(
                modifiers,
                functionHeader,
                body,
                ctx
        );
    }

    @Override
    public Object visitMethodHeader(final Java20Parser.MethodHeaderContext ctx) {
        List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());

        final var annotations = ctx.annotation().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var result = (Tree) ctx.result().accept(this);
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
    public Object visitMethodBody(final Java20Parser.MethodBodyContext ctx) {
        return accept(ctx.block());
    }

    @Override
    public Object visitBlock(final Java20Parser.BlockContext ctx) {
        final List<StatementTree> statements = acceptList(ctx.blockStatements());
        return TreeMaker.blockStatement(
                statements,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitBlockStatements(final Java20Parser.BlockStatementsContext ctx) {
        return ctx.blockStatement().stream()
                .flatMap(it -> asStream(it.accept(this)))
                .toList();
    }

    @Override
    public Object visitMethodInvocation(final Java20Parser.MethodInvocationContext ctx) {
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
            } else if (child instanceof Java20Parser.ArgumentListContext) {
                arguments.addAll((List<ExpressionTree>) child.accept(this));
                continue;
            } else if (child instanceof Java20Parser.TypeArgumentsContext) {
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

        methodInvocationBuilder.methodSelector(expression);

        return methodInvocationBuilder.build();
    }


    @Override
    public Object visitArgumentList(final Java20Parser.ArgumentListContext ctx) {
        return ctx.expression().stream()
                .filter(it -> !(it instanceof TerminalNode))
                .map(it -> it.accept(this))
                .toList();
    }

    public ExpressionTree combineExpressions(final ExpressionTree first,
                                             final Object second) {
        final ExpressionTree secondExpression;

        if (second instanceof ExpressionTree expression) {
            secondExpression = expression;
        } else if (second instanceof List<?> list && list.size() == 1) {
            secondExpression = (ExpressionTree) list.getFirst();
        } else if (second == null) {
            secondExpression = null;
        } else {
            throw new IllegalArgumentException();
        }

        if (first == null) {
            return secondExpression;
        } else if (secondExpression == null) {
            return first;
        } else if (secondExpression instanceof ArrayAccessExpressionTree arrayAccessExpressionTree) {
            return fillExpression(arrayAccessExpressionTree, first);
        } else if (secondExpression instanceof MethodInvocationTree methodInvocationTree) {
            final var selector = methodInvocationTree.getMethodSelector();
            final ExpressionTree newSelector;

            if (selector instanceof IdentifierTree identifierTree) {
                newSelector = new CFieldAccessExpressionTree(
                        first,
                        identifierTree
                );
            } else {
                final var fieldAccess = (CFieldAccessExpressionTree) selector;
                final var selected = fieldAccess.getSelected();

                if (selected instanceof IdentifierTree identifierTree) {
                    newSelector = new CFieldAccessExpressionTree(
                            new CFieldAccessExpressionTree(
                                    first,
                                    identifierTree
                            ),
                            fieldAccess.getField()
                    );
                } else if (selected instanceof MethodInvocationTree subInvocation) {
                    var expr = combineExpressions(first, subInvocation);
                    final var field = fieldAccess.getField();
                    newSelector = new CFieldAccessExpressionTree(
                            expr,
                            field
                    );
                } else {
                    throw new TodoException();
                }
            }

            return methodInvocationTree.builder()
                    .methodSelector(newSelector)
                    .build();
        } else {
            return new FieldAccessExpressionBuilder()
                    .selected(first)
                    .field((IdentifierTree) secondExpression)
                    .build();
        }
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
                                    (IdentifierTree) exp,
                                    expressionTree.getLineNumber(),
                                    expressionTree.getColumnNumber()
                            )
                    )
                    .build();
        }
    }

    @Override
    public Object visitExpressionStatement(final Java20Parser.ExpressionStatementContext ctx) {
        final var expression = (ExpressionTree) ctx.statementExpression().accept(this);

        return TreeMaker.expressionStatement(
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
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
                new Modifiers(
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
        final Modifiers modifiers;
        final IdentifierTree name;
        final ExpressionTree type;

        if (ctx.variableArityParameter() != null) {
            final var parameter = (VariableArityParameter) ctx.variableArityParameter().accept(this);
            modifiers = parameter.modifiers();
            name = parameter.name();
            type = parameter.type();
        } else {
            modifiers = parseModifiers(ctx.variableModifier());
            name = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
            type = (ExpressionTree) ctx.unannType().accept(this);
        }

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
    public Object visitVariableArityParameter(final Java20Parser.VariableArityParameterContext ctx) {
        final var modifiers = parseModifiers(ctx.variableModifier()).with(Flags.VARARGS);
        final var componentType = (ExpressionTree) ctx.unannType().accept(this);
        final var identifierName = (IdentifierTree) ctx.identifier().accept(this);
        final var type = TreeMaker.arrayTypeTree(
                componentType,
                List.of(),
                componentType.getLineNumber(),
                componentType.getColumnNumber()
        );
        return new VariableArityParameter(
                modifiers,
                type,
                identifierName
        );
    }

    private Modifiers parseModifiers(final List<? extends ParserRuleContext> modifierList) {
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

        return new Modifiers(annotations, flags);
    }

    @Override
    public Object visitNormalAnnotation(final Java20Parser.NormalAnnotationContext ctx) {
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
    public Object visitElementValuePairList(final Java20Parser.ElementValuePairListContext ctx) {
        return ctx.elementValuePair().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitElementValuePair(final Java20Parser.ElementValuePairContext ctx) {
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
    public Object visitMarkerAnnotation(final Java20Parser.MarkerAnnotationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        return TreeMaker.annotationTree(
                typeName,
                List.of(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitSingleElementAnnotation(final Java20Parser.SingleElementAnnotationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        final var elementValue = (ExpressionTree) ctx.elementValue().accept(this);

        final var argument = TreeMaker.assignmentExpression(
                IdentifierTree.create("value"),
                elementValue,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );

        return TreeMaker.annotationTree(
                typeName,
                List.of(argument),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitClassLiteral(final Java20Parser.ClassLiteralContext ctx) {
        ExpressionTree type = null;

        for (final var child : ctx.children) {
            if (child instanceof TerminalNode terminalNode) {
                if ("]".equals(terminalNode.getText())) {
                    type = TreeMaker.arrayTypeTree(
                            type,
                            List.of(),
                            terminalNode.getSymbol().getLine(),
                            terminalNode.getSymbol().getCharPositionInLine()
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

        return TreeMaker.classLiteralTree(
                type,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
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
            case Java20Lexer.Identifier -> identifier(node.getSymbol());
            default -> null;
        };
    }

    private IdentifierTree identifier(final Token token) {
        return TreeMaker.identifier(
                token.getText(),
                token.getLine(),
                token.getCharPositionInLine() + 1);
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

    @Override
    public Object visitTypeParameters(final Java20Parser.TypeParametersContext ctx) {
        return ctx.typeParameterList().accept(this);
    }

    @Override
    public Object visitTypeParameter(final Java20Parser.TypeParameterContext ctx) {
        final var annotations = ctx.typeParameterModifier().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var typeIdentifier = (IdentifierTree) ctx.typeIdentifier().accept(this);
        final List<ExpressionTree> typeBound = acceptList(ctx.typeBound());

        return TreeMaker.typeParameterTree(
                annotations,
                typeIdentifier,
                typeBound,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitClassExtends(final Java20Parser.ClassExtendsContext ctx) {
        return ctx.classType().accept(this);
    }

    @Override
    public Object visitClassType(final Java20Parser.ClassTypeContext ctx) {
        final var annotations = ctx.annotation().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final List<IdentifierTree> typeArguments = acceptList(ctx.typeArguments());

        ExpressionTree prefix = null;

        if (ctx.packageName() != null) {
            prefix = (ExpressionTree) ctx.packageName().accept(this);
        } else if (ctx.classOrInterfaceType() != null) {
            prefix = (ExpressionTree) ctx.classOrInterfaceType().accept(this);
        }

        var identifier = (ExpressionTree) ctx.typeIdentifier().accept(this);

        if (prefix != null) {
            identifier = TreeMaker.fieldAccessExpressionTree(
                    prefix,
                    (IdentifierTree) identifier,
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
    public Object visitWildcard(final Java20Parser.WildcardContext ctx) {
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());

        final WildcardBound wildcardBound;

        if (ctx.wildcardBounds() != null) {
            wildcardBound = (WildcardBound) ctx.wildcardBounds().accept(this);
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
    public Object visitWildcardBounds(final Java20Parser.WildcardBoundsContext ctx) {
        final var boundKind = Java20Lexer.EXTENDS == ctx.kind.getType()
                ? BoundKind.EXTENDS
                : BoundKind.SUPER;
        final var type = (ExpressionTree) ctx.referenceType().accept(this);
        return new WildcardBound(boundKind, type);
    }

    @Override
    public Object visitTypeParameterList(final Java20Parser.TypeParameterListContext ctx) {
        return ctx.typeParameter().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitTypeParameterModifier(final Java20Parser.TypeParameterModifierContext ctx) {
        return super.visitTypeParameterModifier(ctx);
    }

    @Override
    public Object visitTypeBound(final Java20Parser.TypeBoundContext ctx) {
        if (ctx.typeVariable() != null) {
            final var expression = (Tree) ctx.typeVariable().accept(this);
            return List.of(expression);
        } else if (ctx.classOrInterfaceType() != null) {
            final var list = new ArrayList<Tree>();
            final var classOrInterfaceType = (Tree) ctx.classOrInterfaceType().accept(this);
            list.add(classOrInterfaceType);

            for (final var additionalBoundContext : ctx.additionalBound()) {
                final var additionalBound = (Tree) additionalBoundContext.accept(this);
                list.add(additionalBound);
            }

            return list;
        } else {
            return List.of();
        }
    }

    @Override
    public Object visitArrayType(final Java20Parser.ArrayTypeContext ctx) {
        Tree componentType;

        if (ctx.primitiveType() != null) {
            componentType = (Tree) ctx.primitiveType().accept(this);
        } else if (ctx.classType() != null) {
            componentType = (Tree) ctx.classType().accept(this);
        } else {
            componentType = (Tree) ctx.typeVariable().accept(this);
        }

        final var dimensions = (List<Dimension>) ctx.dims().accept(this);

        return dimensions.stream()
                .map(dimension -> {
                    final var arrayType = TreeMaker.arrayTypeTree(
                            componentType,
                            List.of(),
                            ctx.getStart().getLine(),
                            ctx.getStart().getCharPositionInLine()
                    );

                    if (dimension.getAnnotations().isEmpty()) {
                        return arrayType;
                    } else {
                        return TreeMaker.annotatedTypeTree(
                                dimension.getAnnotations(),
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
                                .clazz(TreeMaker.arrayTypeTree(first, List.of(), first.getLineNumber(), first.getColumnNumber()))
                                .build();
                    } else if (first instanceof AnnotatedTypeTree annotatedTypeTree) {
                        return annotatedTypeTree.builder()
                                .clazz(TreeMaker.arrayTypeTree(second, List.of(), second.getLineNumber(), second.getColumnNumber()))
                                .build();
                    } else {
                        return TreeMaker.arrayTypeTree(second, List.of(), second.getLineNumber(), second.getColumnNumber());
                    }
                })
                .orElseGet(() -> TreeMaker.errorTree(
                        ctx.getStart().getLine(),
                        ctx.getStart().getCharPositionInLine()
                ));
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
    public Object visitUnannArrayType(final Java20Parser.UnannArrayTypeContext ctx) {
        Tree componentType;

        if (ctx.unannPrimitiveType() != null) {
            componentType = (Tree) ctx.unannPrimitiveType().accept(this);
        } else if (ctx.unannClassOrInterfaceType() != null) {
            componentType = (Tree) ctx.unannClassOrInterfaceType().accept(this);
        } else {
            componentType = (Tree) ctx.unannTypeVariable().accept(this);
        }

        final var dims = (List<Dimension>) ctx.dims().accept(this);

        return new CArrayTypeTree(
                componentType,
                dims,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitEnumDeclaration(final Java20Parser.EnumDeclarationContext ctx) {
        final var modifiers = parseModifiers(ctx.classModifier());
        final var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);
        final List<ExpressionTree> classImplements = acceptList(ctx.classImplements());

        final var enclosedElements = postProcessEnumBody(
                flatList(ctx.enumBody().accept(this)),
                identifier
        );

        return TreeMaker.classDeclaration(
                Kind.ENUM,
                NestingKind.TOP_LEVEL,
                modifiers,
                identifier.getName(),
                enclosedElements,
                List.of(),
                classImplements,
                null,

                List.of(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    private List<Tree> postProcessEnumBody(final List<Tree> enclosedElements,
                                           final IdentifierTree identifier) {
        return enclosedElements.stream()
                .map(enclosedElement -> {
                    if (enclosedElement instanceof VariableDeclaratorTree variableDeclaratorTree
                            && variableDeclaratorTree.getKind() == Kind.ENUM_CONSTANT) {
                        var newClassExpression = (NewClassExpression) variableDeclaratorTree.getValue();

                        newClassExpression = newClassExpression.builder()
                                .name(identifier)
                                .build();

                        return variableDeclaratorTree.builder()
                                .variableType(identifier)
                                .value(newClassExpression)
                                .build();
                    } else {
                        return enclosedElement;
                    }
                })
                .toList();
    }

    private List<Tree> flatList(final Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .flatMap(element -> {
                        if (element instanceof List<?>) {
                            final var subList = (List<Tree>) element;
                            return subList.stream();
                        } else {
                            return Stream.of((Tree) element);
                        }
                    }).toList();
        } else {
            return List.of((Tree) value);
        }
    }

    @Override
    public Object visitEnumBody(final Java20Parser.EnumBodyContext ctx) {
        final var body = new ArrayList<Tree>();

        if (ctx.enumConstantList() != null) {
            body.addAll(acceptList(ctx.enumConstantList()));
        }

        if (ctx.enumBodyDeclarations() != null) {
            body.addAll(acceptList(ctx.enumBodyDeclarations()));
        }

        return body;
    }

    @Override
    public Object visitEnumConstantList(final Java20Parser.EnumConstantListContext ctx) {
        return ctx.enumConstant().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitEnumConstant(final Java20Parser.EnumConstantContext ctx) {
        var modifiers = parseModifiers(ctx.enumConstantModifier());
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());

        final var lineNumber = ctx.getStart().getLine();
        final var columnNumber = ctx.getStart().getCharPositionInLine();

        final List<Tree> classBody;

        if (ctx.classBody() != null) {
            classBody = acceptList(ctx.classBody());
        } else {
            classBody = List.of();
        }

        final var value = TreeMaker.newClassExpression(
                null,
                List.of(),
                arguments,
                TreeMaker.classDeclaration(
                        Kind.ENUM,
                        NestingKind.TOP_LEVEL,
                        new Modifiers(),
                        null,
                        classBody,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        lineNumber,
                        columnNumber
                ),
                lineNumber,
                columnNumber
        );

        modifiers = modifiers
                .with(Flags.PUBLIC + Flags.STATIC + Flags.FINAL);

        return TreeMaker.variableDeclarator(
                Kind.ENUM_CONSTANT,
                modifiers,
                null,
                identifier,
                null,
                value,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitEnumBodyDeclarations(final Java20Parser.EnumBodyDeclarationsContext ctx) {
        return ctx.classBodyDeclaration().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitConstructorDeclaration(final Java20Parser.ConstructorDeclarationContext ctx) {
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
    public Object visitConstructorDeclarator(final Java20Parser.ConstructorDeclaratorContext ctx) {
        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());

        final VariableDeclaratorTree receiverParameter = accept(ctx.receiverParameter());

        final List<VariableDeclaratorTree> parameters = acceptList(ctx.formalParameterList());

        return TreeMaker.function(
                Constants.INIT,
                Kind.CONSTRUCTOR,
                new Modifiers(),
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
    public Object visitLiteral(final Java20Parser.LiteralContext ctx) {
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

    @Override
    public Object visitClassOrInterfaceType(final Java20Parser.ClassOrInterfaceTypeContext ctx) {
        ExpressionTree packageName = accept(ctx.packageName());
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());
        Tree result = (Tree) ctx.typeIdentifier().accept(this);

        if (!annotations.isEmpty()) {
            result = TreeMaker.annotatedTypeTree(
                    annotations,
                    (ExpressionTree) result,
                    List.of(),
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (packageName != null) {
            result = TreeMaker.fieldAccessExpressionTree(
                    packageName,
                    (IdentifierTree) result,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        final List<ExpressionTree> typeArguments = acceptList(ctx.typeArguments());

        if (!typeArguments.isEmpty()) {
            result = TreeMaker.typeApplyTree(
                    (ExpressionTree) result,
                    typeArguments,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        if (ctx.coit() != null) {
            final var coit = (ExpressionTree) ctx.coit().accept(this);
            result = TreeMaker.fieldAccessExpressionTree(
                    (ExpressionTree) result,
                    (IdentifierTree) coit,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        return result;
    }

    @Override
    public Object visitRecordDeclaration(final Java20Parser.RecordDeclarationContext ctx) {
        var modifiers = parseModifiers(ctx.classModifier());
        final var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);
        final List<TypeParameterTree> typeParameters = acceptList(ctx.typeParameters());
        final List<VariableDeclaratorTree> header = acceptList(ctx.recordHeader());
        final List<ExpressionTree> classImplements = acceptList(ctx.classImplements());
        final List<Tree> body = acceptList(ctx.recordBody());

        if (modifiers.hasFlag(Flags.FINAL)) {
            modifiers = modifiers.with(Flags.FINAL);
        }

        final var constructor = createCompactConstructor(header);

        final var enclosedElements = new ArrayList<Tree>();
        enclosedElements.add(constructor);
        enclosedElements.addAll(body);

        return new ClassDeclarationBuilder()
                .kind(Kind.RECORD)
                .modifiers(modifiers)
                .simpleName(identifier.getName())
                .typeParameters(typeParameters)
                .implemention(classImplements)
                .enclosedElements(enclosedElements)
                .build();
    }

    @Override
    public Object visitRecordHeader(final Java20Parser.RecordHeaderContext ctx) {
        return accept(ctx.recordComponentList());
    }

    @Override
    public Object visitRecordComponentList(final Java20Parser.RecordComponentListContext ctx) {
        return ctx.recordComponent().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitRecordComponent(final Java20Parser.RecordComponentContext ctx) {
        if (ctx.variableArityRecordComponent() != null) {
            return ctx.variableArityRecordComponent().accept(this);
        } else {
            final var modifiers = parseModifiers(ctx.recordComponentModifier());
            final var type = (ExpressionTree) ctx.unannType().accept(this);
            final var identifier = (IdentifierTree) ctx.identifier().accept(this);

            return new VariableDeclaratorTreeBuilder()
                    .kind(Kind.PARAMETER)
                    .modifiers(modifiers)
                    .variableType(type)
                    .name(identifier)
                    .build();
        }
    }

    @Override
    public Object visitVariableArityRecordComponent(final Java20Parser.VariableArityRecordComponentContext ctx) {
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);

        var modifiers = parseModifiers(ctx.recordComponentModifier());
        final List<AnnotationTree> annotations = acceptList(ctx.annotation());

        if (!annotations.isEmpty()) {
            final List<? extends AnnotationTree> allAnnotations = Stream.concat(
                            modifiers.getAnnotations().stream(),
                            annotations.stream())
                    .toList();
            modifiers = new Modifiers(
                    allAnnotations,
                    modifiers.getFlags()
            );
        }

        modifiers = modifiers.with(Flags.VARARGS);

        final var type = (ExpressionTree) ctx.unannType().accept(this);

        return new VariableDeclaratorTreeBuilder()
                .kind(Kind.PARAMETER)
                .modifiers(modifiers)
                .variableType(type)
                .name(identifier)
                .build();
    }

    @Override
    public Object visitRecordBody(final Java20Parser.RecordBodyContext ctx) {
        return ctx.recordBodyDeclaration().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitCompactConstructorDeclaration(final Java20Parser.CompactConstructorDeclarationContext ctx) {
        var modifiers = parseModifiers(ctx.constructorModifier());
        modifiers = modifiers.with(Flags.COMPACT_RECORD_CONSTRUCTOR);

        final var body = (BlockStatementTree) ctx.constructorBody().accept(this);

        return new FunctionBuilder()
                .kind(Kind.CONSTRUCTOR)
                .modifiers(modifiers)
                .simpleName(Constants.INIT)
                .body(body)
                .returnType(
                        TreeMaker.primitiveTypeTree(
                                PrimitiveTypeTree.Kind.VOID,
                                -1,
                                -1
                        )
                )
                .build();
    }

    private Function createCompactConstructor(final List<VariableDeclaratorTree> header) {
        return new FunctionBuilder()
                .kind(Kind.CONSTRUCTOR)
                .modifiers(
                        new Modifiers(
                                List.of(),
                                Flags.PUBLIC + Flags.COMPACT_RECORD_CONSTRUCTOR
                        )
                )
                .simpleName(Constants.INIT)
                .parameters(header)
                .body(TreeMaker.blockStatement(List.of(), -1, -1))
                .returnType(
                        TreeMaker.primitiveTypeTree(
                                PrimitiveTypeTree.Kind.VOID,
                                -1,
                                -1
                        )
                )
                .build();
    }

    @Override
    public Object visitBasicForStatement(final Java20Parser.BasicForStatementContext ctx) {
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
    public Object visitExpression(final Java20Parser.ExpressionContext ctx) {
        if (ctx.lambdaExpression() != null) {
            return ctx.lambdaExpression().accept(this);
        } else {
            return ctx.assignmentExpressionTree().accept(this);
        }
    }

    @Override
    public Object visitStatementExpressionList(final Java20Parser.StatementExpressionListContext ctx) {
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
    public Object visitPostIncrementExpression(final Java20Parser.PostIncrementExpressionContext ctx) {
        final var expression = (ExpressionTree) ctx.postfixExpression().accept(this);
        return TreeMaker.unaryExpressionTree(
                Tag.POST_INC,
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitLocalVariableDeclaration(final Java20Parser.LocalVariableDeclarationContext ctx) {
        final var modifiers = parseModifiers(ctx.variableModifier());
        final var type = (ExpressionTree) ctx.localVariableType().accept(this);
        final List<VariableDeclaratorTree> variableDeclarators = acceptList(ctx.variableDeclaratorList());

        if (variableDeclarators.isEmpty()) {
            return type;
        }

        final var list = variableDeclarators.stream()
                .map(variableDeclarator -> variableDeclarator.builder()
                        .kind(Kind.LOCAL_VARIABLE)
                        .modifiers(modifiers)
                        .variableType(type)
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
    public Object visitPrimaryNoNewArray(final Java20Parser.PrimaryNoNewArrayContext ctx) {
        final var pNNA = accept(ctx.pNNA());

        if (ctx.pattern() != null) {
            return ctx.pattern().accept(this);
        } else if (ctx.literal() != null) {
            final var literal = (ExpressionTree) ctx.literal().accept(this);

            if (pNNA == null) {
                return literal;
            } else {
                return combineExpressions(literal, pNNA);
            }
        }

        final var firstChild = ctx.getChild(0);

        if (firstChild instanceof TerminalNode terminalNode && firstChild.getText().equals("(")) {
            final var expression = (ExpressionTree) ctx.expression().accept(this);

            final var parenthesizedExpression = TreeMaker.parenthesizedExpression(
                    expression,
                    terminalNode.getSymbol().getLine(),
                    terminalNode.getSymbol().getCharPositionInLine()
                    );

            if (pNNA == null) {
                return parenthesizedExpression;
            } else {
                return combineExpressions(parenthesizedExpression, pNNA);
            }
        }


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
                        final ExpressionTree methodSelector = lastExpression;

                        lastExpression = new MethodInvocationTreeBuilder()
                                .typeArguments(typeArguments)
                                .methodSelector(methodSelector)
                                .arguments(arguments)
                                .build();
                        isMethodCall = false;
                    } else {
                        lastExpression = combineExpressions(lastExpression, child.accept(this));
                    }
                }
                case Java20Parser.ArgumentListContext ignored -> arguments.addAll(acceptList(child));
                case Java20Parser.TypeArgumentsContext ignored -> typeArguments.addAll(acceptList(child));
                default -> lastExpression = combineExpressions(lastExpression, child.accept(this));
            }
        }

        return lastExpression;
    }

    @Override
    public Object visitTypePattern(final Java20Parser.TypePatternContext ctx) {
        final var result = ctx.localVariableDeclaration().accept(this);

        if (result instanceof VariableDeclaratorTree variableDeclarator) {
            return new CTypePattern(
                    variableDeclarator,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return result;
        }
    }

    @Override
    public Object visitRelationalExpression(final Java20Parser.RelationalExpressionContext ctx) {
        final var children = ctx.children;
        Tree result = null;
        ExpressionTree lastExpression = null;
        String operatorText = null;
        final var lastIndex = children.size() - 1;

        for (var i = 0; i < children.size(); i++) {
            final var child = children.get(i);

            if (child instanceof TerminalNode terminalNode) {
                operatorText = terminalNode.getText();
                continue;
            }

            var childResult = child.accept(this);

            if (operatorText != null) {
                if ("instanceof".equals(operatorText)) {
                    childResult = TreeMaker.instanceOfExpression(
                            lastExpression,
                            (ExpressionTree) childResult,
                            ctx.getStart().getLine(),
                            ctx.getStart().getCharPositionInLine()
                    );
                } else {
                    final var tag = Tag.fromText(operatorText);
                    childResult = TreeMaker.binaryExpressionTree(
                            lastExpression,
                            tag,
                            (ExpressionTree) childResult,
                            ctx.getStart().getLine(),
                            ctx.getStart().getCharPositionInLine()
                    );
                }

                lastExpression = (ExpressionTree) childResult;
                continue;
            }

            if (childResult instanceof ExpressionTree expressionTree) {
                lastExpression = combineExpressions(lastExpression, expressionTree);
            } else {
                if (i == lastIndex) {
                    result = (Tree) childResult;
                } else {
                    throw new TodoException();
                }
            }
        }

        if (result == null) {
            result = lastExpression;
        }

        return result;
    }

    @Override
    public Object visitLocalVariableType(final Java20Parser.LocalVariableTypeContext ctx) {
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
    public Object visitPostfixExpression(final Java20Parser.PostfixExpressionContext ctx) {
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
                    (IdentifierTree) pfe,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        }

        return result;
    }

    @Override
    public Object visitUnqualifiedClassInstanceCreationExpression(final Java20Parser.UnqualifiedClassInstanceCreationExpressionContext ctx) {
        final List<ExpressionTree> typeArgs = acceptList(ctx.typeArguments());

        final var clazz = (ExpressionTree) ctx.classOrInterfaceTypeToInstantiate().accept(this);
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());
        final ClassDeclaration classDeclaration;

        if (ctx.classBody() != null) {
            final List<Tree> classBody = (List<Tree>) ctx.classBody().accept(this);
            classDeclaration = TreeMaker.classDeclaration(
                    null,
                    NestingKind.TOP_LEVEL,
                    new Modifiers(),
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
    public Object visitClassOrInterfaceTypeToInstantiate(final Java20Parser.ClassOrInterfaceTypeToInstantiateContext ctx) {
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
            } else {
                expressionTree = combineExpressions(expressionTree, expr);
            }
        }

        return expressionTree;
    }

    @Override
    public Object visitLocalVariableDeclarationStatement(final Java20Parser.LocalVariableDeclarationStatementContext ctx) {
        return ctx.localVariableDeclaration().accept(this);
    }

    @Override
    public Object visitReturnStatement(final Java20Parser.ReturnStatementContext ctx) {
        final ExpressionTree expression = accept(ctx.expression());
        return TreeMaker.returnStatement(
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitWhileStatement(final Java20Parser.WhileStatementContext ctx) {
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
    public Object visitWhileStatementNoShortIf(final Java20Parser.WhileStatementNoShortIfContext ctx) {
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
    public Object visitAssignment(final Java20Parser.AssignmentContext ctx) {
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
    public Object visitAssignmentOperator(final Java20Parser.AssignmentOperatorContext ctx) {
        return Tag.fromText(ctx.start.getText());
    }

    @Override
    public Object visitCastExpression(final Java20Parser.CastExpressionContext ctx) {
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
    public Object visitPNNA(final Java20Parser.PNNAContext ctx) {
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
                                .methodSelector(lastExpression)
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

    @Override
    public Object visitEnhancedForStatement(final Java20Parser.EnhancedForStatementContext ctx) {
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
    public Object visitEnhancedForStatementNoShortIf(final Java20Parser.EnhancedForStatementNoShortIfContext ctx) {
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
    public Object visitDoStatement(final Java20Parser.DoStatementContext ctx) {
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
    public Object visitPostDecrementExpression(final Java20Parser.PostDecrementExpressionContext ctx) {
        final var expression = (ExpressionTree) ctx.postfixExpression().accept(this);
        return TreeMaker.unaryExpressionTree(
                Tag.POST_DEC,
                expression,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitIfThenStatement(final Java20Parser.IfThenStatementContext ctx) {
        final var condition = (ExpressionTree) ctx.expression().accept(this);
        final var thenStatement = (StatementTree) ctx.statement().accept(this);
        return new CIfStatementTree(
                condition,
                thenStatement,
                null,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitIfThenElseStatement(final Java20Parser.IfThenElseStatementContext ctx) {
        final var condition = (ExpressionTree) ctx.expression().accept(this);
        final var thenStatement = (StatementTree) ctx.statementNoShortIf().accept(this);
        final var elseStatement = (StatementTree) ctx.statement().accept(this);

        return new CIfStatementTree(
                condition,
                thenStatement,
                elseStatement,
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Object visitIfThenElseStatementNoShortIf(final Java20Parser.IfThenElseStatementNoShortIfContext ctx) {
        throw new TodoException();
    }

    @Override
    public Object visitForStatementNoShortIf(final Java20Parser.ForStatementNoShortIfContext ctx) {
        throw new TodoException();
    }

    @Override
    public Object visitBasicForStatementNoShortIf(final Java20Parser.BasicForStatementNoShortIfContext ctx) {
        throw new TodoException();
    }

    @Override
    public Object visitEqualityExpression(final Java20Parser.EqualityExpressionContext ctx) {
        final var right = (Tree) ctx.relationalExpression().accept(this);

        if (ctx.equalityExpression() != null) {
            final ExpressionTree left = (ExpressionTree) ctx.equalityExpression().accept(this);
            final var operatorText = ctx.oper.getText();
            return TreeMaker.binaryExpressionTree(
                    left,
                    Tag.fromText(operatorText),
                    (ExpressionTree) right,
                    ctx.getStart().getLine(),
                    ctx.getStart().getCharPositionInLine()
            );
        } else {
            return right;
        }
    }

    @Override
    public Object visitUnaryExpression(final Java20Parser.UnaryExpressionContext ctx) {
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
    public Object visitAdditiveExpression(final Java20Parser.AdditiveExpressionContext ctx) {
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

    @Override
    public Object visitLambdaExpression(final Java20Parser.LambdaExpressionContext ctx) {
        final List<VariableDeclaratorTree> parameters = acceptList(ctx.lambdaParameters());
        final var body = (Tree) ctx.lambdaBody().accept(this);
        return TreeMaker.lambdaExpressionTree(
                parameters,
                body,
                ctx.start.getLine(),
                ctx.start.getCharPositionInLine()
        );
    }

    @Override
    public Object visitLambdaParameters(final Java20Parser.LambdaParametersContext ctx) {
        if (ctx.lambdaParameterList() != null) {
            return acceptList(ctx.lambdaParameterList());
        } else {
            return toLambdaVariable((Tree) ctx.identifier().accept(this));
        }
    }

    @Override
    public Object visitLambdaParameterList(final Java20Parser.LambdaParameterListContext ctx) {
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
    public Object visitLambdaParameter(final Java20Parser.LambdaParameterContext ctx) {
        final var name = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
        final var type = (ExpressionTree) ctx.lambdaParameterType().accept(this);

        return TreeMaker.variableDeclarator(
                Kind.PARAMETER,
                new Modifiers(),
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
    public Object visitLambdaBody(final Java20Parser.LambdaBodyContext ctx) {
        return super.visitLambdaBody(ctx);
    }
}