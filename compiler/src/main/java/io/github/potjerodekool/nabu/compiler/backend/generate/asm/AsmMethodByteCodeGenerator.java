package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.StandardElementMetaData;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.annotation.AsmAnnotationGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.ExpCall;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.IrCleaner;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.MoveCall;
import io.github.potjerodekool.nabu.compiler.resolve.ClassUtils;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import org.objectweb.asm.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.util.*;

import static io.github.potjerodekool.nabu.compiler.resolve.ClassUtils.getInternalName;

public class AsmMethodByteCodeGenerator implements CodeVisitor<Frame> {

    static final IReferenceType STRING_TYPE = IReferenceType.createClassType(
            null,
            "java.lang.String",
            List.of()
    );

    private final ClassWriter classWriter;
    private final String owner;
    private final Textifier textifier = new Textifier();
    private final ToIType toIType = new ToIType();
    private final Map<String, Label> labelMap = new HashMap<>();

    private AsmWithStackMethodVisitor methodWriter;

    public AsmMethodByteCodeGenerator(final ClassWriter classWriter,
                                      final String owner) {
        this.classWriter = classWriter;
        this.owner = owner;
    }

    public Textifier getTextifier() {
        return textifier;
    }

    public void generate(final ExecutableElement methodSymbol) {
        final var access = AsmUtils.calculateAccess(methodSymbol.getModifiers());

        final var methodHeader = createMethodHeader(methodSymbol);
        final var methodDescriptor = createMethodDescriptor(methodHeader);
        final var methodSignature = createMethodSignature(methodHeader);

        MethodVisitor mv = classWriter.visitMethod(
                access,
                methodSymbol.getSimpleName(),
                methodDescriptor,
                methodSignature,
                null);

        mv = new TraceMethodVisitor(mv, this.textifier);
        this.methodWriter = new AsmWithStackMethodVisitor(Opcodes.ASM9, mv);

        final var frame = new Frame();

        if (!methodSymbol.isStatic() && !methodSymbol.isAbstract()) {
            frame.allocateLocal("this", IReferenceType.createClassType(null, owner, List.of()), false);
        }

        final var procFrag = methodSymbol.getMetaData(StandardElementMetaData.FRAG, ProcFrag.class);
        final List<IStatement> body;

        if (procFrag != null) {
            final var frag = IrCleaner.cleanUp(procFrag);
            body = frag.getBody();
        } else {
            final var clazz = (TypeElement) methodSymbol.getEnclosingElement();
            final var superTypes = (DeclaredType) clazz.getSuperclass();
            final var superClassName = superTypes.getTypeElement().getQualifiedName();

            body = List.of(new Move(
                    new Call(
                            InvocationType.SPECIAL,
                            new Name(superClassName),
                            new Name("super"),
                            IPrimitiveType.VOID,
                            List.of(),
                            List.of()
                    ),
                    new TempExpr(Frame.V0.getIndex(), frame)
            ));
        }

        visitParameters(methodHeader, frame);
        visitAnnotations(methodSymbol);
        visitBody(body, frame);
        visitLocalVariables(frame);

        methodWriter.visitMaxs(-1, -1);
        methodWriter.visitEnd();
    }

    private MethodHeader createMethodHeader(final ExecutableElement executableElement) {
        final var parameters = executableElement.getParameters().stream()
                .map(parameter -> {
                    final var name = parameter.getSimpleName();
                    final var type = parameter.asType();
                    return new Parameter(name, type.accept(toIType, null));
                })
                .toList();

        final IType returnType;

        if (executableElement.getKind() == ElementKind.CONSTRUCTOR) {
            returnType = IPrimitiveType.VOID;
        } else {
            returnType = executableElement.getReturnType()
                    .accept(toIType, null);
        }

        return new MethodHeader(parameters, returnType);
    }

    private String createMethodDescriptor(final MethodHeader methodHeader) {
        return AsmISignatureGenerator.INSTANCE.getMethodDescriptor(
                methodHeader.parameters().stream()
                        .map(Parameter::type)
                        .toList(),
                methodHeader.returnType()
        );
    }

