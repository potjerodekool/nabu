package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.ToIType;
import org.objectweb.asm.ClassWriter;

public class AsmFieldByteCodeGenerator {

    private final ClassWriter classWriter;


    final ToIType toIType = new ToIType();

    public AsmFieldByteCodeGenerator(final ClassWriter classWriter) {
        this.classWriter = classWriter;
    }

    public void generate(final VariableElement variableElement) {
        if (variableElement.getKind() == ElementKind.FIELD) {
            final var access = AsmUtils.calculateAccess(variableElement.getModifiers());

            final var fieldType = variableElement.asType().accept(toIType, null);
            final var descriptor = AsmISignatureGenerator.INSTANCE.getDescriptor(fieldType);
            final var signature = AsmISignatureGenerator.INSTANCE.getFieldSignature(fieldType);

            //TODO handle field value
            final var value = variableElement.isFinal()
                ? variableElement.getConstantValue()
                : null;

            final var fieldVisitor = classWriter.visitField(
                    access,
                    variableElement.getSimpleName(),
                    descriptor,
                    signature,
                    value
            );

            fieldVisitor.visitEnd();
        } else {
            throw new TodoException();
        }
    }
}
