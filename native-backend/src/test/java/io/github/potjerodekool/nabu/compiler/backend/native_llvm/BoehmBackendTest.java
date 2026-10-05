package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GcStrategy.BOEHM: alle heap-allocaties lopen via de Boehm-collector.
 *
 * <p>Het contract van de strategie is bewust klein en controleerbaar:
 * <pre>
 *   alloc                -&gt; GC_malloc  + object-header (type/vtable/itable)
 *   GC_init              -&gt; één keer vóór main, via @llvm.global_ctors
 *   vrijgeven            -&gt; nooit: de collector beslist wanneer iets doodgaat
 * </pre>
 *
 * <p>Het interessante aan BOEHM is juist dat er niets te bewijzen valt over
 * levensduur: er komt geen enkele teller of vroege-vrijgeven-pad in de IR voor.
 * De test houdt daarom vooral vast dát die er níet in zitten, want een
 * onbedoelde {@code free} of {@code nabu_release} zou een object doden dat de
 * collector nog in gebruik heeft.
 */
class BoehmBackendTest {

    private static final IRType BOX = new IRType.Ptr(IRType.I8, "Ltest/Box;");

    @TempDir
    Path tempDir;

    private static CompileOptions boehm() {
        return new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.BOEHM, JavaVersion.MINIMAL_VERSION);
    }

    private static CompileOptions none() {
        return new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.NONE, JavaVersion.MINIMAL_VERSION);
    }

    private String emit(final IRModule module, final String name, final CompileOptions opts)
            throws Exception {
        final Path ll = tempDir.resolve(name + ".ll");
        new NativeLLVMBackend().emitIRText(module, opts, ll);
        NativeToolchain.dump(ll, name + ".ll");
        return Files.readString(ll);
    }

    // -------------------------------------------------------
    // Allocatie loopt via de collector
    // -------------------------------------------------------

    @Test
    void anObjectIsAllocatedByTheCollectorAndGetsAHeader() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        b.emitStore(field(b.emitHeapAlloc("obj", BOX), "x"), b.constInt(1));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final IRModule module = b.build();
        module.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));

        final var ll = emit(module, "boehm_object", boehm());

        assertTrue(ll.contains("call ptr @GC_malloc("),
                "object moet via GC_malloc worden toegewezen:\n" + ll);
        // De header moet in IR gezet worden; de runtime doet dat niet, want die
        // kent de layout niet.
        assertTrue(ll.contains(".type.slot"), "geen type-info in de header:\n" + ll);
        assertTrue(ll.contains(".vtable.slot"), "geen vtable in de header:\n" + ll);
        assertTrue(ll.contains(".itable.slot"), "geen itable in de header:\n" + ll);
        // Ook de teller begint op 0, ook al gebruikt BOEHM 'm niet: een header
        // met ongeïnitialiseerd geheugen erin is een debugging-valkuil.
        assertTrue(ll.contains("store i32 0, ptr %.refcount.slot"), ll);

        // En nergens een malloc: dat zou een object opleveren dat de collector
        // nooit ziet en dus nooit vrijgeeft.
        assertFalse(ll.contains("call ptr @malloc("),
                "malloc onder BOEHM lekt:\n" + ll);
    }

    @Test
    void anArrayIsAllocatedByTheCollectorToo() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        b.emitArrayStore(b.emitAllocaArray(4, IRType.I32), b.constInt(0), b.constInt(7),
                IRType.I32);
        b.emitReturn(b.emitArrayLength(b.emitAllocaArray(4, IRType.I32)));
        b.endFunction();

        final var ll = emit(b.build(), "boehm_array", boehm());

        assertTrue(ll.contains("call ptr @GC_malloc("),
                "array moet via GC_malloc worden toegewezen:\n" + ll);
        assertFalse(ll.contains("call ptr @malloc("), ll);
    }

    // -------------------------------------------------------
    // GC_init bij start
    // -------------------------------------------------------

    @Test
    void theCollectorIsInitialisedBeforeMainRuns() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final var ll = emit(b.build(), "boehm_init", boehm());

        // Een constructor i.p.v. een call in main: dan draait de init ook als
        // de module in een ander programma gelinkt wordt.
        assertTrue(ll.contains("define void @nabu_gc_init_thunk()"),
                "geen GC_init-constructor:\n" + ll);
        assertTrue(ll.contains("call void @GC_init()"),
                "GC_init wordt niet aangeroepen:\n" + ll);
        assertTrue(ll.contains("llvm.global_ctors"),
                "geen @llvm.global_ctors:\n" + ll);
        // Appending, zodat de linker dit array samenvoegt met die uit de
        // runtime of uit andere objecten.
        assertTrue(ll.contains("appending global [1 x { i32, ptr, ptr }]"),
                "ctor-array is niet appending:\n" + ll);
        assertTrue(ll.contains("ptr @nabu_gc_init_thunk"),
                "de constructor is niet geregistreerd:\n" + ll);
    }

    @Test
    void gcInitAndReflectionShareOneCtorArray() throws Exception {
        // Een module mag maar één @llvm.global_ctors hebben; de linker voegt
        // appending-arrays uit verschillende .o's samen, maar twee in één .o
        // zijn een dubbele globale. Dus: GC_init-thunk en de reflectie-
        // registratiefunctie moeten in één array staan.
        final var genDir = tempDir.resolve("META-INF").resolve("native-image")
                .resolve("picocli-generated");
        Files.createDirectories(genDir);
        Files.writeString(genDir.resolve("reflect-config.json"), """
                [
                  {
                    "name" : "greet.GreetCommand",
                    "allDeclaredConstructors" : true,
                    "allPublicMethods" : true
                  }
                ]
                """);

        final Path out = tempDir.resolve("out.o");
        // compile() scant de reflect-config vanaf de output-directory; er zit
        // geen main in, dus er wordt niet gelinkt.
        new NativeLLVMBackend().compile(List.of(TestReflectionModel.greetCommandModule()),
                boehm(), out);

        final String ll = Files.readString(tempDir.resolve("out.ll"));
        final int ctorArrays = ll.split("@llvm.global_ctors", -1).length - 1;
        assertTrue(ctorArrays == 1,
                "er zijn " + ctorArrays + " @llvm.global_ctors-definities:\n" + ll);
        assertTrue(ll.contains("appending global [2 x { i32, ptr, ptr }]"),
                "beide constructors moeten in één appending-array:\n" + ll);
        assertTrue(ll.contains("ptr @nabu_gc_init_thunk"),
                "GC-init ontbreekt in de array:\n" + ll);
        assertTrue(ll.contains("ptr @nabu_reflect_init_greet_GreetCommand"),
                "reflectie-registratie ontbreekt in de array:\n" + ll);
    }

    // -------------------------------------------------------
    // Er wordt nooit vrijgegeven
    // -------------------------------------------------------

    @Test
    void nothingIsEverFreed() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        final IRValue slot = b.emitAlloca("s", BOX);
        // Twee allocs in één slot: precies het scenario waarin REFCOUNT iets
        // zou vrijgeven.
        b.emitStore(slot, b.emitHeapAlloc("a", BOX));
        b.emitStore(slot, b.emitHeapAlloc("b2", BOX));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final IRModule module = b.build();
        module.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));

        final var ll = emit(module, "boehm_no_free", boehm());

        assertFalse(ll.contains("@free("),
                "BOEHM geeft nooit zelf vrij; de collector beslist dat:\n" + ll);
        assertFalse(ll.contains("@nabu_release"),
                "geen release onder BOEHM:\n" + ll);
        assertFalse(ll.contains("@nabu_retain"),
                "geen retain onder BOEHM:\n" + ll);
        assertFalse(ll.contains("nabu.rc."),
                "geen refcount-slotflags of exit-pad onder BOEHM:\n" + ll);
    }

    // -------------------------------------------------------
    // Onbekende (library) klassen
    // -------------------------------------------------------

    @Test
    void anUnknownLibraryClassIsAlsoAllocatedByTheCollector() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        // Geen layout bekend voor java/lang/Exception → de fallback.
        b.emitHeapAlloc("exc", new IRType.Ptr(IRType.I8, "Ljava/lang/Exception;"));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final var ll = emit(b.build(), "boehm_unknown_class", boehm());

        assertTrue(ll.contains("call ptr @GC_malloc("),
                "onbekende klasse moet via GC_malloc:\n" + ll);
        assertFalse(ll.contains("call ptr @malloc("), ll);
        // De runtime vult alleen de header; de allocatie gebeurt in IR, anders
        // zouden exception-objecten buiten de collector vallen.
        assertTrue(ll.contains("call ptr @nabu_init_object("),
                "header wordt niet geïnitialiseerd:\n" + ll);
        assertFalse(ll.contains("@nabu_new_object"),
                "nabu_new_object alloceert zelf en zou dus lekken:\n" + ll);
    }

    // -------------------------------------------------------
    // Consistentie met NONE
    // -------------------------------------------------------

    @Test
    void noneAllocatesOnTheStackAndNotWithTheCollector() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        b.emitHeapAlloc("obj", BOX);
        b.emitHeapAlloc("exc", new IRType.Ptr(IRType.I8, "Ljava/lang/Exception;"));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final IRModule module = b.build();
        module.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));

        final var ll = emit(module, "none_alloc", none());

        assertFalse(ll.contains("@GC_malloc"), ll);
        assertFalse(ll.contains("@GC_init"), ll);
        assertFalse(ll.contains("llvm.global_ctors"), ll);
        // Een onbekende klasse mag geen alloca van één byte worden: het
        // gemapte type is i8, dus een kale `alloca i8` zou de 40-byte header
        // over de stack heen schrijven. De 40 is sizeof(nabu_object) op een
        // 64-bittig target (zie de layout in runtime/nabu_runtime.h); groeit
        // de header, dan moet dit getal mee.
        assertTrue(ll.contains("alloca i8, i64 40"),
                "onbekende klasse alloceert te weinig:\n" + ll);
        assertTrue(ll.contains("call ptr @nabu_init_object("), ll);
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    private static IRValue field(final IRValue obj, final String name) {
        return new IRValue.Values(obj,
                new IRValue.Named(name, IRType.I32, BOX, false, 0));
    }
}
