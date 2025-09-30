package io.github.potjerodekool.nabu.compiler.backend.lower.codegen;

import io.github.potjerodekool.nabu.compiler.TestClassElementLoader;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.NewClassExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;
import org.junit.jupiter.api.Test;

class DefaultCodeGeneratorTest {

    @Test
    void generateCode() {
        final var loader = new TestClassElementLoader();
        final var generator = new DefaultCodeGenerator(loader);

        final var field = new VariableDeclaratorTreeBuilder()
                .kind(Kind.FIELD)
                .type(IdentifierTree.create("List"))
                .name(IdentifierTree.create("list"))
                .value(new NewClassExpressionBuilder()
                        .name(IdentifierTree.create("ArrayList"))
                        .build())
                .build();

        final var clazz = new ClassDeclarationBuilder()
                .enclosingElement(field)
                        .build();

        generator.generateCode(clazz);
    }
}