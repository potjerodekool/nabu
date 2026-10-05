package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Gedeelde registratie van {@code @llvm.global_ctors}.
 *
 * <p>Een module mag maar één global met die naam hebben; de gebruikelijke
 * aanpak is een <em>appending</em> global, zodat de linker alle
 * constructor-arrays uit alle objecten samenvoegt. Twee losse emitters die elk
 * hun eigen {@code llvm.global_ctors} aanmaken zouden dus een dubbele globale
 * opleveren, en dus een kapotte module. Daarom is er één instantie per module:
 * iedereen die iets vóór {@code main} wil doen registreert daar zijn functie.
 *
 * <p>Elke entry is {@code { i32 prioriteit, ptr functie, ptr data }} en de
 * functie heeft type {@code void(void)}. Prioriteit 65535 is de conventie voor
 * "gewone" constructors.
 *
 * <p>De volgorde tussen constructors met dezelfde prioriteit is niet
 * gegarandeerd. Dat is hier irrelevant: geen enkele constructor alloceert
 * (zie {@link ReflectionInfoEmitter} en de GC-init), dus de volgorde tussen
 * hen is niet waarneembaar.
 */
final class ModuleCtors {

    private static final int CTOR_PRIORITY = 65535;

    private final LLVMContextRef ctx;
    private final LLVMModuleRef  mod;
    private final List<LLVMValueRef> ctors = new ArrayList<>();
    private boolean emitted;

    ModuleCtors(final LLVMContextRef ctx, final LLVMModuleRef mod) {
        this.ctx = ctx;
        this.mod = mod;
    }

    /** Voegt een {@code void(void)}-functie toe die vóór {@code main} draait. */
    void add(final LLVMValueRef fn) {
        if (emitted) {
            throw new IllegalStateException(
                    "llvm.global_ctors is al geëmit; registreer alle constructors vóór emit()");
        }
        ctors.add(fn);
    }

    /**
     * Schrijft {@code @llvm.global_ctors}, tenzij er niets te registreren is.
     * Een tweede aanroep is een no-op.
     */
    void emit() {
        if (emitted) {
            return;
        }
        emitted = true;

        if (ctors.isEmpty()) {
            return;
        }

        final LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
        final LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);

        final PointerPointer<Pointer> entryTypes = new PointerPointer<>(3);
        entryTypes.put(0, i32);
        entryTypes.put(1, i8Ptr);
        entryTypes.put(2, i8Ptr);
        final LLVMTypeRef entryType = LLVMStructTypeInContext(ctx, entryTypes, 3, 0);
        Reference.reachabilityFence(entryTypes);

        final int n = ctors.size();
        final LLVMTypeRef arrType = LLVMArrayType(entryType, n);
        final LLVMValueRef ctorsGlobal = LLVMAddGlobal(mod, arrType,
                new BytePointer("llvm.global_ctors"));
        // Appending: de linker voegt dit array samen met die uit andere objecten.
        LLVMSetLinkage(ctorsGlobal, LLVMAppendingLinkage);

        final PointerPointer<Pointer> elems = new PointerPointer<>(n);
        for (int i = 0; i < n; i++) {
            final PointerPointer<Pointer> elemInit = new PointerPointer<>(3);
            elemInit.put(0, LLVMConstInt(i32, CTOR_PRIORITY, 0));
            elemInit.put(1, ctors.get(i));
            elemInit.put(2, LLVMConstNull(i8Ptr));
            elems.put(i, LLVMConstNamedStruct(entryType, elemInit, 3));
            Reference.reachabilityFence(elemInit);
        }
        LLVMSetInitializer(ctorsGlobal, LLVMConstArray(entryType, elems, n));
        Reference.reachabilityFence(elems);
    }

    /** Aantal geregistreerde constructors. */
    int size() {
        return ctors.size();
    }
}
