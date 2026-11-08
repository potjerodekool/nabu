package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.util.CompileException;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTargetMachineRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Generates the code of one large LLVM module in chunks while the module is still being built, so that the bodies of
 * the whole program are never in memory at once.
 *
 * <p>After a group of bodies has been emitted, {@link #flush} compiles the module to an object file in which only
 * those functions are defined and then throws their bodies away (the functions stay, as declarations, so the values
 * the emitter holds stay valid). {@link #finish} compiles what is left: the globals and the functions made last.
 * The object files link into the same program as the whole module would.</p>
 *
 * <ul>
 *   <li>Every private or internal symbol becomes external and hidden: another object file uses it.</li>
 *   <li>The initializers of the globals are taken away for the chunks (a global is a declaration there) and put back
 *   for the last object file, which is where the globals are defined.</li>
 *   <li>The data layout and target of the module are those of the machine only while the code is generated: the
 *   emitter takes its sizes from the module while it is building.</li>
 * </ul>
 */
final class ChunkedCodegen {

    private final LLVMModuleRef mod;
    private final LLVMTargetMachineRef machine;
    private final String triple;
    private final Path firstObject;
    private final List<Path> objects = new ArrayList<>();

    /** The globals whose initializer was taken away, with the initializer and the linkage they had. */
    private final List<Object[]> stripped = new ArrayList<>();

    /** Names of the functions that had a body when a chunk was compiled. */
    private final Set<String> compiled = new HashSet<>();
    /** Names of the declarations that were called or referenced while their callers were still there. */
    private final Set<String> usedDeclarations = new HashSet<>();

    ChunkedCodegen(final LLVMModuleRef mod, final LLVMTargetMachineRef machine, final String triple,
                   final Path firstObject) {
        this.mod = mod;
        this.machine = machine;
        this.triple = triple;
        this.firstObject = firstObject;
    }

    Set<String> compiledFunctions() {
        return compiled;
    }

    Set<String> usedDeclarations() {
        return usedDeclarations;
    }

    List<Path> objects() {
        return objects;
    }

    private Path nextObject() {
        final Path path = objects.isEmpty() ? firstObject
                : firstObject.resolveSibling(firstObject.getFileName().toString().replaceFirst("\\.o$", "")
                        + "." + objects.size() + ".o");
        objects.add(path);
        return path;
    }

    /** Compiles the functions that have a body now and drops their bodies. */
    void flush() throws CompileException {
        note();
        internalize();
        strip();
        generate(nextObject());
        clearBodies();
    }

    /** Compiles the rest: the globals (with their initializers) and the functions that are still defined. */
    void finish() throws CompileException {
        internalize();
        for (final Object[] each : stripped) {
            final LLVMValueRef global = (LLVMValueRef) each[0];
            LLVMSetInitializer(global, (LLVMValueRef) each[1]);
            LLVMSetLinkage(global, (Integer) each[2]);
        }
        stripped.clear();
        generate(nextObject());
    }

    private void note() {
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull(); fn = LLVMGetNextFunction(fn)) {
            if (LLVMGetIntrinsicID(fn) != 0) {
                continue;
            }
            if (LLVMIsDeclaration(fn) == 0) {
                compiled.add(LLVMGetValueName(fn).getString());
            } else {
                final var use = LLVMGetFirstUse(fn);
                if (use != null && !use.isNull()) {
                    usedDeclarations.add(LLVMGetValueName(fn).getString());
                }
            }
        }
    }

    private static boolean isLocal(final int linkage) {
        return linkage == LLVMInternalLinkage || linkage == LLVMPrivateLinkage;
    }

    private void internalize() {
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull(); fn = LLVMGetNextFunction(fn)) {
            if (isLocal(LLVMGetLinkage(fn))) {
                LLVMSetLinkage(fn, LLVMExternalLinkage);
                LLVMSetVisibility(fn, LLVMHiddenVisibility);
            }
        }
        for (LLVMValueRef g = LLVMGetFirstGlobal(mod); g != null && !g.isNull(); g = LLVMGetNextGlobal(g)) {
            if (isLocal(LLVMGetLinkage(g))) {
                LLVMSetLinkage(g, LLVMExternalLinkage);
                LLVMSetVisibility(g, LLVMHiddenVisibility);
            }
        }
    }

    /** Makes the globals declarations for the chunks. The ones that are new since the last time are taken. */
    private void strip() {
        for (LLVMValueRef g = LLVMGetFirstGlobal(mod); g != null && !g.isNull(); g = LLVMGetNextGlobal(g)) {
            final LLVMValueRef init = LLVMGetInitializer(g);
            if (init == null || init.isNull()) {
                continue;
            }
            final int linkage = LLVMGetLinkage(g);
            stripped.add(new Object[]{g, init, linkage});
            LLVMSetInitializer(g, null);
            if (linkage == LLVMAppendingLinkage) {
                // llvm.global_ctors and the like: written once, with the last object file
                LLVMSetLinkage(g, LLVMExternalLinkage);
            }
        }
    }

    private void generate(final Path output) throws CompileException {
        LLVMSetTarget(mod, new BytePointer(triple));
        final BytePointer err = new BytePointer();
        try {
            if (LLVMTargetMachineEmitToFile(machine, mod, new BytePointer(output.toString()), LLVMObjectFile, err) != 0) {
                throw new CompileException("Codegen failed: " + err.getString());
            }
        } finally {
            // while it builds, the emitter takes the sizes of types from the module: the default layout
            LLVMSetDataLayout(mod, new BytePointer(""));
            LLVMSetTarget(mod, new BytePointer(""));
        }
    }

    /** Turns every function with a body into a declaration. */
    private void clearBodies() {
        for (LLVMValueRef fn = LLVMGetFirstFunction(mod); fn != null && !fn.isNull(); fn = LLVMGetNextFunction(fn)) {
            if (LLVMIsDeclaration(fn) != 0) {
                continue;
            }
            final List<LLVMValueRef> instructions = new ArrayList<>();
            final List<LLVMBasicBlockRef> blocks = new ArrayList<>();
            for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(fn); bb != null && !bb.isNull();
                 bb = LLVMGetNextBasicBlock(bb)) {
                blocks.add(bb);
                for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null && !inst.isNull();
                     inst = LLVMGetNextInstruction(inst)) {
                    instructions.add(inst);
                }
            }
            // Uses first (a value can be used in a block that comes before it), then the instructions from the back.
            for (final LLVMValueRef inst : instructions) {
                final var type = LLVMTypeOf(inst);
                if (LLVMGetTypeKind(type) != LLVMVoidTypeKind) {
                    LLVMReplaceAllUsesWith(inst, LLVMGetUndef(type));
                }
            }
            for (int i = instructions.size() - 1; i >= 0; i--) {
                LLVMInstructionEraseFromParent(instructions.get(i));
            }
            for (final LLVMBasicBlockRef bb : blocks) {
                LLVMDeleteBasicBlock(bb);
            }
            LLVMSetSubprogram(fn, null);
        }
    }
}