    private String createMethodSignature(final MethodHeader methodHeader) {
        return AsmISignatureGenerator.INSTANCE.getMethodSignature(
                methodHeader.parameters().stream()
                        .map(Parameter::type)
                        .toList(),
                methodHeader.returnType()
        );
    }

    private void visitParameters(final MethodHeader methodHeader,
                                 final Frame frame) {


        methodHeader.parameters().forEach(parameter ->
                frame.allocateLocal(parameter.name(), parameter.type(), true)
        );
    }

    private void visitAnnotations(final ExecutableElement executableElement) {
        executableElement.getAnnotationMirrors().forEach(annotation ->
                AsmAnnotationGenerator.generate(annotation, methodWriter));
    }

    private void visitBody(final List<IStatement> body,
                           final Frame frame) {
        this.methodWriter.visitCode();
        body.forEach(stm -> stm.accept(this, frame));
    }

    private void visitLocalVariables(final Frame frame) {
        frame.getAllLocals().forEach(local -> {
            final var type = local.type();
            final var localName = local.name();
            final var descriptor = AsmISignatureGenerator.INSTANCE.getDescriptor(type);
            final var localIndex = local.index();

            final var start = local.getStart();
            final var end = local.getEnd();

            if (start != null && end != null) {
                final var fromLabel = getOrCreateLabel(start);
                final var toLabel = getOrCreateLabel(end);
                final var signature = hasTypeArgs(type)
                        ? AsmISignatureGenerator.INSTANCE.getSignature(local.type())
                        : null;

                methodWriter.visitLocalVariable(
                        localName,
                        descriptor,
                        signature,
                        fromLabel,
                        toLabel,
                        localIndex
                );
            }
        });
    }

    private boolean hasTypeArgs(final IType type) {
        if (type instanceof IReferenceType referenceType) {
            return referenceType.getTypeArguments() != null
                    && !referenceType.getTypeArguments().isEmpty();
        } else {
            return false;
        }
    }

    @Override
    public Temp visitUnknown(final INode node, final Frame parm) {
        return null;
    }

