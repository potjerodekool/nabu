package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMDIBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMMetadataRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Line tables for the functions that come from source. Every such function
 * gets a {@code DISubprogram}, and the instructions that carry a source line
 * get a debug location, so the object file has a DWARF {@code .debug_line}
 * table that the runtime reads to put line numbers in stack traces.
 *
 * <p>The file of a subprogram has the <em>symbol of the function</em> as its
 * directory. A line in the table then says which function it belongs to,
 * which matters when LLVM inlined a function into another one: the line is
 * that of the inlined function, not of the function the symbol names.</p>
 *
 * <p>Only line tables are emitted, no types or variables. Functions compiled
 * from bytecode have no source line and no debug location.</p>
 */
final class DebugLines {

    private static final String SYNTHETIC_FILE_SUFFIX = ".class";

    /** The file of a function that does not come from source; the runtime knows it by the leading '<'. */
    private static final String UNKNOWN_FILE = "<unknown>";

    /** Subprograms for all functions, so that inlined JDK functions are frames of a trace too. */
    private static final boolean INLINE_INFO = !"false".equals(System.getProperty("nabu.inlineInfo"));

    private final LLVMContextRef ctx;
    private final LLVMModuleRef mod;
    private final LLVMBuilderRef builder;

    private LLVMDIBuilderRef di;
    private LLVMMetadataRef unit;
    private boolean any;

    /** The subprogram of the function being emitted, or null when it has no source lines. */
    private LLVMMetadataRef scope;
    private LLVMValueRef function;
    private int lastLine;

    /** The functions whose emission was interrupted by another one: their scope and last line. */
    /** Every function that got a subprogram: its function, subprogram and line. */
    private final java.util.ArrayList<Object[]> withScope = new java.util.ArrayList<>();
    private final java.util.ArrayDeque<Object[]> outer = new java.util.ArrayDeque<>();

    DebugLines(final LLVMContextRef ctx, final LLVMModuleRef mod, final LLVMBuilderRef builder) {
        this.ctx = ctx;
        this.mod = mod;
        this.builder = builder;
    }

    /** Starts a function: gives {@code llvmFn} a subprogram when {@code fn} comes from source. */
    void begin(final IRFunction fn, final LLVMValueRef llvmFn, final String symbol) {
        scope = null;
        final SourceLocation location = fn.location;
        if (fn.blocks().isEmpty()) {
            return; // abstract or interface method: a declaration cannot have a subprogram
        }
        final boolean fromSource = location != null && location.isKnown() && location.file() != null
                && !location.file().endsWith(SYNTHETIC_FILE_SUFFIX) && !location.file().startsWith("<");
        if (!fromSource && !INLINE_INFO) {
            return;
        }
        if (LLVMGetSubprogram(llvmFn) != null) {
            return; // the function was emitted before (two IR functions with one symbol): it keeps its first subprogram
        }
        if (di == null) {
            di = LLVMCreateDIBuilder(mod);
            final LLVMMetadataRef unitFile = LLVMDIBuilderCreateFile(di, "<nabu>", 6, ".", 1);
            unit = LLVMDIBuilderCreateCompileUnit(di, LLVMDWARFSourceLanguageJava, unitFile,
                    "nabu", 4, 0, "", 0, 0, "", 0, LLVMDWARFEmissionLineTablesOnly, 0, 0, 0, "", 0, "", 0);
        }
        // A function without source lines still gets a subprogram (and line 0 locations): that is what makes the
        // functions inlined into it, and it into others, show up in a stack trace.
        final String file = fromSource ? location.file() : UNKNOWN_FILE;
        final int declLine = fromSource ? location.line() : 0;
        final LLVMMetadataRef diFile = LLVMDIBuilderCreateFile(di, file, file.length(),
                symbol, symbol.length());
        final LLVMMetadataRef type = LLVMDIBuilderCreateSubroutineType(di, diFile,
                (PointerPointer<?>) null, 0, LLVMDIFlagZero);
        function = llvmFn;
        scope = LLVMDIBuilderCreateFunction(di, unit, symbol, symbol.length(), symbol, symbol.length(),
                diFile, declLine, type, 0, 1, declLine, LLVMDIFlagZero, 0);
        LLVMSetSubprogram(llvmFn, scope);
        withScope.add(new Object[]{llvmFn, scope, declLine});
        any = true;
        lastLine = 0;
        setLine(declLine);
    }

    /** Sets the debug location for the instruction about to be emitted; keeps the last one when it has no line. */
    void locate(final IRInstruction instr) {
        if (scope == null) {
            return;
        }
        final SourceLocation location = instr.location();
        if (location != null && location.isKnown() && location.line() != lastLine) {
            setLine(location.line());
        }
    }

    /**
     * Gives the instructions that have no location (some are made by another builder) the last line of the function:
     * the verifier wants a location on every call that may be inlined, and an inlined call needs one to name its caller.
     */
    private void locateTheRest(final LLVMValueRef function, final LLVMMetadataRef scope, final int line) {
        final int kind = LLVMGetMDKindIDInContext(ctx, "dbg", 3);
        final LLVMValueRef rest = LLVMMetadataAsValue(ctx, LLVMDIBuilderCreateDebugLocation(ctx, line, 0, scope, null));
        for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(function); block != null;
             block = LLVMGetNextBasicBlock(block)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(inst)) {
                if (LLVMGetMetadata(inst, kind) == null) {
                    LLVMSetMetadata(inst, kind, rest);
                }
            }
        }
    }

    private void setLine(final int line) {
        lastLine = line;
        LLVMSetCurrentDebugLocation2(builder,
                LLVMDIBuilderCreateDebugLocation(ctx, line, 0, scope, null));
    }

    /** Starts the emission of a body; the function being emitted (if any) is resumed by {@link #leave}. */
    void enter() {
        outer.push(new Object[]{scope, lastLine, function});
        scope = null;
        function = null;
    }

    /**
     * Ends a body. The body of another function can be emitted in the middle of one (a lambda, a bridge): the
     * instructions of the function that goes on carry its locations again.
     */
    void leave() {
        final Object[] previous = outer.pop();
        scope = (LLVMMetadataRef) previous[0];
        lastLine = (Integer) previous[1];
        function = (LLVMValueRef) previous[2];
        if (scope != null) {
            setLine(lastLine);
        } else {
            LLVMSetCurrentDebugLocation2(builder, null);
        }
    }

    private boolean flagsAdded;

    /**
     * Completes the debug metadata of the functions emitted so far; call after all bodies, or after a chunk (see
     * {@link ChunkedCodegen}): the next function then starts a new compile unit.
     */
    void finish() {
        if (!any || di == null) {
            return;
        }
        for (final Object[] each : withScope) {
            locateTheRest((LLVMValueRef) each[0], (LLVMMetadataRef) each[1], (Integer) each[2]);
        }
        LLVMDIBuilderFinalize(di);
        if (!flagsAdded) {
            flagsAdded = true;
            final org.bytedeco.llvm.LLVM.LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
            LLVMAddModuleFlag(mod, 1, "Debug Info Version", 18,
                    LLVMValueAsMetadata(LLVMConstInt(i32, 3, 0)));
            LLVMAddModuleFlag(mod, 1, "Dwarf Version", 13,
                    LLVMValueAsMetadata(LLVMConstInt(i32, 4, 0)));
        }
        LLVMDisposeDIBuilder(di);
        di = null;
        unit = null;
        withScope.clear();
    }
}
