package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.BinOp;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Const;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Mem;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.CJump;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractMethodGenerator implements CodeVisitor<Frame> {

    private final Map<String,Label> labelMap = new HashMap<>();

    protected abstract WithStackMethodVisitor getMethodWriter();

    @Override
    public void visitCJump(final CJump cJump, final Frame frame) {
        if (immediate(cJump)) {
            visitCJumpImmediate(cJump, frame);
        } else {
            visitCJumpNotImmediate(cJump, frame);
        }
    }

    private boolean immediate(final CJump cjump) {
        final var left = const16(cjump.getLeft());
        final var right = const16(cjump.getRight());

        if (left == null) {
            return right != null;
        } else if (right == null) {
            cjump.setLeft(cjump.getRight());
            cjump.setRight(left);
            switch (cjump.getTag()) {
                case EQ:
                case NE:
                    break;
                case LT:
                    cjump.setTag(Tag.GT);
                    break;
                case GE:
                    cjump.setTag(Tag.LE);
                    break;
                case GT:
                    cjump.setTag(Tag.LT);
                    break;
                case LE:
                    cjump.setTag(Tag.GE);
                    break;
                default:
                    throw new TodoException(String.valueOf(cjump.getTag()));
            }
        }
        return true;
    }

    private void visitCJumpImmediate(final CJump cJump,
                                     final Frame frame) {
        final IExpression left = cJump.getLeft();
        final Label trueLabel = getOrCreateLabel(cJump.getTrueLabel());

        if (left instanceof BinOp binop) {
            binop.getRight().accept(this, frame);
            getMethodWriter().visitJumpInsn(Opcodes.IFNE, trueLabel);
        } else {
            left.accept(this, frame);

            IExpression right = cJump.getRight();

            if (right instanceof Mem mem) {
                final IExpression rightExpr = mem.getExp();

                if (rightExpr instanceof Const) {
                    right = rightExpr;
                }
            }

            final boolean compare;
            final Object rightValue;

            if (right instanceof Const c) {
                rightValue = c.getValue();

                if (rightValue instanceof Integer integer) {
                    if (integer != 0) {
                        cJump.getRight().accept(this, frame);
                        compare = true;
                    } else {
                        compare = false;
                    }
                } else {
                    compare = true;
                }
            } else {
                cJump.getRight().accept(this, frame);
                compare = true;
            }

            final var tag = cJump.getTag();
            final Type type = unboxType(peek());

            if (type == null) {
                if (tag == Tag.NE) {
                    getMethodWriter().visitJumpInsn(Opcodes.IFNE, trueLabel);
                } else {
                    getMethodWriter().visitJumpInsn(Opcodes.IFEQ, trueLabel);
                }
            } else if (type.getSort() == Type.SHORT ||
                    type.getSort() == Type.INT ||
                    type.getSort() == Type.BOOLEAN) {
                switch (tag) {
                    case NE:
                        if (compare) {
                            getMethodWriter().visitJumpInsn(Opcodes.IF_ICMPNE, trueLabel);
                        } else {
                            getMethodWriter().visitJumpInsn(Opcodes.IFNE, trueLabel);
                        }
                        break;
                    case EQ:
                        if (compare) {
                            getMethodWriter().visitJumpInsn(Opcodes.IF_ICMPEQ, trueLabel);
                        } else {
                            getMethodWriter().visitJumpInsn(Opcodes.IFEQ, trueLabel);
                        }
                        break;
                    case LT:
                        if (compare) {
                            getMethodWriter().visitJumpInsn(Opcodes.IF_ICMPLT, trueLabel);
                        } else {
                            getMethodWriter().visitJumpInsn(Opcodes.IFLT, trueLabel);
                        }
                        break;
                    case LE:
                        if (compare) {
                            getMethodWriter().visitJumpInsn(Opcodes.IF_ICMPLE, trueLabel);
                        } else {
                            getMethodWriter().visitJumpInsn(Opcodes.IFLE, trueLabel);
                        }
                        break;
                    case GE:
                        if (compare) {
                            getMethodWriter().visitJumpInsn(Opcodes.IF_ICMPGE, trueLabel);
                        } else {
                            getMethodWriter().visitJumpInsn(Opcodes.IFGE, trueLabel);
                        }
                        break;
                    case GT:
                        if (compare) {
                            getMethodWriter().visitJumpInsn(Opcodes.IF_ICMPGT, trueLabel);
                        } else {
                            getMethodWriter().visitJumpInsn(Opcodes.IFGT, trueLabel);
                        }
                        break;
                    default:
                        throw new TodoException();
                }
            } else if (type.getSort() == Type.LONG) {
                visitCJumpImmediateLong(cJump, compare);
            } else {
                throw new TodoException("" + type);
            }
        }
    }

    private void visitCJumpNotImmediate(final CJump cJump,
                                        final Frame frame) {
        cJump.getLeft().accept(this, frame);
        final IExpression right = cJump.getRight();
        final boolean rightIsNull = isNullLiteral(right);

        final int leftType = peek().getSort();

        if (!rightIsNull) {
            right.accept(this, frame);
        }

        if (leftType == Type.LONG) {
            visitCJumpNotImmediateLong(cJump);
        } else if (leftType == Type.INT) {
            visitCJumpNotImmediateInteger(cJump);
        } else {
            final int opcode;

            switch (cJump.getTag()) {
                case NE:
                    if (rightIsNull) {
                        opcode = Opcodes.IFNONNULL;
                    } else {
                        opcode = Opcodes.IF_ACMPNE;
                    }
                    break;
                case EQ:
                    if (rightIsNull) {
                        opcode = Opcodes.IFNULL;
                    } else {
                        opcode = Opcodes.IF_ACMPEQ;
                    }
                    break;
                case LT:
                    opcode = Opcodes.IFLT;
                    break;
                case LE:
                    opcode = Opcodes.IFLE;
                    break;
                case GE:
                    opcode = Opcodes.IFGE;
                    break;
                case GT:
                    opcode = Opcodes.IFGT;
                    break;
                default:
                    throw new TodoException("" + cJump.getTag());
            }

            final Label label = getOrCreateLabel(cJump.getTrueLabel());

            getMethodWriter().visitJumpInsn(
                    opcode,
                    label
            );
        }
    }

    private void visitCJumpNotImmediateLong(final CJump cJump) {
        final Label label = getOrCreateLabel(cJump.getTrueLabel());

        final var opcode = switch (cJump.getTag()) {
            case EQ -> Opcodes.IFEQ;
            case NE -> Opcodes.IFNE;
            case LE -> Opcodes.IFLE;
            case GE -> Opcodes.IFGE;
            case GT -> Opcodes.IFGT;
            default -> throw new TodoException("" + cJump.getTag());
        };

        getMethodWriter().visitInsn(Opcodes.LCMP);
        getMethodWriter().visitJumpInsn(
                opcode,
                label
        );
    }

    private void visitCJumpImmediateLong(final CJump cJump,
                                         final boolean compare) {
        final var tag = cJump.getTag();
        final Label label = getOrCreateLabel(cJump.getTrueLabel());

        switch (tag) {
            case EQ:
                getMethodWriter().visitJumpInsn(Opcodes.IFEQ, label);
                break;
            case NE:
                getMethodWriter().visitJumpInsn(Opcodes.IFNE, label);
                break;
            default:
                throw new TodoException(tag + " " + compare);
        }
    }

    private void visitCJumpNotImmediateInteger(final CJump cJump) {
        final Label label = getOrCreateLabel(cJump.getTrueLabel());

        final var opcode = switch (cJump.getTag()) {
            case EQ -> Opcodes.IF_ICMPNE;
            case NE -> Opcodes.IF_ICMPEQ;
            case LT -> Opcodes.IF_ICMPGE;
            case LE -> Opcodes.IF_ICMPGT;
            case GE -> Opcodes.IF_ICMPLT;
            case GT -> Opcodes.IF_ICMPLE;
            default -> throw new TodoException("" + cJump.getTag());
        };

        getMethodWriter().visitJumpInsn(
                opcode,
                label
        );
    }

    protected Label getOrCreateLabel(final ILabel label) {
        Objects.requireNonNull(label);
        return this.labelMap.computeIfAbsent(
                label.getName(),
                key -> new Label()
        );
    }

    private Type unboxType(final Type type) {
        if (type == null) {
            return null;
        } else if (type.getSort() != Type.OBJECT) {
            return type;
        } else {
            if (type.getClassName().equals(Constants.SHORT)) {
                return Type.SHORT_TYPE;
            } else if (type.getClassName().equals(Constants.INTEGER)) {
                return Type.INT_TYPE;
            } else if (type.getClassName().equals(Constants.BOOLEAN)) {
                return Type.BOOLEAN_TYPE;
            } else if (type.getClassName().equals(Constants.LONG)) {
                return Type.LONG_TYPE;
            } else if (type.getClassName().equals(Constants.FLOAT)) {
                return Type.FLOAT_TYPE;
            } else if (type.getClassName().equals(Constants.DOUBLE)) {
                return Type.DOUBLE_TYPE;
            } else if (type.getClassName().startsWith("java.lang.")) {
                throw new TodoException(type.getClassName());
            } else {
                return type;
            }
        }
    }

    private Type peek() {
        return getMethodWriter().peek();
    }

    private boolean isNullLiteral(final IExpression expression) {
        return switch (expression) {
            case Const c -> c.getValue() == null;
            case Mem mem -> isNullLiteral(mem.getExp());
            case BinOp binOp -> isNullLiteral(binOp.getRight());
            case null, default -> false;
        };
    }

    protected Const const16(final IExpression e) {
        if (e instanceof Const c) {
            if (c.getValue() instanceof Integer) {
                final int value = (int) c.getValue();
                if (value == (short) value) {
                    return c;
                }
            }
        } else if (e instanceof Mem mem) {
            return const16(mem.getExp());
        }
        return null;
    }
}