    @Override
    public Temp visitConst(final Const cnst,
                           final Frame frame) {
        final var value = cnst.getValue();

        if (value instanceof Boolean b) {
            methodWriter.visitInsn(b ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else if (value instanceof Long) {
            methodWriter.visitLdcInsn(value);
        } else if (isByte(value)) {
            final var intValue = toInt(value);
            pushByte(intValue);
        } else if (isShort(value)) {
            final var intValue = toInt(value);
            methodWriter.visitIntInsn(Opcodes.SIPUSH, intValue);
        } else {
            methodWriter.visitLdcInsn(value);
        }

        return new Temp(-1);
    }

    private void pushByte(final int intValue) {
        switch (intValue) {
            case -1 -> methodWriter.visitInsn(Opcodes.ICONST_M1);
            case 0 -> methodWriter.visitInsn(Opcodes.ICONST_0);
            case 1 -> methodWriter.visitInsn(Opcodes.ICONST_1);
            case 2 -> methodWriter.visitInsn(Opcodes.ICONST_2);
            case 3 -> methodWriter.visitInsn(Opcodes.ICONST_3);
            case 4 -> methodWriter.visitInsn(Opcodes.ICONST_4);
            case 5 -> methodWriter.visitInsn(Opcodes.ICONST_5);
            default -> methodWriter.visitIntInsn(Opcodes.BIPUSH, intValue);
        }
    }

    private int toInt(final Object value) {
        if (value instanceof Integer integer) {
            return integer;
        } else if (value instanceof Byte b) {
            return b.intValue();
        } else {
            throw new UnsupportedOperationException("Can't convert to int");
        }
    }

    private boolean isByte(final Object value) {
        if (value instanceof Byte) {
            return true;
        } else if (value instanceof Integer integer) {
            return integer >= Byte.MIN_VALUE
                    && integer <= Byte.MAX_VALUE;
        } else {
            return false;
        }
    }

    private boolean isShort(final Object value) {
        if (value instanceof Short) {
            return true;
        } else if (value instanceof Integer integer) {
            return integer >= Short.MIN_VALUE
                    && integer <= Short.MAX_VALUE;
        } else {
            return false;
        }
    }

    @Override
    public void visitCJump(final CJump cJump,
                           final Frame frame) {
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
        final var left = cJump.getLeft();
        final var right = cJump.getRight();
        final Label falseLabel = getOrCreateLabel(cJump.getFalseLabel());

        if (left instanceof Const) {
            throw new TodoException();
        }

        if (left instanceof BinOp binOp) {
            left.accept(this, frame);

            final var tag = binOp.getTag();
            final var type = getBigestType(methodWriter.peek2());

            if (type == Type.BYTE_TYPE
                    || type == Type.SHORT_TYPE
                    || type == Type.INT_TYPE) {

                if (tag == Tag.GT) {
                    methodWriter.visitJumpInsn(Opcodes.IF_ICMPLE, falseLabel);
                } else if (tag == Tag.LT) {
                    methodWriter.visitJumpInsn(Opcodes.IF_ICMPGE, falseLabel);
                } else if (tag == Tag.LE) {
                    methodWriter.visitJumpInsn(Opcodes.IF_ICMPGT, falseLabel);
                } else {
                    throw new TodoException();
                }
                return;
            } else if (type == Type.LONG_TYPE) {
                if (tag == Tag.GT) {
                    methodWriter.visitInsn(Opcodes.LCMP);
                    methodWriter.visitJumpInsn(Opcodes.IFLE, falseLabel);
                } else if (tag == Tag.LT) {
                    methodWriter.visitInsn(Opcodes.LCMP);
                    methodWriter.visitJumpInsn(Opcodes.IFGE, falseLabel);
                } else {
                    throw new TodoException();
                }
                return;
            }
        }

        if (right instanceof Const c) {
            final var tag = cJump.getTag();

            left.accept(this, frame);

            final var value = (Integer) c.getValue();

            if (tag == Tag.EQ) {
                if (value == 1) {
                    methodWriter.visitJumpInsn(Opcodes.IFEQ, falseLabel);
                    return;
                }
            } else if (tag == Tag.NE) {
                if (value == 1) {
                    methodWriter.visitJumpInsn(Opcodes.IFNE, falseLabel);
                    return;
                }
            }
        }

        throw new TodoException();
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

            methodWriter.visitJumpInsn(
                    opcode,
                    label
            );
        }
    }

    private boolean isNullLiteral(final IExpression expression) {
        return switch (expression) {
            case Const c -> c.getValue() == null;
            case Mem mem -> isNullLiteral(mem.getExp());
            case BinOp binOp -> isNullLiteral(binOp.getRight());
            case null, default -> false;
        };
    }

    private Type peek() {
        return methodWriter.peek();
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

        methodWriter.visitInsn(Opcodes.LCMP);
        methodWriter.visitJumpInsn(
                opcode,
                label
        );
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

        methodWriter.visitJumpInsn(
                opcode,
                label
        );
    }

    private Type getBigestType(final Type[] typeArray) {
        Type biggest = typeArray[0];

        for (int i = 1; i < typeArray.length; i++) {
            final var type = typeArray[i];
            if (type.getSize() > biggest.getSize()) {
                biggest = type;
            }
        }

        return biggest;
    }

    @Override
    public void visitJump(final Jump jump,
                          final Frame frame) {
        final Label label = getOrCreateLabel(jump.getJumpTargets().getFirst());
        this.methodWriter.visitJumpInsn(Opcodes.GOTO, label);
    }

    @Override
    public Temp visitName(final Name name,
                          final Frame frame) {
        throw new TodoException("");
    }

    private void visitLine(final INode node) {
        if (node.getLineNumber() < 0) {
            return;
        }

        final var lastLabel = methodWriter.getLastLabel();

        methodWriter.visitLineNumber(
                node.getLineNumber(),
                lastLabel
        );
    }

    @Override
    public void visitExpressionStatement(final IExpressionStatement expressionStatement,
                                         final Frame frame) {
        visitLine(expressionStatement);
        expressionStatement.getExp().accept(this, frame);
    }

    @Override
    public void visitMove(final Move move,
                          final Frame frame) {
        visitLine(move);
        final var src = move.getSrc();
        final var dst = move.getDst();

        if (src != null && dst != null) {
            src.accept(this, frame);

            if (dst instanceof TempExpr tempExpr) {
                final var index = tempExpr.getTemp().getIndex();
                if (index == Frame.V0.getIndex()) {
                    final var top = methodWriter.peek();
                    final int opcode = resolveReturnOpcode(top);
                    methodWriter.visitInsn(opcode);
                } else {
                    store(index, frame);
                }
            } else {
                dst.accept(this, frame);
            }
        } else {
            methodWriter.visitInsn(Opcodes.RETURN);
        }
    }

    private void store(final int index,
                       final Frame frame) {
        final var top = methodWriter.peek();

        final int opcode = switch (top.getSort()) {
            case Type.OBJECT -> Opcodes.ASTORE;
            case Type.SHORT,
                 Type.INT,
                 Type.BYTE,
                 Type.BOOLEAN,
                 Type.CHAR -> Opcodes.ISTORE;
            case Type.LONG -> Opcodes.LSTORE;
            case Type.FLOAT -> Opcodes.FSTORE;
            case Type.DOUBLE -> Opcodes.DSTORE;
            default -> throw new UnsupportedOperationException();
        };

        methodWriter.visitVarInsn(opcode, index);
        visitLabel(new ILabel(), frame);
    }

    @Override
    public void visitVariableDeclaratorStatement(final IVariableDeclaratorStatement variableDeclaratorStatement,
                                                 final Frame frame) {
        visitLine(variableDeclaratorStatement);

        variableDeclaratorStatement.getInitExpression().accept(this, frame);

        final var index = frame.allocateLocal(
                variableDeclaratorStatement.getSymbol().getSimpleName(),
                variableDeclaratorStatement.getType(),
                false
        );
        store(index, frame);
    }

    @Override
    public Temp visitCastExpression(final CastExpression castExpression, final Frame param) {
        castExpression.getExpression().accept(this, param);
        final var internalName = getInternalName(castExpression.getName());
        methodWriter.visitTypeInsn(Opcodes.CHECKCAST, internalName);
        return null;
    }

    private int resolveReturnOpcode(final Type type) {
        final int opcode;

        if (type == null) {
            opcode = Opcodes.RETURN;
        } else {
            opcode = switch (type.getSort()) {
                case Type.OBJECT -> Opcodes.ARETURN;
                case Type.SHORT,
                     Type.INT,
                     Type.BYTE,
                     Type.BOOLEAN,
                     Type.CHAR -> Opcodes.IRETURN;
                case Type.LONG -> Opcodes.LRETURN;
                case Type.FLOAT -> Opcodes.FRETURN;
                case Type.DOUBLE -> Opcodes.DRETURN;
                default -> throw new UnsupportedOperationException();
            };
        }

        return opcode;
    }

    @Override
    public Temp visitMem(final Mem mem,
                         final Frame frame) {
        final var tempExp = ((TempExpr) mem.getExp());

        final var temp = tempExp.getTemp();
        final var index = temp.getIndex();
        final var type = tempExp.getFrame().get(index).type();

        if (type instanceof IReferenceType) {
            methodWriter.visitVarInsn(Opcodes.ALOAD, index);
        } else {
            throw new TodoException("");
        }

        return temp;
    }

    @Override
    public Temp visitTemp(final TempExpr tempExpr,
                          final Frame frame) {
        final var temp = tempExpr.getTemp();
        final var index = temp.getIndex();

        if (index > -1) {
            final var type = tempExpr.getFrame().get(index).type();
            Objects.requireNonNull(type);

            final var opcode = switch (type.getKind()) {
                case CLASS, INTERFACE -> Opcodes.ALOAD;
                case INT, BYTE, CHAR, BOOLEAN, SHORT -> Opcodes.ILOAD;
                case LONG -> Opcodes.LLOAD;
                case FLOAT -> Opcodes.FLOAD;
                case DOUBLE -> Opcodes.DLOAD;
                case ARRAY -> Opcodes.AALOAD;
                default -> throw new UnsupportedOperationException();
            };

            methodWriter.visitVarInsn(opcode, index);
        }
        return temp;
    }

    @Override
    public Temp visitCall(final Call call,
                          final Frame frame) {
        if (call.getInvocationType() == InvocationType.DYNAMIC) {
            return visitDynamicCall(call, frame);
        } else {
            return visitDefaultCall(call, frame);
        }
    }

    private Temp visitDefaultCall(final Call call,
                                  final Frame frame) {
        final var opcode = invocationTypeToOpcode(call.getInvocationType());
        final var owner = call.getOwner() != null ?
                ClassUtils.getInternalName(call.getOwner().getLabel().getName())
                : null;
        var name = call.getFunction().getLabel().getName();

        if ("super".equals(name) || "this".equals(name)) {
            name = "<init>";
        }

        final var methodDescriptor = AsmISignatureGenerator.INSTANCE.getMethodDescriptor(
                call.getParamTypes(),
                call.getReturnType()
        );

        call.getArgs().forEach(arg -> {
            if (arg instanceof Unop unop
                    && unop.getTag() == Tag.NOT) {
                unop.getExpression().accept(this, frame);
                final var trueLabel = new Label();
                methodWriter.visitJumpInsn(
                        Opcodes.IFNE,
                        trueLabel
                );

                methodWriter.visitInsn(
                        Opcodes.ICONST_1
                );
                final var falseLabel = new Label();
                methodWriter.visitJumpInsn(
                        Opcodes.GOTO,
                        falseLabel
                );

                methodWriter.visitLabel(trueLabel);
                methodWriter.visitInsn(Opcodes.ICONST_0);
                methodWriter.visitLabel(falseLabel);
            } else {
                arg.accept(this, frame);
            }
        });

        methodWriter.visitMethodInsn(
                opcode,
                owner,
                name,
                methodDescriptor,
                opcode == Opcodes.INVOKEINTERFACE
        );

        return null;
    }

    private Temp visitDynamicCall(final Call dynamicCall,
                                  final Frame frame) {
        dynamicCall.getArgs().forEach(arg -> arg.accept(this, frame));
        final var descriptor = AsmISignatureGenerator.INSTANCE.getMethodDescriptor(dynamicCall.getParamTypes(), dynamicCall.getReturnType());
        final var invokeDynamicDescriptor =
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

        final var lambdaFunctionCall = dynamicCall.getLambdaFunctionCall();

        final var lambdaFunctionDescriptor = AsmISignatureGenerator.INSTANCE.getMethodDescriptor(
                lambdaFunctionCall.getParamTypes(),
                lambdaFunctionCall.getReturnType()
        );

        final var lambdaCall = dynamicCall.getLambdaCall();
        final var lambdaMethodName = lambdaCall.getFunction().getLabel().getName();

        final var lambdaDescriptor = AsmISignatureGenerator.INSTANCE.getMethodDescriptor(
                lambdaCall.getParamTypes(),
                lambdaCall.getReturnType()
        );

        methodWriter.visitInvokeDynamicInsn(
                dynamicCall.getFunction().toString(),
                descriptor,
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        invokeDynamicDescriptor,
                        false
                ), Type.getType(lambdaFunctionDescriptor),
                new Handle(Opcodes.H_INVOKESTATIC,
                        this.owner,
                        lambdaMethodName, lambdaDescriptor,
                        false), Type.getType(lambdaFunctionDescriptor));

        return null;
    }

