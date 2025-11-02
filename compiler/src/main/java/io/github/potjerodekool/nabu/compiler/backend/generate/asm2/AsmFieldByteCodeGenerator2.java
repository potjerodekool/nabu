package io.github.potjerodekool.nabu.compiler.backend.generate.asm2;

import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.ToIType;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class AsmFieldByteCodeGenerator2 {

    private final ClassWriter classWriter;

    public AsmFieldByteCodeGenerator2(final ClassWriter classWriter) {
        this.classWriter = classWriter;
    }

    public void generate(final VariableDeclaratorTree variableDeclaratorStatement) {
        final var name = variableDeclaratorStatement.getName().getName();
        final var type = variableDeclaratorStatement.getVariableType().getType();

        final var fieldType = ToIType.toIType(type);
        final var descriptor = AsmISignatureGenerator.INSTANCE.getDescriptor(fieldType);
        final var signature = AsmISignatureGenerator.INSTANCE.getFieldSignature(fieldType);

        final var fieldVisitor = classWriter.visitField(
                Opcodes.ACC_PUBLIC,
                name,
                descriptor,
                signature,
                null
        );

        fieldVisitor.visitEnd();
    }
}
