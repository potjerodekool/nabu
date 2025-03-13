package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class CasterTest {

    private final ClassElementLoader loader = new AsmClassElementLoader();
    private final Types types = loader.getTypes();
    private final Caster caster = new Caster();

    @Test
    void visitPrimitiveType() {
        final var intType = types.getPrimitiveType(TypeKind.INT);
        final var methodInvocationTree = TreeMaker.methodInvocationTree(
                null,
                IdentifierTree.create("get"),
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var objectType = loader.loadClass(Constants.OBJECT).asType();

        final var integerType = loader.loadClass(Constants.INTEGER).asType();

        final var method = new MethodBuilder()
                .returnType(types.getTypeVariable("E", objectType, null))
                .build();

        final var methodType = types.getExecutableType(
                method,
                List.of(),
                integerType,
                List.of(),
                List.of()
        );

        methodInvocationTree.setMethodType(methodType);

        final var result = intType.accept(caster, methodInvocationTree);
        final var printer = new TreePrinter();
        result.accept(printer, null);
        final var actual = printer.getText();

        Assertions.assertEquals(
                "(java.lang.Integer) get()",
                actual
        );
    }
}