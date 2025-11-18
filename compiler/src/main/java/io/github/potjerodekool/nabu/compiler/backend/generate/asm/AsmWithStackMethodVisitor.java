package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tools.Constants;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.List;

public class AsmWithStackMethodVisitor extends MethodVisitor {

    private final Type OBJECT_TYPE = Type.getType(Object.class);
    private final List<Type> stack = new ArrayList<>();

    private Label lastLabel;

    protected AsmWithStackMethodVisitor(final int api,
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
    public void visitVarInsn(final int opcode,
                             final int varIndex) {
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
    public void visitMethodInsn(final int opcode,
                                final String owner,
                                final String name,
                                final String descriptor,
                                final boolean isInterface) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

        if (opcode != Opcodes.INVOKESTATIC
                && !Constants.INIT.equals(name)) {
            pop();
        }

        final var methodType = Type.getMethodType(descriptor);
        final var argumentTypes = methodType.getArgumentTypes();
        final var returnType = methodType.getReturnType();

        pop(argumentTypes.length);

        if (returnType.getSort() != Type.VOID) {
            push(returnType);
        } else if (Opcodes.INVOKESPECIAL == opcode
                && Constants.INIT.equals(name)) {
            pop();
        }
    }

    @Override
    public void visitInvokeDynamicInsn(final String name,
                                       final String descriptor,
                                       final Handle bootstrapMethodHandle,
                                       final Object... bootstrapMethodArguments) {
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
            case Type t -> t;
            default -> throw new UnsupportedOperationException();
        };

        push(type);
    }

    public void push(final Type type) {
        this.stack.add(type);
    }

    public void pop() {
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

    public Type peek(final int index) {
        if (stack.isEmpty()) {
            return null;
        } else {
            return stack.get(stack.size() - 1 - index);
        }
    }

    public Type[] peek2() {
        if (stack.size() > 1) {
            return new Type[]{
                    stack.get(stack.size() - 2),
                    stack.getLast()
            };
        } else {
            return new Type[]{
                    stack.getLast(),
                    null
            };
        }
    }

    @Override
    public void visitInsn(final int opcode) {
        super.visitInsn(opcode);

        switch (opcode) {
            case Opcodes.ICONST_M1,
                 Opcodes.ICONST_0,
                 Opcodes.ICONST_1,
                 Opcodes.ICONST_2,
                 Opcodes.ICONST_3,
                 Opcodes.ICONST_4,
                 Opcodes.ICONST_5 -> push(Type.INT_TYPE);
            case Opcodes.LCONST_0,
                 Opcodes.LCONST_1 -> push(Type.LONG_TYPE);
            case Opcodes.FCONST_0,
                 Opcodes.FCONST_1,
                 Opcodes.FCONST_2 -> push(Type.FLOAT_TYPE);
            case Opcodes.DCONST_0,
                 Opcodes.DCONST_1 -> push(Type.DOUBLE_TYPE);
            case Opcodes.IRETURN,
                 Opcodes.LRETURN,
                 Opcodes.FRETURN,
                 Opcodes.DRETURN,
                 Opcodes.ARETURN,
                 Opcodes.RETURN,
                 Opcodes.LCMP,
                 Opcodes.CHECKCAST,
                 Opcodes.IADD,
                 Opcodes.ATHROW -> {
                //Do nothing
            }
            case Opcodes.IALOAD -> push(Type.INT_TYPE);
            case Opcodes.LALOAD -> push(Type.LONG_TYPE);
            case Opcodes.FALOAD -> push(Type.FLOAT_TYPE);
            case Opcodes.DALOAD -> push(Type.DOUBLE_TYPE);
            case Opcodes.BALOAD -> push(Type.BYTE_TYPE);
            case Opcodes.CALOAD -> push(Type.CHAR_TYPE);
            case Opcodes.SALOAD -> push(Type.SHORT_TYPE);
            case Opcodes.DUP -> push(peek());
            case Opcodes.AASTORE -> pop(3);
            case Opcodes.IFNONNULL -> push(Type.BOOLEAN_TYPE);
            case Opcodes.POP -> pop();
            default -> throw new TodoException("" + opcode);
        }
    }

    @Override
    public void visitIntInsn(final int opcode,
                             final int operand) {
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
    public void visitFieldInsn(final int opcode,
                               final String owner,
                               final String name,
                               final String descriptor) {
        super.visitFieldInsn(opcode, owner, name, descriptor);

        if (opcode == Opcodes.GETSTATIC) {
            push(Type.getType(descriptor));
        } else if (opcode == Opcodes.GETFIELD) {
            pop();
            push(Type.getType(descriptor));
        } else if (opcode == Opcodes.PUTSTATIC) {
            pop();
        } else if (opcode == Opcodes.PUTFIELD) {
            pop(2);
        }
    }

    @Override
    public void visitIincInsn(final int varIndex,
                              final int increment) {
        super.visitIincInsn(varIndex, increment);
    }

    @Override
    public void visitTypeInsn(final int opcode,
                              final String type) {
        super.visitTypeInsn(opcode, type);

        if (opcode == Opcodes.NEW) {
            push(Type.getObjectType(type));
        } else {
            pop();

            if (opcode == Opcodes.CHECKCAST) {
                push(Type.getObjectType(type));
            } else if (opcode == Opcodes.INSTANCEOF) {
                push(Type.BOOLEAN_TYPE);
            } else if (opcode == Opcodes.ANEWARRAY) {
                final var name = "[L" + type + ";";
                push(Type.getType(name));
            }
        }
    }

    @Override
    public void visitJumpInsn(final int opcode, final Label label) {
        super.visitJumpInsn(opcode, label);

        if (opcode == Opcodes.IFNULL
                || opcode == Opcodes.IFNONNULL
                || opcode == Opcodes.IFEQ) {
            pop();
        }
    }
}

