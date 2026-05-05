package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ModuleSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.Directive;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.builder.TypeElementBuilder;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AccessCheckerTest extends AbstractCompilerTest {

    private TypeElement createClass(final String moduleName,
                                    final String packageName,
                                    final String className,
                                    final Consumer<TypeElementBuilder> typeConsumer) {
        final var elementBuilders = getCompilerContext().getElementBuilders();

        final var fooModule = new ModuleSymbolBuilder()
                .simpleName(moduleName)
                .build();

        final var fooPackage = (PackageElement) elementBuilders.packageElementBuilder()
                .simpleName(packageName)
                .module(fooModule)
                .build();

        fooModule.setExports(
                List.of(
                        new Directive.ExportsDirective(
                                fooPackage,
                                List.of()
                        )
                )
        );

        final var builder = elementBuilders.typeElementBuilder()
                .kind(ElementKind.CLASS)
                .simpleName(className)
                .enclosingElement(fooPackage);

        if (typeConsumer != null) {
            typeConsumer.accept(builder);
        }

        return builder.build();
    }

    @Test
    void isAccessible() {
        final var fooClass = createClass("foo-module", "foo", "Foo", b -> {
            b.flags(Flags.PUBLIC);
        });
        final var barClass = createClass("bar-module", "bar", "Bar",b -> {
            b.flags(Flags.PUBLIC);
        });

        assertTrue(AccessChecker.isAccessible(fooClass, barClass));
    }
}