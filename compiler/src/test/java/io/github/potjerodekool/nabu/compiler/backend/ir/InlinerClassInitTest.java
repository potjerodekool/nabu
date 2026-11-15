package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.optimize.Inliner;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A static method of a class with a static initializer starts with the initializer guard: do not inline it. */
class InlinerClassInitTest {

    private static final SourceLocation LOC = SourceLocation.UNKNOWN;

    private static IRFunction empty(final String name) {
        final var f = new IRFunction(name, IRType.VOID, List.of(), LOC, 0);
        final var b = new IRBasicBlock("entry");
        b.add(new IRInstruction.Return(null, LOC));
        f.addBlock(b);
        return f;
    }

    private static long callsTo(final IRFunction caller, final String target) {
        return caller.blocks().stream().flatMap(b -> b.instructions().stream())
                .filter(i -> i instanceof IRInstruction.Call c && c.function().equals(target)).count();
    }

    private static IRFunction callerOf(final String target) {
        final var f = new IRFunction("app.Main_main", IRType.VOID, List.of(), LOC, 0);
        final var b = new IRBasicBlock("entry");
        b.add(new IRInstruction.Call(CallKind.STATIC, IRType.VOID, List.of(), null, target, List.of(), LOC));
        b.add(new IRInstruction.Return(null, LOC));
        f.addBlock(b);
        return f;
    }

    @Test
    void methodOfAClassWithStaticInitializerIsNotInlined() {
        final var touch = empty("lib.Lazy_touch");
        final Map<String, IRFunction> callees = new HashMap<>();
        callees.put("lib.Lazy_touch", touch);
        callees.put("lib.Lazy_clinit", empty("lib.Lazy_clinit"));
        final var caller = callerOf("lib.Lazy_touch");
        new Inliner(callees, Map.of("lib.Lazy_touch", 1)).run(caller);
        assertEquals(1, callsTo(caller, "lib.Lazy_touch"));
    }

    @Test
    void methodOfAClassWithoutStaticInitializerIsInlined() {
        final Map<String, IRFunction> callees = new HashMap<>();
        callees.put("lib.Plain_touch", empty("lib.Plain_touch"));
        final var caller = callerOf("lib.Plain_touch");
        new Inliner(callees, Map.of("lib.Plain_touch", 1)).run(caller);
        assertEquals(0, callsTo(caller, "lib.Plain_touch"));
    }
}
