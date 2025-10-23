package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.ToIType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AccessUtils;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class AsmFieldByteCodeGenerator {

    private final ClassWriter classWriter;

    public AsmFieldByteCodeGenerator(final ClassWriter classWriter) {
        this.classWriter = classWriter;
    }

    public void generate(final VariableSymbol variableElement) {
        var access = AccessUtils.flagsToAccess(variableElement.getFlags());

        if (variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
            access += Opcodes.ACC_ENUM;
        }

        final var fieldType = ToIType.toIType(variableElement.asType());
        final var descriptor = AsmISignatureGenerator.INSTANCE.getDescriptor(fieldType);
        final var signature = AsmISignatureGenerator.INSTANCE.getFieldSignature(fieldType);

        //TODO handle field value
        final var value = variableElement.isFinal()
            ? variableElement.getConstantValue()
            : null;

        if (variableElement.getKind() == ElementKind.FIELD
            || variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
            final var fieldVisitor = classWriter.visitField(
                    access,
                    variableElement.getSimpleName(),
                    descriptor,
                    signature,
                    value
            );

            final var annotations = variableElement.getAnnotationMirrors();

            annotations.forEach(annotationMirror -> {
                final var annotationDescriptor = AsmISignatureGenerator.INSTANCE.getDescriptor(
                        ToIType.toIType(annotationMirror.getAnnotationType())
                );

                final var isVisible = AsmUtils.isVisible(annotationMirror);

               final var annotationVisitor = fieldVisitor.visitAnnotation(
                       annotationDescriptor,
                       isVisible
                );
                annotationVisitor.visitEnd();
            });


            fieldVisitor.visitEnd();
        } else if (variableElement.getKind() == ElementKind.RECORD_COMPONENT) {
            final var recordComponentVisitor = classWriter.visitRecordComponent(
                    variableElement.getSimpleName(),
                    descriptor,
                    signature
            );
            recordComponentVisitor.visitEnd();
        }
    }
}
