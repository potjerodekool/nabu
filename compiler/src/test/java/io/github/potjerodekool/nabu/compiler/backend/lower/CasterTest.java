package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.test.TreePrinter;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class CasterTest extends AbstractCompilerTest {

    private final ClassElementLoader loader = getCompilerContext().getClassElementLoader();
    private final Types types = loader.getTypes();
    private final Caster caster = new Caster();

    @Test
    void visitPrimitiveType() {
        final var intType = types.getPrimitiveType(TypeKind.INT);
        final var methodInvocationTree = TreeMaker.methodInvocationTree(
                IdentifierTree.create("get"),
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var module = getCompilerContext().getSymbolTable().getUnnamedModule();

        final var objectType = loader.loadClass(module, Constants.OBJECT).asType();

        final var integerType = loader.loadClass(module, Constants.INTEGER).asType();

        final var method = new MethodSymbolBuilderImpl()
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