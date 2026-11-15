package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafepointPlanTest {

    private static final SourceLocation LOC = SourceLocation.UNKNOWN;

    private static IRFunction fn(final String name, final CallKind kind, final String... calls) {
        final var f = new IRFunction(name, IRType.VOID, List.of(), LOC, 0);
        final var b = new IRBasicBlock("entry");
        for (final String target : calls) {
            b.add(new IRInstruction.Call(kind, IRType.VOID, List.of(), null, target, List.of(), LOC));
        }
        b.add(new IRInstruction.Return(null, LOC));
        f.addBlock(b);
        return f;
    }

    private static Set<String> plan(final IRFunction... fns) {
        final var m = new IRModule("m");
        for (final var f : fns) m.addFunction(f);
        return SafepointPlan.entryPolls(List.of(m));
    }

    @Test
    void acyclicDirectCallsDoNotPoll() {
        final var polls = plan(fn("a", CallKind.STATIC, "b"), fn("b", CallKind.STATIC, "c"), fn("c", CallKind.STATIC));
        assertEquals(Set.of(), polls);
    }

    @Test
    void selfRecursionPolls() {
        assertEquals(Set.of("r"), plan(fn("r", CallKind.STATIC, "r")));
    }

    @Test
    void mutualRecursionPollsBothButNotTheCaller() {
        final var polls = plan(fn("top", CallKind.STATIC, "x"), fn("x", CallKind.STATIC, "y"),
                fn("y", CallKind.STATIC, "x"));
        assertEquals(Set.of("x", "y"), polls);
    }

    @Test
    void virtualCallsMayCloseACycleSoTheyPoll() {
        assertEquals(Set.of("v"), plan(fn("v", CallKind.VIRTUAL, "Other_run"), fn("Other_run", CallKind.STATIC)));
    }

    @Test
    void callsIntoTheRuntimeAreLeaves() {
        assertEquals(Set.of(), plan(fn("f", CallKind.STATIC, "GC_malloc")));
    }

    /** A source function is defined as Main_fib, while its calls say t53.Main_fib. */
    @Test
    void callsThatCarryThePackageResolveToTheShortName() {
        assertEquals(Set.of("Main_fib"), plan(fn("Main_fib", CallKind.STATIC, "t53.Main_fib")));
    }

    @Test
    void callsToUnknownJavaSymbolsPoll() {
        assertEquals(Set.of("f"), plan(fn("f", CallKind.STATIC, "some.pkg.Unknown_run")));
    }
}
