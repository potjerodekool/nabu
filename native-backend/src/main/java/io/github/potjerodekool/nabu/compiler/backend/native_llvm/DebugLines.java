package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import org.bytedeco.javacpp.PointerPointer;
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

    private final LLVMContextRef ctx;
    private final LLVMModuleRef mod;
    private final LLVMBuilderRef builder;

    private LLVMDIBuilderRef di;
    private LLVMMetadataRef unit;
    private boolean any;

    /** The subprogram of the function being emitted, or null when it has no source lines. */
    private LLVMMetadataRef scope;
    private int lastLine;

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
        if (location == null || !location.isKnown() || location.file() == null
                || location.file().endsWith(SYNTHETIC_FILE_SUFFIX) || location.file().startsWith("<")) {
            return;
        }
        if (di == null) {
            di = LLVMCreateDIBuilder(mod);
            final LLVMMetadataRef unitFile = LLVMDIBuilderCreateFile(di, "<nabu>", 6, ".", 1);
            unit = LLVMDIBuilderCreateCompileUnit(di, LLVMDWARFSourceLanguageJava, unitFile,
                    "nabu", 4, 0, "", 0, 0, "", 0, LLVMDWARFEmissionLineTablesOnly, 0, 0, 0, "", 0, "", 0);
        }
        final String file = location.file();
        final LLVMMetadataRef diFile = LLVMDIBuilderCreateFile(di, file, file.length(),
                symbol, symbol.length());
        final LLVMMetadataRef type = LLVMDIBuilderCreateSubroutineType(di, diFile,
                (PointerPointer<?>) null, 0, LLVMDIFlagZero);
        scope = LLVMDIBuilderCreateFunction(di, unit, symbol, symbol.length(), symbol, symbol.length(),
                diFile, location.line(), type, 0, 1, location.line(), LLVMDIFlagZero, 0);
        LLVMSetSubprogram(llvmFn, scope);
        any = true;
        lastLine = 0;
        setLine(location.line());
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

    private void setLine(final int line) {
        lastLine = line;
        LLVMSetCurrentDebugLocation2(builder,
                LLVMDIBuilderCreateDebugLocation(ctx, line, 0, scope, null));
    }

    /** Ends a function: instructions emitted later (helpers) carry no location. */
    void end() {
        if (scope != null) {
            LLVMSetCurrentDebugLocation2(builder, null);
            scope = null;
        }
    }

    /** Completes the debug metadata; call once, after all bodies. */
    void finish() {
        if (!any) {
            return;
        }
        LLVMDIBuilderFinalize(di);
        final org.bytedeco.llvm.LLVM.LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
        LLVMAddModuleFlag(mod, 1, "Debug Info Version", 18,
                LLVMValueAsMetadata(LLVMConstInt(i32, 3, 0)));
        LLVMAddModuleFlag(mod, 1, "Dwarf Version", 13,
                LLVMValueAsMetadata(LLVMConstInt(i32, 4, 0)));
        LLVMDisposeDIBuilder(di);
    }
}
