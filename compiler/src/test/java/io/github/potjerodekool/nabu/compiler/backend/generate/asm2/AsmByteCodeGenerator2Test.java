package io.github.potjerodekool.nabu.compiler.backend.generate.asm2;

import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.test.TestClassElementLoader;
import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmByteCodeGenerator;
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

class AsmByteCodeGenerator2Test {

    private final ByteCodeGenerator generator = new AsmByteCodeGenerator(
            new CompilerOptions.CompilerOptionsBuilder()
                    .build()
    );

    private final TestClassElementLoader loader = new TestClassElementLoader();

    @Disabled
    @Test
    void generate() {
        final var clazz = new ClassDeclarationBuilder()
                .kind(Kind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .simpleName("SomeClass")
                .build();

        final var integerTypeTree = IdentifierTree.create("java.lang.Integer");
        final var integerType = loader.loadClass(null, "java.lang.Integer")
                .asType();
        integerTypeTree.setType(integerType);

        final var field = new VariableDeclaratorTreeBuilder()
                .kind(Kind.FIELD)
                .name(IdentifierTree.create("value"))
                .type(integerTypeTree)
                .build();

        clazz.enclosedElement(field);

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