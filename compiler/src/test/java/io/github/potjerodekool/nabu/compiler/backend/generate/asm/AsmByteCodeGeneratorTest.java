package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

class AsmByteCodeGeneratorTest extends AbstractCompilerTest {

    private final ByteCodeGenerator generator = new AsmByteCodeGenerator(
            new CompilerOptions.CompilerOptionsBuilder()
                    .build()
    );

    @Disabled
    @Test
    void generate() {
        final var module = getCompilerContext().getSymbolTable().getUnnamedModule();
        final var clazz = new ClassDeclarationBuilder()
                .kind(Kind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .simpleName("SomeClass")
                .build();

        final var z = getCompilerContext().getClassElementLoader().loadClass(
                module,
                "foo.SomeClass"
        );

        final var integerTypeTree = IdentifierTree.create("java.lang.Integer");
        final var integerType = getCompilerContext().getClassElementLoader().loadClass(null, "java.lang.Integer")
                .asType();
        integerTypeTree.setType(integerType);

        final var field = new VariableDeclaratorTreeBuilder()
                .kind(Kind.FIELD)
                .name(IdentifierTree.create("value"))
                .variableType(integerTypeTree)
                .build();

        clazz.enclosedElement(field);

        clazz.setClassSymbol(getCompilerContext().getClassElementLoader().loadClass(module, "foo.SomeClass"));

        generator.generate(clazz, null);
        final var bytecode = generator.getBytecode();

        final var reader = new ClassReader(bytecode);
        final var output = new ByteArrayOutputStream();
        final var visitor = new TraceClassVisitor(new PrintWriter(output));
        reader.accept(visitor, 0);

        final var code = output.toString();
        System.out.println(code);
    }
}