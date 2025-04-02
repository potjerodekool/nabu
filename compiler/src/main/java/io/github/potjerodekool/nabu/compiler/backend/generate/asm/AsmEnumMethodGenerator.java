package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class AsmEnumMethodGenerator {

    void generateEnumFieldAndMethods(final ClassWriter classWriter,
                                     final ClassSymbol classSymbol) {
        generateValuesField(classWriter, classSymbol);
        generateValuesMethod(classWriter, classSymbol);
        generateValueOfMethod(classWriter, classSymbol);
        generatePrivateValuesMethod(classWriter, classSymbol);
        generateClientInit(classWriter, classSymbol);
    }

    private void generateValuesField(final ClassWriter classWriter,
                                     final ClassSymbol classSymbol) {
        final var classDescriptor = ClassUtils.getDescriptor(classSymbol.asType());
        final var fieldVisitor = classWriter.visitField(
                Opcodes.ACC_PRIVATE
                | Opcodes.ACC_FINAL
                | Opcodes.ACC_STATIC
                | Opcodes.ACC_SYNTHETIC,
            "$VALUES",
                String.format("[%s", classDescriptor),
                null,
                null
        );
        fieldVisitor.visitEnd();
    }

    private void generateValuesMethod(final ClassWriter classWriter,
                                      final ClassSymbol classSymbol) {
        final var classDescriptor = ClassUtils.getDescriptor(classSymbol.asType());
        final var classInternalName = ClassUtils.getInternalName(classSymbol.getQualifiedName());

        final var methodVisitor = classWriter.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "values",
                String.format("()[%s", classDescriptor),
                null,
                null
        );

        methodVisitor.visitCode();
        final var startLabel = new Label();
        methodVisitor.visitLabel(startLabel);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, classInternalName, "$VALUES", String.format("[%s", classDescriptor));
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, String.format("[%s", classDescriptor), "clone", "()Ljava/lang/Object;", false);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, String.format("[%s", classDescriptor));
        methodVisitor.visitInsn(Opcodes.ARETURN);
        methodVisitor.visitMaxs(1, 0);
        methodVisitor.visitEnd();
    }

    private void generateValueOfMethod(final ClassWriter classWriter,
                                       final ClassSymbol classSymbol) {
        final var classDescriptor = ClassUtils.getDescriptor(classSymbol.asType());
        final var classInternalName = ClassUtils.getInternalName(classSymbol.getQualifiedName());

        final var methodVisitor =
                classWriter.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "valueOf",
                        String.format("(Ljava/lang/String;)%s", classDescriptor),
                        null,
                        null);

        methodVisitor.visitParameter("noNameParm", Opcodes.ACC_MANDATED);
        methodVisitor.visitCode();
        final var startLabel = new Label();
        methodVisitor.visitLabel(startLabel);
        methodVisitor.visitLdcInsn(Type.getType(classDescriptor));
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
        methodVisitor.visitInsn(Opcodes.ARETURN);
        final var  endLabel = new Label();
        methodVisitor.visitLabel(endLabel);
        methodVisitor.visitLocalVariable("name", "Ljava/lang/String;", null, startLabel, endLabel, 0);
        methodVisitor.visitMaxs(2, 1);
        methodVisitor.visitEnd();
    }

    private void generatePrivateValuesMethod(final ClassWriter classWriter,
                                             final ClassSymbol classSymbol) {
        final var classDescriptor = ClassUtils.getDescriptor(classSymbol.asType());
        final var classInternalName = ClassUtils.getInternalName(classSymbol.getQualifiedName());
        final var enumConstants = ElementFilter.enumValues(classSymbol);
        final var methodVisitor = classWriter.visitMethod(
                Opcodes.ACC_PRIVATE |
                        Opcodes.ACC_STATIC |
                        Opcodes.ACC_SYNTHETIC,
                "$values",
                String.format("()[%s", classDescriptor),
                null,
                null
        );
        methodVisitor.visitCode();
        final var startLabel = new Label();
        methodVisitor.visitLabel(startLabel);

        if (enumConstants.size() < 6) {
            methodVisitor.visitInsn(Opcodes.ICONST_0 + enumConstants.size());
        } else {
            methodVisitor.visitIntInsn(Opcodes.BIPUSH, enumConstants.size());
        }

        methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, classInternalName);

        for (var index = 0; index < enumConstants.size(); index++) {
            methodVisitor.visitInsn(Opcodes.DUP);
            final var enumConstant = enumConstants.get(index);

            if (index < 6) {
                methodVisitor.visitInsn(Opcodes.ICONST_0 + index);
            } else {
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, index);
            }

            methodVisitor.visitFieldInsn(
                    Opcodes.GETSTATIC,
                    classInternalName,
                    enumConstant.getSimpleName(),
                    classDescriptor
            );
            methodVisitor.visitInsn(Opcodes.AASTORE);
        }
        methodVisitor.visitInsn(Opcodes.ARETURN);
        methodVisitor.visitMaxs(4, 0);
        methodVisitor.visitEnd();
    }

    void generateClientInit(final ClassWriter classWriter,
                            final ClassSymbol classSymbol) {
        final var classDescriptor = ClassUtils.getDescriptor(classSymbol.asType());
        final var classInternalName = ClassUtils.getInternalName(classSymbol.getQualifiedName());
        final var enumConstants = ElementFilter.enumValues(classSymbol);

        final var methodVisitor = classWriter.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        methodVisitor.visitCode();

        for (var index = 0; index < enumConstants.size(); index++) {
            final var constantName = enumConstants.get(index).getSimpleName();

            final var label = new Label();
            methodVisitor.visitLabel(label);
            methodVisitor.visitTypeInsn(Opcodes.NEW, classInternalName);
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitLdcInsn(constantName);

            if (index < 6) {
                methodVisitor.visitInsn(Opcodes.ICONST_0 + index);
            } else {
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, index);
            }

            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, classInternalName, "<init>", "(Ljava/lang/String;I)V", false);
            methodVisitor.visitFieldInsn(Opcodes.PUTSTATIC, classInternalName, constantName, classDescriptor);
        }

        final var label = new Label();
        methodVisitor.visitLabel(label);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, classInternalName, "$values", String.format("()[%s", classDescriptor), false);
        methodVisitor.visitFieldInsn(Opcodes.PUTSTATIC, classInternalName, "$VALUES", String.format("[%s", classDescriptor));
        methodVisitor.visitInsn(Opcodes.RETURN);
        methodVisitor.visitMaxs(4, 0);
        methodVisitor.visitEnd();
    }
}