    private int invocationTypeToOpcode(final InvocationType invocationType) {
        return switch (invocationType) {
            case SPECIAL -> Opcodes.INVOKESPECIAL;
            case VIRTUAL -> Opcodes.INVOKEVIRTUAL;
            case INTERFACE -> Opcodes.INVOKEINTERFACE;
            case STATIC -> Opcodes.INVOKESTATIC;
            case DYNAMIC -> Opcodes.INVOKEDYNAMIC;
        };
    }

    @Override
    public Temp visitEseq(final Eseq eseq, final Frame frame) {
        eseq.getStm().accept(this, frame);
        return eseq.getExp().accept(this, frame);
    }

    @Override
    public void visitSeq(final Seq seq, final Frame frame) {
        seq.getLeft().accept(this, frame);
        seq.getRight().accept(this, frame);
    }

    @Override
    public Temp visitExpList(final ExpList expList, final Frame param) {
        expList.getList().forEach(exp -> exp.accept(this, param));
        return null;
    }

    @Override
    public Temp visitBinop(final BinOp binOp, final Frame frame) {
        if (isStringConcat(binOp)) {
            return generateStringConcat(binOp, frame);
        }

        visitLine(binOp);

        final var tag = binOp.getTag();

        if (tag == Tag.ADD_ASSIGN) {
            final var left = (TempExpr) binOp.getLeft();
            final var index = left.getTemp().getIndex();
            final var right = binOp.getRight();

            if (right instanceof Const c) {
                final var increment = (Integer) c.getValue();
                methodWriter.visitIincInsn(index, increment);
            } else {
                left.accept(this, frame);
                right.accept(this, frame);
                methodWriter.visitInsn(Opcodes.IADD);
                store(index, frame);
            }
        } else if (tag == Tag.ASSIGN) {
            final var left = (IFieldAccess) binOp.getLeft();

            if (!left.isStatic()) {
                methodWriter.visitVarInsn(Opcodes.ALOAD, 0);
            }

            binOp.getRight().accept(this, frame);
            final var owner = ClassUtils.getInternalName(left.getOwner());
            final var name = left.getName();
            final var fieldType = left.getFieldType();
            final var descriptor = AsmISignatureGenerator.INSTANCE.getDescriptor(fieldType);
            final var opcode = left.isStatic()
                    ? Opcodes.PUTSTATIC
                    : Opcodes.PUTFIELD;
            methodWriter.visitFieldInsn(
                    opcode,
                    owner,
                    name,
                    descriptor
            );
        } else {
            binOp.getLeft().accept(this, frame);
            binOp.getRight().accept(this, frame);
        }

        return new Temp(-1);
    }

