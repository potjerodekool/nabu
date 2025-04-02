package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.backend.ir.ToIType;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassUtils;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import org.objectweb.asm.*;

import java.util.List;
import java.util.stream.Collectors;

public class AsmRecordMethodsGenerator {

    void generateMethods(final ClassWriter classWriter,
                         final ClassSymbol classSymbol) {
        generateToStringMethod(classWriter, classSymbol);
        generateHashCodeMethod(classWriter, classSymbol);
        generateEqualsMethod(classWriter, classSymbol);
    }

    void generateToStringMethod(final ClassWriter classWriter,
                                final ClassSymbol classSymbol) {
        if (ElementFilter.methods(classSymbol).stream()
                .filter(method -> "toString".equals(method.getSimpleName()))
                .anyMatch(method -> method.asType().getParameterTypes().isEmpty())) {
            //record contains a toString method. No need to generate it.
            return;
        }

        final var classDescriptor = ClassUtils.getClassDescriptor(classSymbol.getQualifiedName());
        final var innerClassName = ClassUtils.getInternalName(classSymbol.getQualifiedName());
        final var fields = ElementFilter.fields(classSymbol);

        final var methodVisitor = classWriter.visitMethod(
                Opcodes.ACC_PUBLIC |
                        Opcodes.ACC_FINAL,
                "toString",
                "()Ljava/lang/String;",
                null,
                null
        );

        methodVisitor.visitCode();
        final var startLabel = new Label();
        methodVisitor.visitLabel(startLabel);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);

        final var handle = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/runtime/ObjectMethods",
                "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;", false);

        final var boostrapMethodArguments = generateBootstrapMethodArguments(
                fields,
                classDescriptor,
                innerClassName
        );

        methodVisitor.visitInvokeDynamicInsn(
                "toString",
                String.format("(%s)Ljava/lang/String;", classDescriptor),
                handle,
                boostrapMethodArguments);

        methodVisitor.visitInsn(Opcodes.ARETURN);

        final var endLabel = new Label();
        methodVisitor.visitLabel(endLabel);
        methodVisitor.visitLocalVariable("this", classDescriptor, null, startLabel, endLabel, 0);
        methodVisitor.visitMaxs(1, 1);
        methodVisitor.visitEnd();
    }

    private Object[] generateBootstrapMethodArguments(final List<VariableElement> fields,
                                                      final String classDescriptor,
                                                      final String innerClassName) {
        final var fieldNames = fields.stream()
                .map(Element::getSimpleName)
                .collect(Collectors.joining(";"));

        final var boostrapMethodArguments = new Object[fields.size() + 2];
        boostrapMethodArguments[0] = Type.getType(classDescriptor);
        boostrapMethodArguments[1] = fieldNames;

        for (var index = 0; index < fields.size(); index++) {
            final var field = fields.get(index);
            final var fieldHandle = new Handle(Opcodes.H_GETFIELD,
                    innerClassName,
                    field.getSimpleName(),
                    AsmISignatureGenerator.toAsmType(
                            field.asType().accept(ToIType.INSTANCE, null)
                    ).getDescriptor(),
                    false
            );
            boostrapMethodArguments[index + 2] = fieldHandle;
        }

        return boostrapMethodArguments;
    }

    void generateHashCodeMethod(final ClassWriter classWriter,
                                final ClassSymbol classSymbol) {
        if (ElementFilter.methods(classSymbol).stream()
                .filter(method -> "hashCode".equals(method.getSimpleName()))
                .anyMatch(method -> method.asType().getParameterTypes().isEmpty())) {
            //record contains a hashCode method. No need to generate it.
            return;
        }

        final var classDescriptor = ClassUtils.getClassDescriptor(classSymbol.getQualifiedName());
        final var innerClassName = ClassUtils.getInternalName(classSymbol.getQualifiedName());
        final var fields = ElementFilter.fields(classSymbol);

        final var boostrapMethodArguments = generateBootstrapMethodArguments(
                fields,
                classDescriptor,
                innerClassName
        );

        final var methodVisitor = classWriter.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "hashCode",
                "()I",
                null,
                null
        );

        methodVisitor.visitCode();
        final var startLabel = new Label();
        methodVisitor.visitLabel(startLabel);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInvokeDynamicInsn(
                "hashCode",
                String.format("(%s)I", classDescriptor),
                new Handle(Opcodes.H_INVOKESTATIC,
                        "java/lang/runtime/ObjectMethods",
                        "bootstrap",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;",
                        false),
                boostrapMethodArguments);
        methodVisitor.visitInsn(Opcodes.IRETURN);
        final var endLabel = new Label();
        methodVisitor.visitLabel(endLabel);
        methodVisitor.visitLocalVariable("this", classDescriptor, null, startLabel, endLabel, 0);
        methodVisitor.visitMaxs(1, 1);
        methodVisitor.visitEnd();
    }

    void generateEqualsMethod(final ClassWriter classWriter,
                              final ClassSymbol classSymbol) {
        if (ElementFilter.methods(classSymbol).stream()
                .filter(method -> "equals".equals(method.getSimpleName()))
                .anyMatch(method -> {
                    final var parameterTypes = method.asType().getParameterTypes();
                    return parameterTypes.size() == 1 && parameterTypes.getFirst() instanceof DeclaredType declaredType
                            && Constants.OBJECT.equals(declaredType.getClassName());
                })) {
            //record contains an equals method. No need to generate it.
            return;
        }

        final var classDescriptor = ClassUtils.getClassDescriptor(classSymbol.getQualifiedName());
        final var innerClassName = ClassUtils.getInternalName(classSymbol.getQualifiedName());
        final var fields = ElementFilter.fields(classSymbol);

        final var bootstrapMethodArguments = generateBootstrapMethodArguments(
                fields,
                classDescriptor,
                innerClassName
        );

        final var methodWriter = classWriter.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "equals",
                "(Ljava/lang/Object;)Z",
                null,
                null
        );

        methodWriter.visitCode();
        final var startLabel = new Label();
        methodWriter.visitLabel(startLabel);
        methodWriter.visitVarInsn(Opcodes.ALOAD, 0);
        methodWriter.visitVarInsn(Opcodes.ALOAD, 1);
        methodWriter.visitInvokeDynamicInsn(
                "equals",
                String.format("(%sLjava/lang/Object;)Z", classDescriptor),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/runtime/ObjectMethods",
                        "bootstrap",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;",
                        false),

                bootstrapMethodArguments);

        methodWriter.visitInsn(Opcodes.IRETURN);
        final var endLabel = new Label();
        methodWriter.visitLabel(endLabel);
        methodWriter.visitLocalVariable("this", classDescriptor, null, startLabel, endLabel, 0);
        methodWriter.visitLocalVariable("o", "Ljava/lang/Object;", null, startLabel, endLabel, 1);
        methodWriter.visitMaxs(2, 2);
        methodWriter.visitEnd();
    }


}
