package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.backend.lower.codegen.EnumCodeGenerator;
import io.github.potjerodekool.nabu.testing.TreePrinter;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.expression.builder.NewClassExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.util.CollectionUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

class EnumCodeGeneratorTest extends AbstractCompilerTest {

    private final ClassElementLoader loader = getCompilerContext().getClassElementLoader();

    @Test
    void generateCode() {
        final var generator = new EnumCodeGenerator(
                getCompilerContext()
        );

        final var enumType = loader.loadClass(null, Constants.ENUM).asType();

        final var classSymbol = new ClassSymbolBuilder()
                .kind(ElementKind.ENUM)
                .simpleName("SomeEnum")
                .superclass(enumType)
                .build();

        final var type = new CIdentifierTree("SomeEnum");
        type.setType(classSymbol.asType());

        final var enumConstant = new VariableDeclaratorTreeBuilder()
                .kind(Kind.ENUM_CONSTANT)
                .name(new CIdentifierTree("ONE"))
                .variableType(type)
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