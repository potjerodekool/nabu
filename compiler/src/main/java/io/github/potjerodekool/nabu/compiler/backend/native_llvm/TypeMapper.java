package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;

import java.lang.ref.Reference;
import java.util.List;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Vertaalt IRType naar LLVMTypeRef.
 * Ondersteunt: Int, Float, Bool, Ptr, Array, Void, Function, Struct.
 */
public class TypeMapper {

    private final LLVMContextRef ctx;

    public TypeMapper(LLVMContextRef ctx) {
        this.ctx = ctx;
    }

    public LLVMTypeRef map(IRType type) {
        return switch (type) {
            case IRType.Int   t -> LLVMIntTypeInContext(ctx, t.bits());
            case IRType.Float t -> t.bits() == 32
                    ? LLVMFloatTypeInContext(ctx)
                    : LLVMDoubleTypeInContext(ctx);
            case IRType.Bool  t -> LLVMInt1TypeInContext(ctx);
            case IRType.Void  t -> LLVMVoidTypeInContext(ctx);
            case IRType.Ptr   t -> LLVMPointerTypeInContext(ctx, 0); // opaque ptr
            case IRType.Array t -> LLVMArrayType(map(t.elem()), t.size());
            case IRType.Function t -> {
                LLVMTypeRef   ret    = map(t.returnType());
                LLVMTypeRef[] params = t.paramTypes().stream()
                        .map(this::map)
                        .toArray(LLVMTypeRef[]::new);
                PointerPointer<Pointer> pp = toPointerPointer(params);
                LLVMTypeRef result = LLVMFunctionType(ret, pp, params.length, 0);
                Reference.reachabilityFence(pp);
                yield result;
            }
            case IRType.Struct t -> {
                LLVMTypeRef[] fields = t.fields().stream()
                        .map(this::map)
                        .toArray(LLVMTypeRef[]::new);
                PointerPointer<Pointer> pp = toPointerPointer(fields);
                LLVMTypeRef result = LLVMStructTypeInContext(ctx, pp, fields.length, 0);
                Reference.reachabilityFence(pp);
                yield result;
            }
            default -> throw new IllegalStateException("Unexpected value: " + type);
        };
    }

    /**
     * Mapt een lijst van IRValue-parameters naar een array van LLVMTypeRef.
     */
    public LLVMTypeRef[] mapParams(List<IRValue> params) {
        LLVMTypeRef[] result = new LLVMTypeRef[params.size()];
        for (int i = 0; i < params.size(); i++)
            result[i] = map(params.get(i).type());
        return result;
    }

    /**
     * Bouwt een PointerPointer van een LLVMTypeRef-array.
     * Houd de return-waarde in leven met reachabilityFence na gebruik.
     */
    public PointerPointer<Pointer> toPointerPointer(LLVMTypeRef[] types) {
        PointerPointer<Pointer> pp = new PointerPointer<>(types.length);
        for (int i = 0; i < types.length; i++)
            pp.put(i, types[i]);
        return pp;
    }
}
