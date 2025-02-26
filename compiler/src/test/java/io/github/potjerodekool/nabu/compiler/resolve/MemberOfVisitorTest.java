package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.NestingKind;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.Types;
import org.junit.jupiter.api.Test;

class MemberOfVisitorTest {

    private final ClassElementLoader loader = new AsmClassElementLoader();
    private final Types types = loader.getTypes();

    @Test
    void visitDeclaredType() {
        final var visitor = new MemberOfVisitor(types);
        final var listSymbol = loader.resolveClass("java.util.List");

        final var stringSymbol = loader.resolveClass(Constants.STRING);

        final var stringType = types.getDeclaredType(
                stringSymbol
        );

        final var intType = types.getPrimitiveType(TypeKind.INT);

        final var classType = types.getDeclaredType(
                listSymbol,
                stringType
        );

        final var clazz = new ClassBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .name("SomeClass")
                .build();

        final var objectType = loader.resolveClass(Constants.OBJECT).asType();

        final var typeVar = types.getTypeVariable(
                "T",
                objectType,
                null
        );

        final var method = new MethodBuilder()
                .name("get")
                .enclosingElement(clazz)
                .returnType(typeVar)
                .argumentType(intType)
                .typeParameter((TypeParameterElement) typeVar.asElement())
                .build();

        classType.accept(visitor, method);
    }
}