package io.github.potjerodekool.nabu.compiler.lang.support.java.lomboksupport.handler;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructorHandlersTest extends AbstractCompilerTest {

    private VariableElement field(final String name) {
        return field(name, 0L);
    }

    private VariableElement field(final String name,
                                  final long flags) {
        return getCompilerContext().getElementBuilders()
                .variableElementBuilder()
                .kind(ElementKind.FIELD)
                .simpleName(name)
                .type(getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT))
                .flags(flags)
                .build();
    }

    private ClassSymbol clazz(final VariableElement... fields) {
        final var builder = getCompilerContext().getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.CLASS);

        for (final var field : fields) {
            builder.enclosedElement(field);
        }

        return (ClassSymbol) builder.build();
    }

    private void addNoArgConstructor(final ClassSymbol clazz) {
        final var constructor = (io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol) getCompilerContext()
                .getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.CONSTRUCTOR)
                .simpleName(Constants.INIT)
                .returnType(getCompilerContext().getTypes().getNoType(TypeKind.VOID))
                .build();

        clazz.addEnclosedElement(constructor);
    }

    @Test
    void requiredArgsConstructorAddsConstructorForFinalFields() {
        final var handler = new RequiredArgsConstructorHandler(getCompilerContext());
        final var clazz = clazz(field("id", Flags.FINAL));

        handler.handle(clazz);

        final var constructors = ElementFilter.constructorsIn(clazz.getEnclosedElements());
        assertEquals(1, constructors.size());
        final var constructor = constructors.getFirst();
        assertEquals(Constants.INIT, constructor.getSimpleName());
        assertEquals(1, constructor.getParameters().size());
        assertEquals("id", constructor.getParameters().getFirst().getSimpleName());
    }

    @Test
    void requiredArgsConstructorSkipsWithoutFinalFields() {
        final var handler = new RequiredArgsConstructorHandler(getCompilerContext());
        final var clazz = clazz(field("id"));

        handler.handle(clazz);

        assertTrue(ElementFilter.constructorsIn(clazz.getEnclosedElements()).isEmpty());
    }

    @Test
    void allArgsConstructorAddsConstructorForAllFields() {
        final var handler = new AllArgsConstructorHandler(getCompilerContext());
        final var clazz = clazz(field("id"), field("name"));

        handler.handle(clazz);

        final var constructors = ElementFilter.constructorsIn(clazz.getEnclosedElements());
        assertEquals(1, constructors.size());
        assertEquals(2, constructors.getFirst().getParameters().size());
    }

    @Test
    void noArgsConstructorAddsConstructorWhenNonePresent() {
        final var handler = new NoArgsConstructorHandler(getCompilerContext());
        final var clazz = clazz();

        handler.handle(clazz);

        final var constructors = ElementFilter.constructorsIn(clazz.getEnclosedElements());
        assertEquals(1, constructors.size());
        assertTrue(constructors.getFirst().getParameters().isEmpty());
    }

    @Test
    void noArgsConstructorSkipsWhenNoArgConstructorPresent() {
        final var handler = new NoArgsConstructorHandler(getCompilerContext());
        final var clazz = clazz();
        addNoArgConstructor(clazz);

        handler.handle(clazz);

        assertEquals(1, ElementFilter.constructorsIn(clazz.getEnclosedElements()).size());
    }

    @Test
    void noArgsConstructorSkipsWhenFinalFieldPresent() {
        final var handler = new NoArgsConstructorHandler(getCompilerContext());
        final var clazz = clazz(field("id", Flags.FINAL));

        handler.handle(clazz);

        assertTrue(ElementFilter.constructorsIn(clazz.getEnclosedElements()).isEmpty());
    }

    @Test
    void addGetterMethodAddsAccessor() {
        final var context = getCompilerContext();
        final var clazz = clazz();

        LombokAccessUtils.addGetterMethod(field("name"), Flags.PUBLIC, clazz, context);

        final var getters = ElementFilter.methodsIn(clazz.getEnclosedElements());
        assertEquals(1, getters.size());
        final var getter = getters.getFirst();
        assertEquals("getName", getter.getSimpleName());
        assertEquals(getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT), getter.getReturnType());
    }

    @Test
    void addSetterMethodAddsAccessor() {
        final var context = getCompilerContext();
        final var clazz = clazz();

        LombokAccessUtils.addSetterMethod(field("name"), Flags.PUBLIC, clazz, context);

        final var setters = ElementFilter.methodsIn(clazz.getEnclosedElements());
        assertEquals(1, setters.size());
        final var setter = setters.getFirst();
        assertEquals("setName", setter.getSimpleName());
        assertEquals(1, setter.getParameters().size());
        assertEquals("name", setter.getParameters().getFirst().getSimpleName());
    }

    @Test
    void accessorNamesAreGenerated() {
        assertEquals("getName", LombokAccessUtils.createGetterName("name"));
        assertEquals("setName", LombokAccessUtils.createSetterName("name"));
    }
}