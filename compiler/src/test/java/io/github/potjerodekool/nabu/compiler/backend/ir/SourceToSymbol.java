package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20ParserBaseVisitor;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilders;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SourceToSymbol {

    private SourceToSymbol() {
    }

    public static MethodSymbol createMethod(final String code,
                                            final CompilerContext compilerContext) throws IOException {
        final var inputStream = new ByteArrayInputStream(code.getBytes());
        final var inputSteam = CharStreams.fromStream(inputStream);
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        final var methodDeclaration = parser.methodDeclaration();
        final var visitor = new SimpleParseTreeVisitor(compilerContext);
        return (MethodSymbol) methodDeclaration.accept(visitor);
    }
}


class SimpleParseTreeVisitor extends Java20ParserBaseVisitor<Object> {

    private final Types types;
    private final ClassElementLoader loader;
    private final ElementBuilders elementBuilders;

    SimpleParseTreeVisitor(final CompilerContext compilerContext) {
        this.types = compilerContext.getTypes();
        this.loader = compilerContext.getClassElementLoader();
        elementBuilders = compilerContext.getElementBuilders();
    }

    @Override
    public Object visitMethodDeclaration(final Java20Parser.MethodDeclarationContext ctx) {
        final var modifiers = new SimpleModifiers();

        ctx.methodModifier().forEach(modifier -> {
            final var mod = modifier.accept(this);

            if (mod instanceof Long modValue) {
                modifiers.flags += modValue;
            }
        });

        final var methodSymbol = (MethodSymbol) ctx.methodHeader().accept(this);
        methodSymbol.setFlags(modifiers.flags);

        return methodSymbol;
    }

    @Override
    public Object visitMethodDeclarator(final Java20Parser.MethodDeclaratorContext ctx) {
        final var ident = (String) ctx.identifier().accept(this);
        final List<VariableElement> params;

        if (ctx.formalParameterList() != null) {
            params = (List<VariableElement>) ctx.formalParameterList().accept(this);
        } else {
            params = new ArrayList<>();
        }

        return elementBuilders.executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName(ident)
                .parameters(params)
                .build();
    }

    @Override
    public Object visitBlock(final Java20Parser.BlockContext ctx) {
        if (ctx.blockStatements() != null) {
            return ctx.blockStatements().accept(this);
        } else {
            return List.of();
        }
    }

    @Override
    public Object visitFormalParameterList(final Java20Parser.FormalParameterListContext ctx) {
        return ctx.formalParameter().stream()
                .map(param -> param.accept(this))
                .toList();
    }

    @Override
    public Object visitFormalParameter(final Java20Parser.FormalParameterContext ctx) {
        final var type = (TypeMirror) ctx.unannType().accept(this);
        final var name = (String) ctx.variableDeclaratorId().accept(this);

        return elementBuilders.variableElementBuilder()
                .type(type)
                .kind(ElementKind.PARAMETER)
                .simpleName(name)
                .build();
    }

    @Override
    public Object visitResult(final Java20Parser.ResultContext ctx) {
        if (ctx.VOID() != null) {
            return types.getNoType(TypeKind.VOID);
        }

        return super.visitResult(ctx);
    }

    @Override
    public Object visitMethodHeader(final Java20Parser.MethodHeaderContext ctx) {
        if (ctx.typeParameters() != null) {
            throw new TodoException();
        }

        final List<Object> annotations;

        if (ctx.annotation() != null && !ctx.annotation().isEmpty()) {
            annotations = ctx.annotation().stream()
                    .map(annotationContext -> annotationContext.accept(this))
                    .toList();
        } else {
            annotations = Collections.emptyList();
        }

        final var result = (TypeMirror) ctx.result().accept(this);
        final var method = (MethodSymbol) ctx.methodDeclarator().accept(this);

        if (ctx.throwsT() != null) {
            throw new TodoException();
        }

        method.setReturnType(result);

        return method;
    }

    @Override
    public Object visitTerminal(final TerminalNode node) {
        return switch (node.getSymbol().getType()) {
            case Java20Lexer.PUBLIC -> Flags.PUBLIC;
            case Java20Lexer.STATIC -> Flags.STATIC;
            case Java20Lexer.FINAL -> Flags.FINAL;
            case Java20Lexer.Identifier -> node.getText();
            default -> throw new TodoException("" + node.getSymbol().getType());
        };
    }

    @Override
    public Object visitUnannArrayType(final Java20Parser.UnannArrayTypeContext ctx) {
        final TypeMirror type;

        if (ctx.unannPrimitiveType() != null) {
            type = (TypeMirror) ctx.unannPrimitiveType().accept(this);
        } else if (ctx.unannClassOrInterfaceType() != null) {
            type = (TypeMirror) ctx.unannClassOrInterfaceType().accept(this);
        } else {
            type = (TypeMirror) ctx.unannTypeVariable().accept(this);
        }

        return types.getArrayType(type);
    }

    @Override
    public Object visitUnannClassOrInterfaceType(final Java20Parser.UnannClassOrInterfaceTypeContext ctx) {
        final var className = new StringBuilder();

        if (ctx.packageName() != null) {
            className.append(ctx.packageName().accept(this));
        }

        className.append(ctx.typeIdentifier().accept(this));

        final var name = className.toString();

        var clazz = loader.loadClass(null, name);

        if (clazz == null && !name.contains(".")) {
            clazz = loader.loadClass(null, "java.lang." + name);
        }

        return clazz != null ? clazz.asType() : null;
    }
}

class SimpleModifiers {
    long flags;
}
