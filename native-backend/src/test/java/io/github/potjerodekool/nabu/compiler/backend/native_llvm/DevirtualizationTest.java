package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closed world: an interface call that every concrete implementer resolves to the same function is a direct call;
 * with two different implementations it stays an itable call.
 */
class DevirtualizationTest {

    @TempDir
    Path tempDir;

    private static final IRType SHAPE = new IRType.Ptr(IRType.I8, "Ltest/Shape;");

    private IRModule shape() {
        final IRBuilder shape = new IRBuilder(Flags.INTERFACE, "test.Shape");
        shape.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        shape.beginFunction("Shape_area", IRType.I32, List.of(new IRValue.Temp("this", SHAPE)), Flags.ABSTRACT);
        shape.endFunction();
        return shape.build();
    }

    private IRModule implementer(final String name) {
        final IRType type = new IRType.Ptr(IRType.I8, "Ltest/" + name + ";");
        final IRBuilder impl = new IRBuilder("test." + name);
        impl.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        impl.interfaces(List.of(SHAPE));
        impl.beginFunction(name + "_area", IRType.I32, List.of(new IRValue.Temp("this", type)), 0L);
        impl.emitReturn(impl.constInt(1));
        impl.endFunction();
        return impl.build();
    }

    private IRModule caller() {
        final IRBuilder main = new IRBuilder("test.Main");
        final var s = new IRValue.Temp("s", SHAPE);
        main.beginFunction("Main_run", IRType.I32, List.of(s), Flags.STATIC, false);
        final var value = main.emitCall(CallKind.INTERFACE, "test.Shape_area", IRType.I32, List.of(), List.of(s));
        main.emitReturn(value);
        main.endFunction();
        return main.build();
    }

    private String run(final String... implementers) throws Exception {
        final List<IRModule> modules = new ArrayList<>(List.of(shape(), caller()));
        for (final String name : implementers) {
            modules.add(implementer(name));
        }
        final Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(modules,
                new CompileOptions(CompileOptions.OptLevel.NONE, false, null, CompileOptions.GcStrategy.BOEHM,
                        io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION), out);
        final String ll = Files.readString(tempDir.resolve("out.ll"));
        final String body = ll.substring(ll.indexOf("define i32 @Main_run("));
        return body.substring(0, body.indexOf("\n}"));
    }

    @Test
    void oneImplementerIsADirectCall() throws Exception {
        final String body = run("Square");
        assertFalse(body.contains(".ifnptr"), "No itable load for a single implementer:\n" + body);
        assertTrue(body.contains("@Square_area"), "A direct call to Square_area:\n" + body);
    }

    @Test
    void twoImplementersStayAnItableCall() throws Exception {
        final String body = run("Square", "Circle");
        assertTrue(body.contains(".ifnptr"), "The itable for two implementations:\n" + body);
    }
}
