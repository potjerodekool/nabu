package io.github.potjerodekool.nabu.compiler.backend.asm2;

import io.github.potjerodekool.nabu.compiler.backend.CompileException;
import io.github.potjerodekool.nabu.compiler.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.backend.ir.Optimizer;
import io.github.potjerodekool.nabu.compiler.backend.ir.SsaBuilder;
import io.github.potjerodekool.nabu.compiler.ir.CallKind;
import io.github.potjerodekool.nabu.compiler.ir.IRBuilder;
import io.github.potjerodekool.nabu.compiler.ir.IRField;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import io.github.potjerodekool.nabu.compiler.lang.Flags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Volledige pipeline: IR → SsaBuilder → Optimizer → Asm2Backend → uitvoer.
 * Specifiek gericht op SSA/phi-getransformeerde IR (multi-dot namen).
 */
class Asm2PipelineTest {

    @TempDir
    Path tempDir;

    private IRBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new IRBuilder("test.TestModule");
        builder.setLocation("test.lang", 1, 1);
    }

    // -------------------------------------------------------
    // Do-while-lus met phi's (SsaBuilder + PhiElimination)
    // -------------------------------------------------------

    /**
     * fn sum(n: i32) -> i32:
     *   i = 0; s = 0
     *   loop:
     *     s = s + i
     *     i = i + 1
     *     if (i < n) loop else exit
     *   exit: return s
     */
    @Test
    void doWhilePhiLoop() throws Exception {
        final var n = new IRValue.Temp("%n", IRType.I32);
        builder.beginFunction("sum", IRType.I32, List.of(n), true);

        final var entry = builder.currentBlock();
        final var iPtr = builder.emitAlloca("i", IRType.I32);
        final var sPtr = builder.emitAlloca("sum", IRType.I32);
        builder.emitStore(iPtr, builder.constInt(0));
        builder.emitStore(sPtr, builder.constInt(0));

        final var body = builder.beginBlock("body");
        final var exit = builder.beginBlock("exit");

        builder.setCurrentBlock(entry);
        builder.emitBranch(body);

        builder.setCurrentBlock(body);
        final var i = builder.emitLoad(iPtr);
        final var s = builder.emitLoad(sPtr);
        final var add = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, s, i);
        builder.emitStore(sPtr, add);

        final var one = builder.constInt(1);
        final var next = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, i, one);
        builder.emitStore(iPtr, next);

        final var cond = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, next, n);
        builder.emitCondBranch(cond, body, exit);

        builder.setCurrentBlock(exit);
        builder.emitReturn(builder.emitLoad(sPtr));
        builder.endFunction();

        final var module = optimize(builder.build());

        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("sum", int.class);
            method.trySetAccessible();
            assertEquals(0, (int) method.invoke(null, 1));
            assertEquals(10, (int) method.invoke(null, 5));
            assertEquals(45, (int) method.invoke(null, 10));
        });
    }

    // -------------------------------------------------------
    // Geneste if-else (diamond-CFG, meerdere returns)
    // -------------------------------------------------------

    /**
     * fn classify(x: i32) -> i32:
     *   if (x < 0) return -1
     *   if (x > 100) return 1
     *   return 0
     */
    @Test
    void nestedIfElse() throws Exception {
        final var x = new IRValue.Temp("%x", IRType.I32);
        builder.beginFunction("classify", IRType.I32, List.of(x), true);

        final var entry = builder.currentBlock();
        final var neg = builder.beginBlock("neg");
        final var posEntry = builder.beginBlock("posEntry");
        final var big = builder.beginBlock("big");
        final var small = builder.beginBlock("small");

        builder.setCurrentBlock(entry);
        final var negCond = builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.LT, x, builder.constInt(0));
        builder.emitCondBranch(negCond, neg, posEntry);

        builder.setCurrentBlock(neg);
        builder.emitReturn(builder.constInt(-1));

        builder.setCurrentBlock(posEntry);
        final var bigCond = builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.GT, x, builder.constInt(100));
        builder.emitCondBranch(bigCond, big, small);

        builder.setCurrentBlock(big);
        builder.emitReturn(builder.constInt(1));

        builder.setCurrentBlock(small);
        builder.emitReturn(builder.constInt(0));

        builder.endFunction();

        final var module = optimize(builder.build());

        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("classify", int.class);
            method.trySetAccessible();
            assertEquals(-1, (int) method.invoke(null, -5));
            assertEquals(0, (int) method.invoke(null, 50));
            assertEquals(1, (int) method.invoke(null, 150));
        });
    }

    // -------------------------------------------------------
    // Statisch veld: store + load via een globale variabele
    // -------------------------------------------------------

    @Test
    void staticFieldStoreLoad() throws Exception {
        final var moduleType = new IRType.Ptr(IRType.I8, "Ltest/TestModule;");

        builder.beginFunction("main", IRType.I32, List.of(), true);
        final var counter = builder.declareGlobal(
                "counter", IRType.I32,
                IRBuilder.defaultInitializer(IRType.I32),
                moduleType,
                true
        );
        builder.emitStore(counter, builder.constInt(42));
        builder.emitReturn(builder.emitLoad(counter));
        builder.endFunction();

        final var module = builder.build();
        module.emitField(IRField.field(
                Flags.STATIC | Flags.PUBLIC,
                "counter",
                IRType.I32,
                null
        ));

        // Geen SsaBuilder: globale velden worden niet als allocas behandeld.
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("main");
            method.trySetAccessible();
            assertEquals(42, (int) method.invoke(null));
        });
    }

    // -------------------------------------------------------
    // Constructor-aanroep: heapalloc + <init> (NEW, DUP, INVOKESPECIAL)
    // -------------------------------------------------------

    /**
     * new ArrayList() toegekend en daarna gebruikt: NEW, DUP, INVOKESPECIAL
     * en de referentie blijft bewaard in het heapalloc-slot.
     */
    @Test
    void heapAllocWithConstructor() throws Exception {
        final var listType = new IRType.Ptr(IRType.I8, "Ljava/util/ArrayList;");

        builder.beginFunction("makeList", IRType.I32, List.of(), true);
        final var list = builder.emitHeapAlloc("new_obj", listType);
        builder.emitCall(
                CallKind.SPECIAL,
                "java_util_ArrayList_init",
                IRType.VOID,
                List.of(),
                List.of()
        );
        final var size = builder.emitCall(
                CallKind.VIRTUAL,
                "java_util_ArrayList_size",
                IRType.I32,
                List.of(),
                List.of(list)
        );
        builder.emitReturn(size);
        builder.endFunction();

        final var module = builder.build();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("makeList");
            method.trySetAccessible();
            assertEquals(0, (int) method.invoke(null));
        });
    }

    /**
     * new ArrayList() zonder toekenning: NEW, DUP, INVOKESPECIAL en daarna
     * POP van de ongebruikte referentie.
     */
    @Test
    void heapAllocDiscarded() throws Exception {
        final var listType = new IRType.Ptr(IRType.I8, "Ljava/util/ArrayList;");

        builder.beginFunction("makeList", IRType.I32, List.of(), true);
        builder.emitHeapAlloc("tmp", listType);
        builder.emitCall(
                CallKind.SPECIAL,
                "java_util_ArrayList_init",
                IRType.VOID,
                List.of(),
                List.of()
        );
        builder.emitReturn(builder.constInt(0));
        builder.endFunction();

        final var module = builder.build();
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("makeList");
            method.trySetAccessible();
            assertEquals(0, (int) method.invoke(null));
        });
    }

    // -------------------------------------------------------
    // For-lus met constructor, casts en virtuele aanroepen (regressie:
    // NegativeArraySizeException in ASM Frame.merge)
    // -------------------------------------------------------

    /**
     * getPets() zoals de gebruiker het compileert (met echte JDK-types):
     *   result = new ArrayList()
     *   result.add("a")                 // niet-void aanroep als statement → Pop
     *   resultList = new ArrayList()
     *   for (i = 0; i < result.size(); i++) {
     *       pet = (Object) result.get(i)
     *       resultList.add(pet)         // niet-void aanroep als statement → Pop
     *   }
     *   return resultList.size()
     */
    @Test
    void loopWithConstructorAndCalls() throws Exception {
        final var listType = new IRType.Ptr(IRType.I8, "Ljava/util/ArrayList;");
        final var objectType = new IRType.Ptr(IRType.I8, "Ljava/lang/Object;");

        builder.beginFunction("getPets", IRType.I32, List.of(), true);

        // entry: result = new ArrayList(), resultList = new ArrayList(),
        //        result.add("a"), i = 0
        final var entry = builder.currentBlock();
        final var resultPtr = builder.emitAlloca("result", listType);
        final var resultListPtr = builder.emitAlloca("resultList", listType);
        final var iPtr = builder.emitAlloca("i", IRType.I32);

        final var result = builder.emitHeapAlloc("new_result", listType);
        builder.emitCall(
                CallKind.SPECIAL,
                "java_util_ArrayList_init",
                IRType.VOID,
                List.of(),
                List.of()
        );
        builder.emitStore(resultPtr, result);

        final var firstAdd = builder.emitCall(
                CallKind.VIRTUAL,
                "java_util_ArrayList_add",
                IRType.BOOL,
                List.of(objectType),
                List.of(result, builder.constString("a"))
        );
        builder.emitPop();

        final var resultList = builder.emitHeapAlloc("new_resultList", listType);
        builder.emitCall(
                CallKind.SPECIAL,
                "java_util_ArrayList_init",
                IRType.VOID,
                List.of(),
                List.of()
        );
        builder.emitStore(resultListPtr, resultList);

        builder.emitStore(iPtr, builder.constInt(0));

        final var cond = builder.beginBlock("for.cond");
        final var body = builder.beginBlock("for.body");
        final var exit = builder.beginBlock("for.exit");

        builder.setCurrentBlock(entry);
        builder.emitBranch(cond);

        // for.cond: i < result.size()
        builder.setCurrentBlock(cond);
        final var iLoad = builder.emitLoad(iPtr);
        final var rLoad = builder.emitLoad(resultPtr);
        final var size = builder.emitCall(
                CallKind.VIRTUAL,
                "java_util_ArrayList_size",
                IRType.I32,
                List.of(),
                List.of(rLoad)
        );
        final var lt = builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.LT, iLoad, size);
        builder.emitCondBranch(lt, body, exit);

        // for.body
        builder.setCurrentBlock(body);
        final var iBodyLoad = builder.emitLoad(iPtr);
        final var rBodyLoad = builder.emitLoad(resultPtr);
        final var pet = builder.emitCall(
                CallKind.VIRTUAL,
                "java_util_ArrayList_get",
                objectType,
                List.of(IRType.I32),
                List.of(rBodyLoad, iBodyLoad)
        );
        final var petCast = builder.emitCast(pet, objectType);
        final var rlLoad = builder.emitLoad(resultListPtr);
        final var addResult = builder.emitCall(
                CallKind.VIRTUAL,
                "java_util_ArrayList_add",
                IRType.BOOL,
                List.of(objectType),
                List.of(rlLoad, petCast)
        );
        builder.emitPop();
        final var inc = builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.ADD, iBodyLoad, builder.constInt(1));
        builder.emitStore(iPtr, inc);
        builder.emitBranch(cond);

        // for.exit: return resultList.size()
        builder.setCurrentBlock(exit);
        final var finalSize = builder.emitCall(
                CallKind.VIRTUAL,
                "java_util_ArrayList_size",
                IRType.I32,
                List.of(),
                List.of(builder.emitLoad(resultListPtr))
        );
        builder.emitReturn(finalSize);
        builder.endFunction();

        final var module = optimize(builder.build());
        final var classFileName = compileDefault(module);

        loadClass(classFileName, module, clazz -> {
            final var method = clazz.getDeclaredMethod("getPets");
            method.trySetAccessible();
            assertEquals(1, (int) method.invoke(null));
        });
    }

    private IRModule optimize(final IRModule module) {
        SsaBuilder.run(module);
        return Optimizer.optimize(module);
    }
    private Path compileDefault(final IRModule module) throws CompileException {
        final var classFileName = tempDir.resolve(module.name + ".class");
        final var directory = classFileName.getParent();
        new Asm2Backend()
                .compile(module, CompileOptions.defaults(), directory);

        return classFileName;
    }

    private void loadClass(final Path classFileName,
                           final IRModule module,
                           final ClassConsumer classConsumer) throws Exception {
        final var url = classFileName.getParent().toUri().toURL();

        try (final var loader = new URLClassLoader(
                new URL[]{url}, getClass().getClassLoader())) {
            final var clazz = loader.loadClass(module.name);
            classConsumer.accept(clazz);
        }
    }

    @FunctionalInterface
    interface ClassConsumer {
        void accept(Class<?> clazz) throws Exception;
    }
}
