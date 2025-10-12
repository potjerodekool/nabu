package io.github.potjerodekool.nabu.compiler.backend.generate.asm2;

import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.ModuleDeclaration;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class AsmByteCodeGenerator2 extends AbstractTreeVisitor<Object, Object> implements ByteCodeGenerator {

    protected final ClassWriter classWriter = new ClassWriter(
            ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES
    );

    @Override
    public void generate(final ClassDeclaration clazz, final Object param) {
        final var name = clazz.getSimpleName();

        classWriter.visit(
                Opcodes.V20,
                Opcodes.ACC_PUBLIC,
                name,
                null,
                "java/lang/Object",
                null
        );

        clazz.getEnclosedElements().forEach(element -> element.accept(this, param));

        classWriter.visitEnd();
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Object param) {
        new AsmFieldByteCodeGenerator2(classWriter)
                .generate(variableDeclaratorStatement);
        return null;
    }

    @Override
    public void generate(final ModuleDeclaration moduleDeclaration, final Object param) {
    }

    @Override
    public byte[] getBytecode() {
        return classWriter.toByteArray();
    }
}
