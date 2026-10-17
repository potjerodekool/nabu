package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * De null-check vóór een virtuele aanroep splitst het huidige blok. De
 * nieuwe blokken horen bij dezelfde try-regio: een aanroep ná de virtuele
 * call moet nog steeds een {@code invoke} naar de handler zijn. Zonder die
 * erfenis werd het een gewone {@code call} en ving de catch niets — picocli's
 * {@code execute()} liet zo zijn eigen ParameterException ontsnappen.
 */
class NullCheckKeepsTryRegionTest {

    @TempDir
    Path tempDir;

    private static final IRType BOX = new IRType.Ptr(IRType.I8, "Ltest/Box;");

    @Test
    void callAfterNullCheckedVirtualCallIsStillAnInvoke() throws Exception {
        IRBuilder box = new IRBuilder("test/Box");
        box.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        box.beginFunction("Box_get", IRType.I32, List.of(new IRValue.Temp("this", BOX)), 0L);
        box.emitReturn(box.constInt(1));
        box.endFunction();

        IRBuilder b = new IRBuilder("test/Main");
        var param = new IRValue.Temp("box", BOX);
        b.beginFunction("Main_run", IRType.I32, List.of(param), Flags.STATIC, false);
        final IRBasicBlock entry = b.currentBlock();
        final IRBasicBlock tryBody = b.beginBlock("try.body");
        final IRBasicBlock tryEnd = b.beginBlock("try.end");
        final IRBasicBlock catch0 = b.beginBlock("try.catch.0");

        b.setCurrentBlock(entry);
        b.emitBranch(tryBody);

        b.setCurrentBlock(tryBody);
        b.emitCall(CallKind.VIRTUAL, "Box_get", IRType.I32, List.of(), List.of(param));
        b.emitCall(CallKind.STATIC, "ext_mayThrow", IRType.VOID, List.of(), List.of());
        b.emitBranch(tryEnd);

        b.setCurrentBlock(catch0);
        catch0.add(0, new IRInstruction.TryCatchRegion(tryBody.label(), tryEnd.label(),
                catch0.label(), "java/lang/Exception", b.currentLocation()));
        b.emitReturn(b.constInt(1));

        b.setCurrentBlock(tryEnd);
        b.emitReturn(b.constInt(0));
        b.endFunction();

        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(List.of(box.build(), b.build()),
                new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                        CompileOptions.GcStrategy.BOEHM,
                        io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION), out);
        String ll = Files.readString(tempDir.resolve("out.ll"));
        String run = ll.substring(ll.indexOf("define i32 @Main_run("));
        run = run.substring(0, run.indexOf("\n}"));

        assertTrue(run.contains("receiver.nonnull"), "verwacht een null-check:\n" + run);
        assertTrue(run.contains("invoke void @ext_mayThrow()"),
                "de aanroep na de null-check hoort een invoke naar de handler te zijn:\n" + run);
    }
}
