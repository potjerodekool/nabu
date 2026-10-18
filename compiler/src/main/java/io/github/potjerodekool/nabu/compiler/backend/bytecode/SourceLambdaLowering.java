package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Lambdas and method references from source for a closed-world backend.
 *
 * <p>The IR of a lambda is a {@link IRValue.FunctionRef} with its functional
 * interface and SAM method; the JVM backend turns it into an
 * {@code invokedynamic} with {@code LambdaMetafactory}. The LLVM backend has
 * no such runtime, so here every such reference becomes what the metafactory
 * would produce: a class that implements the interface
 * ({@link LambdaClassSynthesizer}, as for lambdas in JDK bytecode) and, at the
 * place of the reference, {@code new} of that class with the captured values,
 * loaded from the slots of the captured variables.</p>
 */
public final class SourceLambdaLowering {

    private SourceLambdaLowering() {
    }

    /**
     * Rewrites the function references in {@code modules} in place.
     *
     * @return the synthesized lambda classes, to compile with the modules
     */
    public static List<IRModule> lower(final List<IRModule> modules) {
        final List<IRModule> synthesized = new ArrayList<>();
        int counter = 0;
        for (final IRModule module : modules) {
            for (final IRFunction function : module.functions()) {
                if (function.isExternal()) {
                    continue;
                }
                for (final IRBasicBlock block : function.blocks()) {
                    for (int i = 0; i < block.instructions().size(); i++) {
                        final IRInstruction instruction = block.instructions().get(i);
                        final List<IRInstruction> before = new ArrayList<>();
                        final int base = counter;
                        final int[] made = {0};
                        final IRInstruction replaced = mapOperands(instruction, value -> {
                            if (value instanceof IRValue.FunctionRef ref && isSamConversion(ref)) {
                                return instantiate(ref, module, function, before, synthesized,
                                        base + made[0]++, instruction.location());
                            }
                            return value;
                        });
                        counter += made[0];
                        if (replaced != instruction) {
                            for (int k = 0; k < before.size(); k++) {
                                block.add(i + k, before.get(k));
                            }
                            i += before.size();
                            block.set(i, replaced);
                        }
                    }
                }
            }
        }
        return synthesized;
    }

    private static boolean isSamConversion(final IRValue.FunctionRef ref) {
        return ref.targetInterface() instanceof IRType.Ptr ptr && ptr.jvmDescriptor() != null
                && ref.samMethodName() != null && ref.samDescriptor() != null;
    }

    /** The instruction with every operand that can hold a function reference mapped. */
    private static IRInstruction mapOperands(final IRInstruction instruction,
                                             final UnaryOperator<IRValue> map) {
        return switch (instruction) {
            case IRInstruction.Store store -> {
                final var value = map.apply(store.value());
                yield value == store.value() ? store
                        : new IRInstruction.Store(store.ptr(), value, store.location());
            }
            case IRInstruction.Call call -> {
                final var args = mapAll(call.args(), map);
                yield args == call.args() ? call
                        : new IRInstruction.Call(call.callKind(), call.returnType(), call.paramTypes(),
                        call.result(), call.function(), args, call.location());
            }
            case IRInstruction.IndirectCall call -> {
                final var args = mapAll(call.args(), map);
                yield args == call.args() ? call
                        : new IRInstruction.IndirectCall(call.result(), call.callee(), call.fnType(),
                        args, call.location());
            }
            case IRInstruction.Return ret -> {
                if (ret.value() == null) {
                    yield ret;
                }
                final var value = map.apply(ret.value());
                yield value == ret.value() ? ret : new IRInstruction.Return(value, ret.location());
            }
            case IRInstruction.Move move -> {
                final var value = map.apply(move.value());
                yield value == move.value() ? move
                        : new IRInstruction.Move(move.result(), value, move.location());
            }
            case IRInstruction.Cast cast -> {
                final var value = map.apply(cast.source());
                yield value == cast.source() ? cast
                        : new IRInstruction.Cast(cast.result(), value, cast.targetType(), cast.location());
            }
            default -> instruction;
        };
    }

    private static List<IRValue> mapAll(final List<IRValue> values, final UnaryOperator<IRValue> map) {
        List<IRValue> result = null;
        for (int i = 0; i < values.size(); i++) {
            final var mapped = map.apply(values.get(i));
            if (mapped != values.get(i)) {
                if (result == null) {
                    result = new ArrayList<>(values);
                }
                result.set(i, mapped);
            }
        }
        return result != null ? result : values;
    }

