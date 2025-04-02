package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.ToIType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AccessUtils;
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

        final var fieldType = variableElement.asType().accept(ToIType.INSTANCE, null);
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
