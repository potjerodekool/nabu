package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.InvocationType;
import io.github.potjerodekool.nabu.compiler.backend.ir.Param;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.ExpCall;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.MoveCall;
import io.github.potjerodekool.nabu.compiler.resolve.ClassUtils;
import org.objectweb.asm.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.util.*;

import static io.github.potjerodekool.nabu.compiler.backend.generate.AsmUtils.*;

public class MethodGenerator implements CodeVisitor<Frame> {

    private final ClassWriter classWriter;
    private final String owner;
    private WithStackMethodVisitor methodWriter;
    private final Map<String,Label> labelMap = new HashMap<>();
    private final Textifier textifier = new Textifier();

    public MethodGenerator(final ClassWriter classWriter,
                           final String owner) {
        this.classWriter = classWriter;
        this.owner = owner;
    }

    public Textifier getTextifier() {
        return textifier;
    }

    public void generate(final MethodSymbol methodSymbol) {
        final var procFrag = methodSymbol.getFrag();

        final var access = calculateAccess(procFrag.getFlags());

        final var paramTypes = procFrag.getParams().stream()
                .map(Param::type)
                .toList();

        final var methodDescriptor = getMethodDescriptor(
                paramTypes,
                procFrag.getReturnType()
        );

        final var methodSignature = getMethodSignature(
                paramTypes,
                procFrag.getReturnType()
        );

        MethodVisitor mv = classWriter.visitMethod(
                access,
                procFrag.getName(),
                methodDescriptor,
                methodSignature,
                null);

        final var trace = true;
        if (trace) {
            mv = new TraceMethodVisitor(mv, this.textifier);
        }

        methodWriter = new WithStackMethodVisitor(Opcodes.ASM9, mv);
        methodWriter.visitCode();

        final var startLabel = new Label();
        methodWriter.visitLabel(startLabel);

        procFrag.getBody().forEach(stm -> stm.accept(this, procFrag.getFrame()));

        final var endLabel = new Label();
        methodWriter.visitLabel(endLabel);
        final var frame = procFrag.getFrame();

        final var labeledLocals = methodWriter.getLabeledLocals();

        frame.getLocals().forEach(local -> {
            final var localName = local.name();
            final var localType = toAsmType(local.type());
            final var localIndex = local.index();
            final var labeledLocal = labeledLocals.get(localIndex);

            Label fromLabel = startLabel;
            Label toLabel = endLabel;

            if (labeledLocal != null) {
                fromLabel = labeledLocal.getStart();
                toLabel = labeledLocal.getEnd();
            }

            methodWriter.visitLocalVariable(
                    localName,
                    localType.getDescriptor(),
                    null,
                    fromLabel,
                    toLabel,
                    localIndex
            );
        });

        methodWriter.visitMaxs(-1, -1);

        methodWriter.visitEnd();
    }

    private int calculateAccess(final int flags) {
        var access = addFlag(flags, Flags.PUBLIC, Opcodes.ACC_PUBLIC);
        access += addFlag(flags, Flags.PRIVATE, Opcodes.ACC_PRIVATE);
        access += addFlag(flags, Flags.STATIC, Opcodes.ACC_STATIC);
        access += addFlag(flags, Flags.SYNTHENTIC, Opcodes.ACC_SYNTHETIC);
        return access;
    }

    private int addFlag(final int flags,
                        final int flag,
                        final int value) {
        if ((flags & flag) == flag) {
            return value;
        } else {
            return 0;
        }
    }

    @Override
    public Temp visitConst(final Const aConst,
                           final Frame frame) {
        final var value = aConst.getValue();

        if (value instanceof Boolean b) {
            methodWriter.visitInsn(b ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else {
            methodWriter.visitLdcInsn(value);
        }

        return new Temp(-1);
    }

    @Override
    public void visitCJump(final CJump cJump, final Frame frame) {
        throw new TodoException("");
    }

    @Override
    public void visitJump(final Jump jump, final Frame frame) {
        throw new TodoException("");
    }

    @Override
    public Temp visitName(final Name name,
                          final Frame frame) {
        throw new TodoException("");
    }

    private void visitLine(final IStatement statement) {
        if (statement.getLineNumber() < 0) {
            return;
            //throw new IllegalStateException();
        }

        final var lastLabel = methodWriter.getLastLabel();

        methodWriter.visitLineNumber(
                statement.getLineNumber(),
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
                if (index == frame.rv().getIndex()) {
                    final var top = methodWriter.peek();
                    final int opcode = resolveReturnOpcode(top);
                    methodWriter.visitInsn(opcode);
                } else {
                    final var top = methodWriter.peek();
                    final int opcode = switch (top.getSort()) {
                        case Type.OBJECT -> Opcodes.ASTORE;
                        case Type.SHORT,
                             Type.INT,
                             Type.BYTE,
                             Type.BOOLEAN,
                             Type.CHAR -> Opcodes.ISTORE;
                        case Type.LONG ->  Opcodes.LSTORE;
                        case Type.FLOAT -> Opcodes.FSTORE;
                        case Type.DOUBLE -> Opcodes.DSTORE;
                        default -> throw new UnsupportedOperationException();
                    };

                    methodWriter.visitVarInsn(opcode,index);
                }
            } else {
                dst.accept(this, frame);
            }
        } else {
            methodWriter.visitInsn(Opcodes.RETURN);
        }
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
                case Type.LONG ->  Opcodes.LRETURN;
                case Type.FLOAT -> Opcodes.FRETURN;
                case Type.DOUBLE -> Opcodes.DRETURN;
                default -> throw new UnsupportedOperationException();
            };
        }