    private boolean isStringConcat(final BinOp binOp) {
        if (binOp.getTag() == Tag.ADD) {
            if (isStringConstant(binOp.getLeft())
                || binOp.getLeft() instanceof BinOp left && isStringConstant(left)) {
                return true;
            } else {
                return isStringConstant(binOp.getRight())
                        || binOp.getRight() instanceof BinOp right && isStringConstant(right);
            }
        }

        return false;
    }

    private boolean isStringConstant(final IExpression expression) {
        return expression instanceof Const c
                && STRING_TYPE.equals(c.getType());
    }


    @Override
    public void visitLabelStatement(final ILabelStatement labelStatement, final Frame frame) {
        visitLabel(labelStatement.getLabel(), frame);
        visitLine(labelStatement);
    }

    private void visitLabel(final ILabel label,
                            final Frame frame) {
        final var asmLabel = getOrCreateLabel(label);
        methodWriter.visitLabel(asmLabel);

        frame.getLocals().forEach(local -> {
            if (local.getStart() == null) {
                local.setStart(label);
            }
            local.setEnd(label);
        });
    }

    @Override
    public void visitMoveCall(final MoveCall moveCall, final Frame param) {
        throw new TodoException();
    }

    @Override
    public void visitExpCall(final ExpCall expCall, final Frame param) {
        throw new TodoException();
    }

