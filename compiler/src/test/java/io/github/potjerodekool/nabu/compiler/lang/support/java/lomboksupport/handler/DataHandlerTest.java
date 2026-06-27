package io.github.potjerodekool.nabu.compiler.lang.support.java.lomboksupport.handler;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DataHandlerTest extends AbstractCompilerTest {

    @Test
    void handle() {
        final var handler = new DataHandler(
                null,
                null,
                null,
                getCompilerContext()
        );

        final var intType = getCompilerContext().getTypes()
                .getPrimitiveType(TypeKind.INT);
        final var voidType = getCompilerContext().getTypes()
                .getNoType(TypeKind.VOID);

        final var elementBuilders = getCompilerContext().getElementBuilders();

        final var idField = elementBuilders.variableElementBuilder()
                .kind(ElementKind.FIELD)
                .simpleName("id")
                .type(intType)
                .build();

        final var clazz = (ClassSymbol) elementBuilders
                .typeElementBuilder()
                .kind(ElementKind.CLASS)
                .enclosedElement(idField)
                .build();

        handler.handle(clazz);

        final var methods = clazz.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(it -> (ExecutableElement) it)
                .collect(Collectors.toMap(
                        ExecutableElement::getSimpleName,
                        Function.identity()
                ));

        final var getter = methods.get("getId");
        assertEquals(intType, getter.getReturnType());

        final var setter = methods.get("setId");
        assertEquals(voidType, setter.getReturnType());
        assertEquals(1, setter.getParameters().size());
        assertEquals(intType, setter.getParameters().getFirst().asType());
    }
}