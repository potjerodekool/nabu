package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.backend.CompileException;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.backend.asm.ASMBackend;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20ParserBaseVisitor;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilders;
import io.github.potjerodekool.nabu.lang.model.element.builder.VariableElementBuilder;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.testing.InMemoryFileObject;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class IrGeneratingVisitorTest extends AbstractCompilerTest {

    private final IrGeneratingVisitor visitor = new IrGeneratingVisitor();

    @TempDir
    Path tempDir;

    @Test
    void visitCompilationUnit() throws CompileException, IOException {
        final var mainFunction = TreeMaker.function(
                "main",
                Kind.METHOD,
                new Modifiers(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                TreeMaker.blockStatement(List.of(), 0, 0),
                null,
                0,
                0
        );

        String m = """
                public static void main(String[] args) {
                }
                """;
        final var mainMethod = createMethod(m);

        mainFunction.setMethodSymbol(mainMethod);

        final var clazz = TreeMaker.classDeclaration(
                Kind.CLASS,
                NestingKind.TOP_LEVEL,
                new Modifiers(),
                "MyClass",
                List.of(mainFunction),
                List.of(),
                List.of(),
                null,
                List.of(),
                0,
                0
        );

        final var fileObject = new InMemoryFileObject(
                "MyClass",
                "MyClass.nabu"
        );

        CompilationUnit cu = TreeMaker.compilationUnit(
                fileObject,
                List.of(),
                List.of(clazz),
                0,
                0
        );

        visitor.acceptTree(cu, null);
        final var module = visitor.getModule();
        final var actual = compile(module);

        final var expected = """
                // class version 61.0 (61)
                // access flags 0x21
                public class MyClass {
                
                  // compiled from: MyClass.nabu
                
                  // access flags 0x9
                  public static main([Ljava/lang/String;)V
                    RETURN
                    MAXSTACK = 0
                    MAXLOCALS = 1
                }
                """;

        assertEquals(expected, actual);
    }

    private String compile(final IRModule module) throws CompileException, IOException {

        final var classFileName = module.name + ".class";
        Path out = tempDir.resolve(classFileName);

        final var backend = new ASMBackend();
        backend.compile(module, CompileOptions.defaults(), out);

        final var textifier = new Textifier();
        final var classNode = new ClassNode();
        final var visitor = new TraceClassVisitor(classNode, textifier, new PrintWriter(System.out));

        final var bytecode = Files.readAllBytes(out);

        final var reader = new ClassReader(bytecode);
        reader.accept(visitor, 0);

        return textifier.getText().stream()
                .map(it -> {
                    if (it instanceof List<?> list) {
                        return list.stream()
                                .map(Object::toString)
                                .collect(Collectors.joining());
                    }
                    return it.toString();
                })
                .collect(Collectors.joining());
    }

    private MethodSymbol createMethod(final String code) throws IOException {
        final var inputStream = new ByteArrayInputStream(code.getBytes());
        final var inputSteam = CharStreams.fromStream(inputStream);
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        final var methodDeclaration = parser.methodDeclaration();
        final var visitor = new SimpleParseTreeVisitor(
                getCompilerContext().getTypes(),
                getCompilerContext().getClassElementLoader(),
                getCompilerContext()
        );
        return (MethodSymbol) methodDeclaration.accept(visitor);
    }
}

class SimpleParseTreeVisitor extends Java20ParserBaseVisitor<Object> {

    private final Types types;
    private final ClassElementLoader loader;
    private final ElementBuilders elementBuilders;

    SimpleParseTreeVisitor(final Types types,
                           final ClassElementLoader loader,
                           final CompilerContext compilerContext) {
        this.types = types;
        this.loader = loader;
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
            default -> throw new TodoException("" +  node.getSymbol().getType());
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

        return clazz != null ? clazz.asType()  : null;
    }
}

class SimpleModifiers {
    long flags;
}