        return opcode;
    }

    @Override
    public Temp visitMem(final Mem mem, final Frame frame) {
        final var temp = ((TempExpr) mem.getExp()).getTemp();
        final var index = temp.getIndex();
        final var type = frame.get(index).type();

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
            final var type = frame.get(index).type();
            Objects.requireNonNull(type);

            final var opcode = switch (type.getKind()) {
                case DECLARED -> Opcodes.ALOAD;
                case INT, BYTE, CHAR, BOOLEAN, SHORT -> Opcodes.ILOAD;
                case LONG -> Opcodes.LLOAD;
                case FLOAT ->  Opcodes.FLOAD;
                case DOUBLE -> Opcodes.DLOAD;
                case ARRAY -> Opcodes.AALOAD;
                default -> throw new UnsupportedOperationException();
            };

            methodWriter.visitVarInsn(opcode, index);
        }
        return temp;
    }

    @Override
    public Temp visitCall(final Call call, final Frame frame) {
        if (call instanceof DefaultCall defaultCall) {
            return visitDefaultCall(defaultCall, frame);
        } else if (call instanceof DynamicCall dynamicCall) {
            return visitDynamicCall(dynamicCall, frame);
        } else {
            throw new TodoException();
        }
    }

    private Temp visitDefaultCall(final DefaultCall call,
                                  final Frame frame) {
        final var opcode = invocationTypeToOpcode(call.getInvocationType());
        final var owner = call.getOwner() != null ?
                ClassUtils.toInternalName(call.getOwner().getLabel().getName())
                : null;
        var name = call.getFunction().getLabel().getName();

        if ("super".equals(name) || "this".equals(name)) {
            name = "<init>";
        }

        final var methodDescriptor = getMethodDescriptor(
                call.getParamTypes(),
                call.getReturnType()
        );

        call.getArgs().forEach(arg -> {
            if (arg instanceof Unop unop
                && unop.getOperator() == Unop.Oper.NOT) {
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

    private Temp visitDynamicCall(final DynamicCall dynamicCall, final Frame frame) {
        dynamicCall.getArgs().forEach(arg -> arg.accept(this, frame));
        final var descriptor = getMethodDescriptor(dynamicCall.getParamTypes(), dynamicCall.getReturnType());
        final var invokeDynamicDescriptor =
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

        final var lambdaFunctionCall = dynamicCall.getLambdaFunctionCall();

        final var lambdaFunctionDescriptor = getMethodDescriptor(
                lambdaFunctionCall.getParamTypes(),
                lambdaFunctionCall.getReturnType()
        );

        final var lambdaCall = dynamicCall.getLambdaCall();
        final var lambdaMethodName = lambdaCall.getFunction().getLabel().getName();

        final var lambdaDescriptor = getMethodDescriptor(
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
        binOp.getLeft().accept(this, frame);
        binOp.getRight().accept(this, frame);
        throw new TodoException();
    }

    @Override
    public void visitLabelStatement(final ILabelStatement labelStatement, final Frame param) {
        final Label l = getOrCreateLabel(labelStatement.getLabel());
        methodWriter.visitLabel(l);
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
        throw new TodoException();
    }

    private Label getOrCreateLabel(final ILabel label) {
        return this.labelMap.computeIfAbsent(
                label.getName(),
                key -> new Label()
        );
    }
}

class WithStackMethodVisitor extends MethodVisitor {

    private final Type OBJECT_TYPE = Type.getType(Object.class);
    private final List<Type> stack = new ArrayList<>();
    private Label lastLabel;
    private final Map<Integer, LabeledLocal> locals = new HashMap<>();
    private final Set<LabeledLocal> activeLocals = new HashSet<>();

    protected WithStackMethodVisitor(final int api,
                                     final MethodVisitor methodVisitor) {
        super(api, methodVisitor);
    }

    public Map<Integer, LabeledLocal> getLabeledLocals() {
        return locals;
    }

    @Override
    public void visitLabel(final Label label) {
        super.visitLabel(label);
        this.lastLabel = label;

        if (!activeLocals.isEmpty()) {
            activeLocals.forEach(local -> local.setEnd(label));
            activeLocals.clear();
        }
    }

    @Override
    public void visitVarInsn(final int opcode, final int varIndex) {
        super.visitVarInsn(opcode, varIndex);

        switch (opcode) {
            case Opcodes.ILOAD -> push(Type.INT_TYPE);
            case Opcodes.FLOAD -> push(Type.FLOAT_TYPE);
            case Opcodes.DLOAD -> push(Type.DOUBLE_TYPE);
            case Opcodes.ALOAD -> push(OBJECT_TYPE);
            case
                 Opcodes.ISTORE,
                 Opcodes.LSTORE,
                 Opcodes.FSTORE,
                 Opcodes.DSTORE,
                 Opcodes.ASTORE -> pop();
        }

        final var local = this.locals.computeIfAbsent(varIndex, (key) -> new LabeledLocal(key, lastLabel));
        activeLocals.add(local);
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
        push(Type.getType(value.getClass()));
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
        return this.stack.isEmpty()
                ? null
                : this.stack.getLast();
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
        }
    }

    public Label getLastLabel() {
        return lastLabel;
    }
}

class LabeledLocal {
    private final int index;
    private final Label start;
    private Label end;

    LabeledLocal(final int index,
          final Label start) {
        this.index = index;
        this.start = start;
    }

    public int getIndex() {
        return index;
    }

    public Label getStart() {
        return start;
    }

    public Label getEnd() {
        return end;
    }

    public void setEnd(final Label end) {
        this.end = end;
    }

}