    @Override
    public void visitThrowStatement(final IThrowStatement throwStatement, final Frame param) {
        throw new TodoException();
    }

    @Override
    public Temp visitUnop(final Unop unop, final Frame param) {
        final var tag = unop.getTag();

        if (tag == Tag.POST_INC) {
            final var temp = (TempExpr) unop.getExpression();
            methodWriter.visitIincInsn(temp.getTemp().getIndex(), 1);
        } else if (tag == Tag.POST_DEC) {
            final var temp = (TempExpr) unop.getExpression();
            methodWriter.visitIincInsn(temp.getTemp().getIndex(), -1);
        } else {
            throw new TodoException();
        }

        return new Temp();
    }

    @Override
    public Temp visitFieldAccess(final IFieldAccess fieldAccess, final Frame frame) {
        if ("class".equals(fieldAccess.getName())) {
            final var type = Type.getType(ClassUtils.getClassDescriptor(fieldAccess.getOwner()));
            methodWriter.visitLdcInsn(type);
        } else {
            final var opcode = fieldAccess.isStatic()
                    ? Opcodes.GETSTATIC
                    : Opcodes.GETFIELD;

            final var owner = ClassUtils.getInternalName(fieldAccess.getOwner());
            final var descriptor = AsmISignatureGenerator.INSTANCE.getDescriptor(fieldAccess.getFieldType());

            methodWriter.visitFieldInsn(
                    opcode,
                    owner,
                    fieldAccess.getName(),
                    descriptor
            );
        }

        return new Temp();
    }

