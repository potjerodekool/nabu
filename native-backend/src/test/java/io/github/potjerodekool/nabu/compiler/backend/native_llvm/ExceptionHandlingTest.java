package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import static org.junit.jupiter.api.Assertions.*;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fase 3.1 (backend): exception handling in de native LLVM-backend.
 *
 * Hier: een {@code Throw}-instructie binnen een try-regio moet als
 * {@code invoke} naar {@code @nabu_throw} worden geëmit (zodat de Itanium
 * unwinder het richting de catch-handler leidt), gevolgd door {@code
 * unreachable}. Buiten een try-regio is het een gewone {@code call} gevolgd
 * door {@code unreachable}. Ook testen we dat het throw-object (nu nog als
 * placeholder) niet crasht wanneer de frontend het resultaat (nog) niet vult.
 */
class ExceptionHandlingTest {

    @TempDir
    Path tempDir;

    private static final IRType EXC_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Exception;");

    private String compileAndReadLl(IRModule module, CompileOptions opts) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(module, opts, out);
        Path ll = tempDir.resolve("out.ll");
        assertTrue(ll.toFile().exists(), ".ll bestand niet aangemaakt");
        return Files.readString(ll);
    }

    private IRModule buildThrowingModule(boolean inTry) {
        IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("main", IRType.VOID, List.of(), 0L, false);

        if (inTry) {
            IRBasicBlock entry = b.currentBlock();
            IRBasicBlock tryBody = b.beginBlock("try.body");
            IRBasicBlock tryEnd = b.beginBlock("try.end");
            IRBasicBlock handler = b.beginBlock("try.catch.0");

            b.setCurrentBlock(entry);
            b.emitBranch(tryBody);

            // try.body: gooi een exception-object (result==null -> placeholder-path)
            b.setCurrentBlock(tryBody);
            b.emitThrow(EXC_TYPE);

            // Handler-blok: TryCatchRegion-metadata (start<->end beschermd door
            // handler) gevolgd door een return.
            b.setCurrentBlock(handler);
            handler.add(0, new IRInstruction.TryCatchRegion(
                    tryBody.label(),
                    tryEnd.label(),
                    handler.label(),
                    null,
                    b.currentLocation()));
            b.emitReturn(null);

            // try.end: continuatie-blok na het try-regio (hier onbereikbaar).
            b.setCurrentBlock(tryEnd);
            b.emitReturn(null);
        } else {
            IRBasicBlock entry = b.currentBlock();
            IRBasicBlock body = b.beginBlock("body");
            b.setCurrentBlock(entry);
            b.emitBranch(body);
            b.setCurrentBlock(body);
            b.emitThrow(EXC_TYPE);
        }

        b.endFunction();
        return b.build();
    }

    @Test
    void throwInsideTryUsesInvoke() throws Exception {
        String ll = compileAndReadLl(buildThrowingModule(true), CompileOptions.defaults());

        // Throw binnen een try-regio moet een invoke naar @nabu_throw zijn,
        // zodat de unwinder richting de catch-handler kan leiden.
        assertTrue(ll.contains("invoke void @nabu_throw("),
                "Verwacht invoke naar @nabu_throw binnen try-regio:\n" + ll);
        // Na een noreturn throw moet een unreachable staan.
        assertTrue(ll.contains("unreachable"),
                "Verwacht unreachable na de throw:\n" + ll);
        // De catch-handler moet een landingpad hebben.
        assertTrue(ll.contains("landingpad"),
                "Verwacht een landingpad in de catch-handler:\n" + ll);
        // En geen gewone (non-invoke) call naar @nabu_throw.
        assertFalse(ll.contains("call void @nabu_throw("),
                "Binnen een try-regio mag de throw geen gewone call zijn:\n" + ll);
    }

    @Test
    void uncaughtThrowUsesCallAndUnreachable() throws Exception {
        String ll = compileAndReadLl(buildThrowingModule(false), CompileOptions.defaults());

        // Zonder try-regio is de throw een gewone call (onbevangen).
        assertTrue(ll.contains("call void @nabu_throw("),
                "Verwacht een gewone call naar @nabu_throw (onbevangen):\n" + ll);
        assertTrue(ll.contains("unreachable"),
                "Verwacht unreachable na de onbevangen throw:\n" + ll);
        assertFalse(ll.contains("invoke void @nabu_throw("),
                "Zonder try-regio mag er geen invoke naar @nabu_throw zijn:\n" + ll);
    }

    /**
     * Fase 3.2 (frontend IR-shape): de frontend emitteert na allocatie van het
     * exception-object een Throw met een niet-null result. Dit toont aan dat de
     * backend het gealloceerde object resolvet (i.p.v. de null-placeholder) en
     * de gooi als invoke + unreachable emitteert.
     */
    @Test
    void throwWithAllocatedObjectResolvesValue() throws Exception {
        IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("main", IRType.VOID, List.of(), 0L, false);

        IRBasicBlock entry = b.currentBlock();
        IRBasicBlock tryBody = b.beginBlock("try.body");
        IRBasicBlock tryEnd = b.beginBlock("try.end");
        IRBasicBlock handler = b.beginBlock("try.catch.0");

        b.setCurrentBlock(entry);
        b.emitBranch(tryBody);

        // try.body: alloca het exception-object en gooi dat object.
        b.setCurrentBlock(tryBody);
        IRValue exc = b.emitHeapAlloc("exc", EXC_TYPE);
        tryBody.add(new IRInstruction.Throw(exc, EXC_TYPE, b.currentLocation()));

        b.setCurrentBlock(handler);
        handler.add(0, new IRInstruction.TryCatchRegion(
                tryBody.label(), tryEnd.label(), handler.label(), null, b.currentLocation()));
        b.emitReturn(null);

        b.setCurrentBlock(tryEnd);
        b.emitReturn(null);

        b.endFunction();
        String ll = compileAndReadLl(b.build(), CompileOptions.defaults());

        assertTrue(ll.contains("invoke void @nabu_throw("),
                "Verwacht invoke naar @nabu_throw met gealloceerd object:\n" + ll);
        assertTrue(ll.contains("unreachable"),
                "Verwacht unreachable na de throw:\n" + ll);
    }
}
