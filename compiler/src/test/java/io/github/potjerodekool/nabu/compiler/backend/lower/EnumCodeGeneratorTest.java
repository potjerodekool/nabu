package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.TestClassElementLoader;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.backend.lower.codegen.EnumCodeGenerator;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.NewClassExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

class EnumCodeGeneratorTest {

    private final ClassSymbolLoader loader = new TestClassElementLoader();

    @Test
    void generateCode() {
        final var generator = new EnumCodeGenerator(
                loader
        );

        final var classSymbol = new ClassSymbolBuilder()
                .kind(ElementKind.ENUM)
                .simpleName("SomeEnum")
                .superclass(loader.getSymbolTable().getEnumType())
                .build();

        final var type = new CIdentifierTree("SomeEnum");
        type.setType(classSymbol.asType());

        final var enumConstant = new VariableDeclaratorTreeBuilder()
                .kind(Kind.ENUM_CONSTANT)
                .name(new CIdentifierTree("ONE"))
                .type(type)
                .value(new NewClassExpressionBuilder()
                        .build()
                )
                .build();

        final var classDeclaration = new ClassDeclarationBuilder()
                .kind(Kind.ENUM)
                .simpleName("SomeEnum")
                .enclosedElements(List.of(enumConstant))
                .build();

        classDeclaration.setClassSymbol(classSymbol);
        generator.generateCode(classDeclaration);

        final var valuesFunction = classDeclaration.getEnclosedElements().stream()
                .flatMap(CollectionUtils.mapOnly(Function.class))
                .filter(it -> "$values".equals(it.getSimpleName()))
                .findFirst()
                .get();

        final var actual = TreePrinter.print(valuesFunction);
        System.out.println(actual);
    }
}