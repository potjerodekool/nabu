package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.TodoException;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.List;

public class WithStackMethodVisitor extends MethodVisitor {

    private final Type OBJECT_TYPE = Type.getType(Object.class);
    private final List<Type> stack = new ArrayList<>();

    private Label lastLabel;

    protected WithStackMethodVisitor(final int api,
                                     final MethodVisitor methodVisitor) {
        super(api, methodVisitor);
    }

    public Label getLastLabel() {
        return lastLabel;
    }

    @Override
    public void visitLabel(final Label label) {
        super.visitLabel(label);
        this.lastLabel = label;
    }

    @Override
    public void visitVarInsn(final int opcode, final int varIndex) {
        super.visitVarInsn(opcode, varIndex);

        switch (opcode) {
            case Opcodes.ILOAD -> push(Type.INT_TYPE);
            case Opcodes.LLOAD -> push(Type.LONG_TYPE);
            case Opcodes.FLOAD -> push(Type.FLOAT_TYPE);
            case Opcodes.DLOAD -> push(Type.DOUBLE_TYPE);
            case Opcodes.ALOAD -> push(OBJECT_TYPE);
            case Opcodes.ISTORE,
                 Opcodes.LSTORE,
                 Opcodes.FSTORE,
                 Opcodes.DSTORE,
                 Opcodes.ASTORE -> pop();
        }
    }

    @Override
    public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

        if (opcode != Opcodes.INVOKESTATIC) {
            pop();
        }

        final var methodType = Type.getMethodType(descriptor);
        final var argumentTypes = methodType.getArgumentTypes();
        final var returnType = methodType.getReturnType();

        pop(argumentTypes.length);

        if (returnType.getSort() != Type.VOID) {
            push(returnType);
        }
    }

    @Override
    public void visitInvokeDynamicInsn(final String name, final String descriptor, final Handle bootstrapMethodHandle, final Object... bootstrapMethodArguments) {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);

        final var methodType = Type.getMethodType(descriptor);
        final var argumentTypes = methodType.getArgumentTypes();
        final var returnType = methodType.getReturnType();

        pop(argumentTypes.length);

        if (returnType.getSort() != Type.VOID) {
            push(returnType);
        }
    }

    @Override
    public void visitLdcInsn(final Object value) {
        super.visitLdcInsn(value);

        final var type = switch (value) {
            case String ignored -> Type.getType(value.getClass());
            case Boolean ignored -> Type.BOOLEAN_TYPE;
            case Character ignored -> Type.CHAR_TYPE;
            case Byte ignored -> Type.BYTE_TYPE;
            case Short ignored -> Type.SHORT_TYPE;
            case Integer ignored -> Type.INT_TYPE;
            case Float ignored -> Type.FLOAT_TYPE;
            case Long ignored -> Type.LONG_TYPE;
            case Double ignored -> Type.DOUBLE_TYPE;
            default -> throw new UnsupportedOperationException();
        };

        push(type);
    }

    private void push(final Type type) {
        this.stack.add(type);
    }

    private void pop() {
        if (stack.isEmpty()) {
            throw new EmptyStackException();
        }
        stack.removeLast();
    }

    private void pop(final int count) {
        for (int i = 0; i < count; i++) {
            pop();
        }
    }

    public Type peek() {
        if (stack.isEmpty()) {
            return null;
        } else {
            return stack.getLast();
        }
    }

    public Type[] peek2() {
        return new Type[]{
                stack.get(stack.size() - 2),
                stack.getLast()
        };
    }

    @Override
    public void visitInsn(final int opcode) {
        super.visitInsn(opcode);

        if (opcode >= Opcodes.ICONST_M1
                && opcode <= Opcodes.ICONST_5) {
            push(Type.INT_TYPE);
        } else if (opcode == Opcodes.LCONST_0
                || opcode == Opcodes.LCONST_1) {
            push(Type.LONG_TYPE);
        } else if (opcode == Opcodes.FCONST_0
                || opcode == Opcodes.FCONST_1
                || opcode == Opcodes.FCONST_2) {
            push(Type.FLOAT_TYPE);
        } else if (opcode == Opcodes.DCONST_0
                || opcode == Opcodes.DCONST_1) {
            push(Type.DOUBLE_TYPE);
        } else if (opcode == Opcodes.IRETURN
                || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN
                || opcode == Opcodes.DRETURN
                || opcode == Opcodes.ARETURN
                || opcode == Opcodes.RETURN
                || opcode == Opcodes.LCMP
                || opcode == Opcodes.CHECKCAST
                || opcode == Opcodes.IADD) {
            //Do nothing
        } else {
            throw new TodoException("" + opcode);
        }
    }

    @Override
    public void visitIntInsn(final int opcode, final int operand) {
        super.visitIntInsn(opcode, operand);

        if (opcode == Opcodes.BIPUSH) {
            push(Type.BYTE_TYPE);
        } else if (opcode == Opcodes.SIPUSH) {
            push(Type.SHORT_TYPE);
        } else {
            throw new TodoException();
        }
    }

    @Override
    public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
        super.visitFieldInsn(opcode, owner, name, descriptor);

        if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD) {
            push(Type.getType(descriptor));
        }
    }

    @Override
    public void visitIincInsn(final int varIndex, final int increment) {
        super.visitIincInsn(varIndex, increment);
    }
}

