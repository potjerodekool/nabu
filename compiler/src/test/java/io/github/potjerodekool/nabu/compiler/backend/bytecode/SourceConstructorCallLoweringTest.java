package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Een source-aanroep {@code new ProcessBuilder(cmd)} heet
 * {@code java_lang_ProcessBuilder_init}; de sluiting moet daarvoor de
 * constructor loweren, ook als de klasse verder alleen via
 * {@code pb.start()} (puntjes-vorm) uit source bereikt wordt en geen seed
 * of andere bytecode-klasse ernaar verwijst.
 */
class SourceConstructorCallLoweringTest {

    @Test
    void sourceConstructorCallIsLowered() {
        final IRType pb = new IRType.Ptr(IRType.I8, "Ljava/lang/ProcessBuilder;");
        final IRType strings = new IRType.Ptr(IRType.I8, "[Ljava/lang/String;");
        final IRBuilder b = new IRBuilder("test/Main");
        final var cmd = new IRValue.Temp("cmd", strings);
        b.beginFunction("Main_run", IRType.VOID, List.of(cmd), Flags.STATIC, false);
        final IRValue obj = b.emitHeapAlloc("new_obj", pb);
        b.emitCall(CallKind.SPECIAL, "java_lang_ProcessBuilder_init", IRType.VOID, List.of(strings), List.of(cmd));
        b.emitCall(CallKind.VIRTUAL, "java.lang.ProcessBuilder_start",
                new IRType.Ptr(IRType.I8, "Ljava/lang/Process;"), List.of(), List.of(obj));
        b.emitReturn(null);
        b.endFunction();

        final var compiler = new BytecodeReachabilityCompiler(null, Set.of());
        final List<IRModule> modules = compiler.compileClosure(List.of(), List.of(b.build()));
        final var names = modules.stream()
                .filter(m -> m.name.replace('/', '.').equals("java.lang.ProcessBuilder"))
                .flatMap(m -> m.functions().stream()).map(f -> f.name).toList();
        assertTrue(names.stream().anyMatch(n -> n.contains("ProcessBuilder_init")), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.contains("ProcessBuilder_start")), names.toString());
    }
}