    /**
     * Synthesizes the lambda class for {@code ref} and emits, into
     * {@code before}, its allocation and constructor call with the captured
     * values. Returns the new object as the functional interface.
     */
    private static IRValue instantiate(final IRValue.FunctionRef ref,
                                       final IRModule module,
                                       final IRFunction function,
                                       final List<IRInstruction> before,
                                       final List<IRModule> synthesized,
                                       final int index,
                                       final SourceLocation location) {
        final String interfaceDescriptor = ((IRType.Ptr) ref.targetInterface()).jvmDescriptor();
        final Type samType = Type.getMethodType(ref.samDescriptor());
        final List<IRType> implParams = ref.fnType().paramTypes();
        final int capturedCount = Math.max(0, implParams.size() - samType.getArgumentTypes().length);

        final int dot = ref.name().lastIndexOf('.');
        final String ownerQualified = dot >= 0 ? ref.name().substring(0, dot) : module.name;
        final String implName = ref.name().substring(dot + 1);
        final String implDescriptor = descriptor(implParams, ref.fnType().returnType());

        final StringBuilder factoryDescriptor = new StringBuilder("(");
        for (int i = 0; i < capturedCount; i++) {
            factoryDescriptor.append(descriptor(implParams.get(i)));
        }
        factoryDescriptor.append(')').append(interfaceDescriptor);

        final Handle implHandle = new Handle(Opcodes.H_INVOKESTATIC, ownerQualified.replace('.', '/'),
                implName, implDescriptor, false);
        final String instantiated = descriptor(implParams.subList(capturedCount, implParams.size()),
                ref.fnType().returnType());
        final var result = LambdaClassSynthesizer.synthesize(ownerQualified, ref.samMethodName(),
                factoryDescriptor.toString(),
                new Object[]{samType, implHandle, Type.getMethodType(instantiated)},
                internalName -> { }, methodRef -> { });
        synthesized.add(result.module());

        final List<IRValue> constructorArgs = new ArrayList<>();
        final var objectType = new IRType.Ptr(IRType.I8, "L" + result.internalName() + ";");
        final var object = new IRValue.Temp("%lambda." + index, objectType);
        before.add(new IRInstruction.HeapAlloc(object, objectType, location));
        constructorArgs.add(object);
        for (int i = 0; i < capturedCount; i++) {
            final String name = ref.capturedVarNames() != null && i < ref.capturedVarNames().size()
                    ? ref.capturedVarNames().get(i) : null;
            constructorArgs.add(capturedValue(function, name, implParams.get(i), index, i, before, location));
        }
        before.add(new IRInstruction.Call(CallKind.SPECIAL, IRType.VOID, result.capturedTypes(), null,
                result.qualifiedName() + "_init", constructorArgs, location));
        final var asInterface = new IRValue.Temp("%lambda." + index + ".itf",
                new IRType.Ptr(IRType.I8, interfaceDescriptor));
        before.add(new IRInstruction.Cast(asInterface, object, asInterface.type(), location));
        return asInterface;
    }

    /**
     * A captured variable: a load from its slot ({@code %x.ptr} or
     * {@code %x.arg.ptr}, which SsaBuilder keeps for captured variables), or
     * the parameter itself.
     */
    private static IRValue capturedValue(final IRFunction function,
                                         final String name,
                                         final IRType type,
                                         final int index,
                                         final int position,
                                         final List<IRInstruction> before,
                                         final SourceLocation location) {
        if (name != null) {
            for (final var block : function.blocks()) {
                for (final var instruction : block.instructions()) {
                    if (instruction instanceof IRInstruction.Alloca alloca) {
                        final String slot = IRValue.nameOf(alloca.result());
                        if (("%" + name + ".ptr").equals(slot) || ("%" + name + ".arg.ptr").equals(slot)) {
                            final var loaded = new IRValue.Temp("%lambda." + index + ".c" + position, type);
                            before.add(new IRInstruction.Load(loaded, type, alloca.result(), location));
                            return loaded;
                        }
                    }
                }
            }
            for (final var parameter : function.params) {
                final String parameterName = IRValue.nameOf(parameter);
                if (("%" + name).equals(parameterName) || name.equals(parameterName)) {
                    return parameter;
                }
            }
        }
        throw new IllegalStateException("Captured variable " + name + " of a lambda in "
                + function.name + " not found");
    }

    private static String descriptor(final List<IRType> parameters, final IRType returnType) {
        final StringBuilder sb = new StringBuilder("(");
        for (final IRType parameter : parameters) {
            sb.append(descriptor(parameter));
        }
        return sb.append(')').append(descriptor(returnType)).toString();
    }

    private static String descriptor(final IRType type) {
        return switch (type) {
            case IRType.Ptr ptr -> ptr.jvmDescriptor() != null ? ptr.jvmDescriptor() : "Ljava/lang/Object;";
            case IRType.Bool ignored -> "Z";
            case IRType.Float f -> f.bits() == 32 ? "F" : "D";
            case IRType.Int i -> switch (i.bits()) {
                case 8 -> "B";
                case 16 -> i.unsigned() ? "C" : "S";
                case 64 -> "J";
                default -> "I";
            };
            case IRType.Void ignored -> "V";
            default -> "Ljava/lang/Object;";
        };
    }
}
