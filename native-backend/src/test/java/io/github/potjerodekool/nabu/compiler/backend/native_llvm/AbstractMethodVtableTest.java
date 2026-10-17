package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstracte methode in een vtable: slot-deling over de ancestor-keten én een
 * linkbare definitie voor het abstracte symbool.
 *
 * <p>Programma (drie klassen in één batch), de picocli-`AbstractHandler`-vorm:
 * <pre>
 *   class Base  { abstract Base self(); int foo() {...} }   // geen body
 *   class Mid   : Base  { /* erft self(), overridet niet *&#47; }
 *   class Leaf  : Mid   { Base self() {...} }               // override
 * </pre>
 *
 * <p>De twee dingen die hier misgaan zonder de fix:
 *
 * <ol>
 *   <li><b>Overgeërfde slot-functies werden niet als override herkend.</b>
 *       {@code self()} hoort bij {@code Base}, maar staat via {@code Mid} in
 *       Leaf's super-vtable. De override-zoeker zocht de super-functie alleen
 *       in het module van de <i>directe</i> super-klasse; hoort het slot
 *       hoger in de keten, dan werd de functie daar niet gevonden en
 *       kreeg {@code Leaf_self} een <i>extra</i> slot in plaats van het
 *       gedeelde slot — terwijl het abstracte {@code Base_self} (geen body)
 *       op het gedeelde slot bleef staan.</li>
 *   <li><b>Het abstracte symbool had alleen een declaration.</b> Een abstracte
 *       methode krijgt geen entry-block in de IR en dus geen {@code define};
 *       de vtable-constante van {@code Base} en {@code Mid} verwijst er
 *       desondanks naar, wat als undefined reference bij het linken faalt
 *       (de picocli {@code AbstractHandler_self}-fout).</li>
 * </ol>
 */
class AbstractMethodVtableTest {

    @TempDir
    Path tempDir;

    private static final IRType OBJ_TYPE  = new IRType.Ptr(IRType.I8, "Ljava/lang/Object;");
    private static final IRType BASE_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Base;");
    private static final IRType MID_TYPE  = new IRType.Ptr(IRType.I8, "Ltest/Mid;");
    private static final IRType LEAF_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Leaf;");

    /** Base: abstracte {@code self()} (geen body) + concrete virtuele {@code foo()}. */
    private IRModule baseModule() {
        IRBuilder b = new IRBuilder("test/Base");
        b.superType(OBJ_TYPE);
        var thisParam = new IRValue.Temp("this", BASE_TYPE);
        b.beginFunction("Base_self", BASE_TYPE, List.of(thisParam), Flags.ABSTRACT);
        b.endFunction();
        b.beginFunction("Base_foo", IRType.I32, List.of(thisParam), 0L);
        b.emitReturn(b.constInt(1));
        b.endFunction();
        return b.build();
    }

    /** Mid : Base — geen eigen virtuele methoden; erft het abstracte slot. */
    private IRModule midModule() {
        IRBuilder b = new IRBuilder("test/Mid");
        b.superType(BASE_TYPE);
        return b.build();
    }

    /** Leaf : Mid — overridden {@code self()}; hij hoort het gedeelde slot. */
    private IRModule leafModule() {
        IRBuilder b = new IRBuilder("test/Leaf");
        b.superType(MID_TYPE);
        var thisParam = new IRValue.Temp("this", LEAF_TYPE);
        b.beginFunction("Leaf_self", LEAF_TYPE, List.of(thisParam), 0L);
        b.emitReturn(thisParam);
        b.endFunction();
        return b.build();
    }

    @Test
    void inheritedAbstractSlotIsReplacedByTheOverrideAndStaysLinkable() throws Exception {
        String ll = compile(List.of(baseModule(), midModule(), leafModule()));

        // 1. Leaf deelt het slot met de ancestor: de override staat erop en
        //    de abstracte ancestor-functie is uit Leaf's vtable verdwenen.
        String leafVtable = globalInitializer(ll, "_ZNabutest_LeafEvtable");
        assertFalse(leafVtable.isEmpty(),
                "Leaf-vtable niet gevonden. Aanwezige Evtable-initializers:\n"
                        + evtableInitializers(ll));
        assertTrue(leafVtable.contains("@Leaf_self"),
                "Leaf_self moet op het gedeelde slot staan:\n" + leafVtable);
        assertFalse(leafVtable.contains("@Base_self"),
                "De abstracte Base_self hoort niet in Leaf's vtable:\n" + leafVtable);

        // 2. Het slot-index moet over alle drie de vtables gelijk zijn — dat
        //    is precies wat dispatch via de static type Base/Mid mogelijk maakt.
        assertEquals(
                slotIndexOfVtable(ll, "_ZNabutest_BaseEvtable", "Base_self"),
                slotIndexOfVtable(ll, "_ZNabutest_LeafEvtable", "Leaf_self"),
                "Leaf_self moet op hetzelfde slot-index als Base_self staan");

        // 3. Mid overridet niets en erft het abstracte slot wél. Dat symbool
        //    moet een definitie hebben (unreachable-stub), anders linkt de
        //    vtable-constante van Mid/Base niet.
        String midVtable = globalInitializer(ll, "_ZNabutest_MidEvtable");
        assertTrue(midVtable.contains("@Base_self"),
                "Mid erft het abstracte slot:\n" + midVtable);
        assertTrue(ll.contains("define ptr @Base_self"),
                "Het abstracte symbool heeft alleen een declaration:\n" + ll);
    }

    /**
     * Een {@code final} override vervangt het super-slot net als een gewone:
     * {@code final} verbiedt alleen verdere overrides. picocli's pad via
     * {@code Charset.defaultCharset()} riep anders het abstracte
     * {@code CharsetProvider.charsetForName} aan, omdat
     * {@code StandardCharsets.charsetForName} {@code public final} is.
     */
    @Test
    void finalOverrideReplacesTheAbstractSuperSlot() throws Exception {
        IRBuilder b = new IRBuilder("test/Leaf");
        b.superType(MID_TYPE);
        var thisParam = new IRValue.Temp("this", LEAF_TYPE);
        b.beginFunction("Leaf_self", LEAF_TYPE, List.of(thisParam), Flags.FINAL);
        b.emitReturn(thisParam);
        b.endFunction();

        String ll = compile(List.of(baseModule(), midModule(), b.build()));

        String leafVtable = globalInitializer(ll, "_ZNabutest_LeafEvtable");
        assertTrue(leafVtable.contains("@Leaf_self"),
                "De final override hoort op het slot van Base_self:\n" + leafVtable);
        assertFalse(leafVtable.contains("@Base_self"),
                "De abstracte Base_self hoort niet in Leaf's vtable:\n" + leafVtable);
    }

    /**
     * Een klasse zonder expliciete super (impliciet {@code extends Object})
     * erft de vtable-prefix van het gecompileerde java.lang.Object, en een
     * native virtuele methode van Object (hashCode) krijgt een slot dat de
     * override deelt. Anders stond op Object's equals-slot van picocli's
     * ArgSpec diens applyInitialValue, en riep HashMap die aan.
     */
    @Test
    void implicitObjectSubclassSharesObjectsSlots() throws Exception {
        IRType objectType = new IRType.Ptr(IRType.I8, "Ljava/lang/Object;");
        IRBuilder object = new IRBuilder("java.lang.Object");
        var objThis = new IRValue.Temp("this", objectType);
        object.beginFunction("java.lang.Object_equals", IRType.BOOL,
                List.of(objThis, new IRValue.Temp("o", objectType)), 0L);
        object.emitReturn(object.constBool(false));
        object.endFunction();
        object.beginFunction("java.lang.Object_hashCode", IRType.I32, List.of(objThis), Flags.NATIVE);
        object.endFunction();

        IRType fooType = new IRType.Ptr(IRType.I8, "Ltest/Foo;");
        IRBuilder foo = new IRBuilder("test/Foo");
        var fooThis = new IRValue.Temp("this", fooType);
        foo.beginFunction("Foo_aaa", IRType.I32, List.of(fooThis), 0L);
        foo.emitReturn(foo.constInt(1));
        foo.endFunction();
        foo.beginFunction("Foo_hashCode", IRType.I32, List.of(fooThis), 0L);
        foo.emitReturn(foo.constInt(42));
        foo.endFunction();

        String ll = compile(List.of(object.build(), foo.build()));
        int objectHash = slotIndexOfVtable(ll, "_ZNabujava_lang_ObjectEvtable", "java.lang.Object_hashCode");
        assertTrue(objectHash >= 0, "Object.hashCode (native) hoort een vtable-slot:\n" + evtableInitializers(ll));
        assertEquals(objectHash, slotIndexOfVtable(ll, "_ZNabutest_FooEvtable", "Foo_hashCode"),
                "Foo.hashCode hoort op het slot van Object.hashCode:\n" + evtableInitializers(ll));
        assertEquals(slotIndexOfVtable(ll, "_ZNabujava_lang_ObjectEvtable", "java.lang.Object_equals"),
                slotIndexOfVtable(ll, "_ZNabutest_FooEvtable", "java.lang.Object_equals"),
                "Foo erft Object.equals op hetzelfde slot:\n" + evtableInitializers(ll));
    }

    private String compile(List<IRModule> modules) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(modules, options(), out);
        Path ll = tempDir.resolve("out.ll");
        assertTrue(ll.toFile().exists(), ".ll-bestand niet aangemaakt");
        return Files.readString(ll);
    }

    private CompileOptions options() {
        return new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.BOEHM,
                io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION);
    }

    /** De initialiserende constanten van de global met de gegeven naam. */
    private static String globalInitializer(String ll, String name) {
        for (String line : ll.split("\n")) {
            String trimmed = line.trim();
            if ((trimmed.startsWith("@" + name + " =")
                    || trimmed.startsWith("@\"" + name + "\" ="))
                    && trimmed.contains(" = ")) {
                return trimmed.substring(trimmed.indexOf(" = "));
            }
        }
        return "";
    }

    /** Alle Evtable-initializers in het .ll (diagnose bij een gefaalde assert). */
    private static String evtableInitializers(String ll) {
        StringBuilder out = new StringBuilder();
        for (String line : ll.split("\n")) {
            if (line.contains("Evtable") && line.contains(" = ")) {
                out.append(line.trim()).append('\n');
            }
        }
        return out.toString();
    }

    /** Het 0-based slot-index van {@code symbol} in de vtable-initializer. */
    private static int slotIndexOfVtable(String ll, String vtableName, String symbol) {
        String init = globalInitializer(ll, vtableName);
        String[] entries = init.split("ptr @");
        for (int i = 1; i < entries.length; i++) {
            if (entries[i].startsWith(symbol)) {
                return i - 1;
            }
        }
        return -1;
    }
}
