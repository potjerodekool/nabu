package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMMetadataRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.util.HashMap;
import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Type-based alias information (!tbaa): an access tag per kind of memory, so the optimizer knows that a store to one
 * field does not change another field, an array element or the length of an array. Each tag is a scalar type of its
 * own under one root: two accesses with different tags never alias, with the same tag they may. An access without a
 * tag (Unsafe, the runtime, copies) may alias everything, as before. {@code -Dnabu.tbaa=false} switches it off.
 */
final class Tbaa {
    private static final boolean ENABLED = !"false".equals(System.getProperty("nabu.tbaa"));

    private final LLVMContextRef ctx;
    private final int kind;
    private final LLVMMetadataRef root;
    private final LLVMMetadataRef zero;
    private final Map<String, LLVMValueRef> tags = new HashMap<>();

    Tbaa(final LLVMContextRef ctx) {
        this.ctx = ctx;
        this.kind = LLVMGetMDKindIDInContext(ctx, "tbaa", 4);
        this.zero = LLVMValueAsMetadata(LLVMConstInt(LLVMInt64TypeInContext(ctx), 0, 0));
        this.root = node(string("nabu"));
    }

    private LLVMMetadataRef string(final String text) {
        return LLVMMDStringInContext2(ctx, new BytePointer(text), text.length());
    }

    private LLVMMetadataRef node(final LLVMMetadataRef... elements) {
        final PointerPointer<Pointer> pp = new PointerPointer<>(elements.length);
        for (int i = 0; i < elements.length; i++) {
            pp.put(i, elements[i]);
        }
        return LLVMMDNodeInContext2(ctx, pp, elements.length);
    }

    /** Marks the load or store as an access to the memory named {@code name}. */
    void mark(final LLVMValueRef instruction, final String name) {
        if (!ENABLED || name == null || instruction == null || instruction.isNull()) {
            return;
        }
        final LLVMValueRef tag = tags.computeIfAbsent(name, n -> {
            final LLVMMetadataRef type = node(string(n), root, zero);
            return LLVMMetadataAsValue(ctx, node(type, type, zero));
        });
        LLVMSetMetadata(instruction, kind, tag);
    }

    /** A short name of an LLVM type, for the tag of a field or of an array element. */
    static String typeKey(final LLVMTypeRef type) {
        return switch (LLVMGetTypeKind(type)) {
            case LLVMIntegerTypeKind -> "i" + LLVMGetIntTypeWidth(type);
            case LLVMPointerTypeKind -> "p";
            case LLVMFloatTypeKind -> "f";
            case LLVMDoubleTypeKind -> "d";
            default -> "t" + LLVMGetTypeKind(type);
        };
    }
}
