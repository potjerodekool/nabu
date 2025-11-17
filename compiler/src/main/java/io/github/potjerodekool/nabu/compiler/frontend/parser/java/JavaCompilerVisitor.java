package io.github.potjerodekool.nabu.compiler.frontend.parser.java;

import io.github.potjerodekool.nabu.Java20Lexer;
import io.github.potjerodekool.nabu.Java20Parser;
import io.github.potjerodekool.nabu.Java20ParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.frontend.parser.VariableArityParameter;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.frontend.parser.MethodDeclarator;
import io.github.potjerodekool.nabu.compiler.frontend.parser.MethodHeader;
import io.github.potjerodekool.nabu.compiler.frontend.parser.WildcardBound;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CArrayTypeTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CDimension;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;
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

public class JavaCompilerVisitor extends Java20ParserBaseVisitor<Object> {

    private final FileObject fileObject;
    private boolean isTopLevel = true;

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
}