    @Override
    public void visitBlockStatement(final IBlockStatement blockStatement, final Frame frame) {
        final var subFrame = frame.subFrame();

        visitLabel(new ILabel(), subFrame);
        blockStatement.getStatements().forEach(stm -> stm.accept(this, subFrame));
        visitLabel(new ILabel(), subFrame);
    }

    private Label getOrCreateLabel(final ILabel label) {
        Objects.requireNonNull(label);
        return this.labelMap.computeIfAbsent(
                label.getName(),
                key -> new Label()
        );
    }

    private Const const16(final IExpression e) {
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

    public Temp generateStringConcat(final BinOp binOp, final Frame frame) {
        final var template = new StringBuilder();
        generateTemplateForStringConcat(binOp, template);
        final var descriptor = new StringBuilder();
        descriptor.append("(");
        generateDescriptorForStringConcat(binOp, descriptor);
        descriptor.append(")Ljava/lang/String;");

        final var visitor = new StringConcatCodeVisitor(this);

        binOp.accept(visitor, frame);

        //TODO generate descriptor from types.
        //final var descriptor = "(Ljava/lang/String;I)Ljava/lang/String;";

        final var handle = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false);

        methodWriter.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                descriptor.toString(),
                handle,
                template.toString()
        );

        return new Temp();
    }

    private void generateTemplateForStringConcat(final BinOp binOp,
                                                 final StringBuilder template) {
        final var left = binOp.getLeft();
        final var right = binOp.getRight();

        if (isStringConstant(left)) {
            final var c = (Const) left;
            template.append(c.getValue());
        } else if (left instanceof BinOp leftBinOp) {
            generateTemplateForStringConcat(leftBinOp, template);
        } else {
            template.append("\u0001");
        }

        if (isStringConstant(right)) {
            final var c = (Const) right;
            template.append(c.getValue());
        } else if (right instanceof BinOp rightBinOp) {
            generateTemplateForStringConcat(rightBinOp, template);
        } else {
            template.append("\u0001");
        }
    }

    private void generateDescriptorForStringConcat(final BinOp binOp,
                                                   final StringBuilder stringBuilder) {
        stringBuilder.append(getDescriptor(binOp.getLeft()));
        stringBuilder.append(getDescriptor(binOp.getRight()));
    }

    private String getDescriptor(final IExpression expression) {
        if (expression instanceof Const c) {
            final var type = c.getType();

            if (STRING_TYPE.equals(type)) {
                return "";
            }

            return AsmISignatureGenerator.toAsmType(type).getDescriptor();
        } else if (expression instanceof Call call) {
            final var type = call.getReturnType();
            return AsmISignatureGenerator.toAsmType(type).getDescriptor();
        } else {
            throw new TodoException();
        }
    }

}

record MethodHeader(List<Parameter> parameters,
                    IType returnType) {
}

record Parameter(String name, IType type) {
}

class StringConcatCodeVisitor implements CodeVisitor<Frame> {

    private final AsmMethodByteCodeGenerator visitor;

    StringConcatCodeVisitor(final AsmMethodByteCodeGenerator visitor) {
        this.visitor = visitor;
    }

    @Override
    public Temp visitUnknown(final INode node, final Frame frame) {
        throw new TodoException(node.getClass().getName());
    }

    @Override
    public Temp visitBinop(final BinOp binOp, final Frame frame) {
        binOp.getLeft().accept(this, frame);
        binOp.getRight().accept(this, frame);
        return null;
    }

    @Override
    public Temp visitConst(final Const cnst, final Frame frame) {
        if (!AsmMethodByteCodeGenerator.STRING_TYPE.equals(cnst.getType())) {
            return cnst.accept(visitor, frame);
        }
        return null;
    }

    @Override
    public Temp visitCall(final Call call, final Frame frame) {
        return call.accept(visitor, frame);
    }
}