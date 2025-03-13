package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

public class FieldBuilder extends FieldVisitor {

    public FieldBuilder(final int api,
                        final int access,
                        final String name,
                        final String descriptor,
                        final String signature,
                        final Object value,
                        final ClassSymbol clazz,
                        final AsmTypeResolver asmTypeResolver,
                        final TypeBuilder typeBuilder) {
        super(api);
        final var elementKind = resolveElementKind(access);

        final TypeMirror type;

        if (signature == null) {
            type = asmTypeResolver.resolveByDescriptor(descriptor);
        } else {
            type = typeBuilder.parseFieldSignature(signature);
        }

        final var field = (VariableSymbol) new VariableBuilder()
                .kind(elementKind)
                .name(name)
                .enclosingElement(clazz)
                .modifiers(AccessUtils.parseModifiers(access))
                .type(type)
                .constantValue(value)
                .build();
        clazz.addEnclosedElement(field);
    }

    private ElementKind resolveElementKind(final int access) {
        if ((access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) {
            return ElementKind.ENUM_CONSTANT;
        }

        return ElementKind.FIELD;
    }
}
