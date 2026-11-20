package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMMemoryBufferRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.junit.jupiter.api.Test;

import static org.bytedeco.llvm.global.LLVM.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With a PGO profile the functions that the training run never entered (function_entry_count 0) are put in a section
 * of their own, so that the pages of the code a server runs are not shared with code that is never read.
 */
class ColdFunctionPlacementTest {

    private static final String IR = """
            define void @hot() !prof !0 { ret void }
            define void @cold() !prof !1 { ret void }
            define void @unknown() { ret void }
            declare void @external()
            !0 = !{!"function_entry_count", i64 25}
            !1 = !{!"function_entry_count", i64 0}
            """;

    private static String sectionOf(final LLVMModuleRef mod, final String name) {
        final LLVMValueRef f = LLVMGetNamedFunction(mod, name);
        final BytePointer section = LLVMGetSection(f);
        return section == null || section.isNull() ? "" : section.getString();
    }

    private int place(final String property, final String triple, final LLVMModuleRef[] out) {
        final String before = System.getProperty("nabu.pgo");
        if (property == null) {
            System.clearProperty("nabu.pgo");
        } else {
            System.setProperty("nabu.pgo", property);
        }
        try {
            new NativeLLVMBackend(); // loads and initializes LLVM
            final LLVMContextRef ctx = LLVMContextCreate();
            final LLVMMemoryBufferRef buffer = LLVMCreateMemoryBufferWithMemoryRangeCopy(
                    new BytePointer(IR), IR.length(), new BytePointer("cold"));
            final LLVMModuleRef mod = new LLVMModuleRef();
            final BytePointer err = new BytePointer();
            assertEquals(0, LLVMParseIRInContext(ctx, buffer, mod, err), "the IR of the test does not parse");
            out[0] = mod;
            return NativeLLVMBackend.placeColdFunctions(ctx, mod, triple);
        } finally {
            if (before == null) {
                System.clearProperty("nabu.pgo");
            } else {
                System.setProperty("nabu.pgo", before);
            }
        }
    }

    @Test
    void onlyFunctionsThatNeverRanGoToTheColdSection() {
        final LLVMModuleRef[] mod = new LLVMModuleRef[1];
        assertEquals(1, place("/some/profile.profdata", "x86_64-pc-linux-gnu", mod));
        assertEquals(".text.unlikely.nabu", sectionOf(mod[0], "cold"));
        assertEquals("", sectionOf(mod[0], "hot"));
        assertEquals("", sectionOf(mod[0], "unknown"), "a function without a count may have been run");
    }

    @Test
    void theSectionNameFollowsTheObjectFormat() {
        final LLVMModuleRef[] mod = new LLVMModuleRef[1];
        place("/some/profile.profdata", "x86_64-w64-windows-gnu", mod);
        assertTrue(sectionOf(mod[0], "cold").startsWith(".text$"), sectionOf(mod[0], "cold"));
        place("/some/profile.profdata", "arm64-apple-macosx", mod);
        assertTrue(sectionOf(mod[0], "cold").startsWith("__TEXT,"), sectionOf(mod[0], "cold"));
    }

    @Test
    void withoutAProfileNothingMoves() {
        final LLVMModuleRef[] mod = new LLVMModuleRef[1];
        assertEquals(0, place(null, "x86_64-pc-linux-gnu", mod));
        assertEquals("", sectionOf(mod[0], "cold"));
    }

    @Test
    void theInstrumentedBuildKeepsEverythingInPlace() {
        final LLVMModuleRef[] mod = new LLVMModuleRef[1];
        assertEquals(0, place("instrument", "x86_64-pc-linux-gnu", mod));
    }
}
