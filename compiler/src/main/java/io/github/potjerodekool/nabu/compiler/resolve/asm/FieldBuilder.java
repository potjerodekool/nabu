package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.FieldTypeBuilder;
import io.github.potjerodekool.nabu.compiler.type.TypeUtils;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.signature.SignatureReader;

public class FieldBuilder extends FieldVisitor {

    // Ljava/util/List<Ljava/lang/module/Configuration;>;

    public FieldBuilder(final int api,
                        final int access,
                        final String name,
                        final String descriptor,
                        final String signature,
                        final Object value,
                        final ClassSymbol clazz,
                        final AsmTypeResolver asmTypeResolver) {
        super(api);
        final var type = asmTypeResolver.resolveByDescriptor(descriptor);
        final var field = new VariableElement(ElementKind.FIELD, name, clazz);
        field.addModifiers(Modifiers.parse(access));

        if (signature == null) {
            field.setVariableType(type);
        } else {
            field.setVariableType(type);

            final var reader = new SignatureReader(signature);

            final var signatureBuilder = new FieldTypeBuilder(
                    api,
                    asmTypeResolver.getClassElementLoader()
            );
            reader.accept(signatureBuilder);
            final var fieldType = TypeUtils.INSTANCE
                            .toImmutableType(
                                    signatureBuilder.getFieldType()
                            );
            field.setVariableType(fieldType);
        }

        clazz.addEnclosedElement(field);
    }
}
