package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interface-dispatch in de native backend, op IR van de ECHTE frontend.
 *
 * De frontend declareert functies met de <em>simple</em> eigenaar
 * ({@code Impl_value}) terwijl call-sites de <em>gekwalificeerde</em> naam
 * gebruiken ({@code pkg.Outer$Impl_value}). Ook het type van een parameter
 * (bv. de interface-receiver) draagt de JVM-descriptor met slashes, terwijl de
 * module met puntjes genoemd is. Zonder de naam-normalisatie en de
 * functie-resolutie-index in {@link ClassLayouts} vond de backend geen enkele
 * layout en klapte de compile met 'Interface-functie niet gedeclareerd' om.
 *
 * Daarnaast bouwt iedere klasse nu een itable die naar de EIGEN implementatie
 * wijst — met dezelfde slot-index als de call-site gebruikt.
 */
class InterfaceDispatchTest {

    @TempDir
    Path tempDir;

    /** {@code pkg.Outer$IHelp} — interface met één methode {@code value()}. */
    private static final String HELP = "Lpkg/Outer$IHelp;";
    private static final String IMPL = "Lpkg/Outer$Impl;";
    private static final String OTHER = "Lpkg/Outer$Other;";

    private String compileAndReadLl(List<IRModule> modules, CompileOptions opts) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(modules, opts, out);
        Path ll = tempDir.resolve("out.ll");
        assertTrue(ll.toFile().exists(), ".ll bestand niet aangemaakt");
        return Files.readString(ll);
    }

    private CompileOptions boehm() {
        return new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.BOEHM, io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION);
    }

    /**
     * Bouwt de IR zoals de frontend hem voor een geneste interface + twee
     * implementaties oplevert: gedeclareerd met simple owner, aangeroepen met
     * gekwalificeerde naam.
     */
    private List<IRModule> frontendShapedModules() {
        // module pkg.Outer$IHelp: interface met 'int value()' (lege body)
        IRBuilder help = new IRBuilder(Flags.INTERFACE, "pkg.Outer$IHelp");
        help.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        IRType helpType = new IRType.Ptr(IRType.I8, HELP);
        var thisOfHelp = new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("this", helpType);
        help.beginFunction("IHelp_value", IRType.I32, List.of(thisOfHelp), Flags.ABSTRACT);
        help.endFunction();
        IRModule helpModule = help.build();

        // module pkg.Outer$Impl: implementeert de interface
        IRBuilder impl = new IRBuilder("pkg.Outer$Impl");
        impl.interfaces(List.of(new IRType.Ptr(IRType.I8, HELP)));
        IRType implType = new IRType.Ptr(IRType.I8, IMPL);
        impl.beginFunction("Impl_value", IRType.I32, List.of(
                new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("this", implType)), 0L);
        impl.emitReturn(impl.constInt(7));
        impl.endFunction();
        IRModule implModule = impl.build();

        // module pkg.Outer$Other: tweede implementatie, andere waarde
        IRBuilder other = new IRBuilder("pkg.Outer$Other");
        other.interfaces(List.of(new IRType.Ptr(IRType.I8, HELP)));
        IRType otherType = new IRType.Ptr(IRType.I8, OTHER);
        other.beginFunction("Other_value", IRType.I32, List.of(new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("this", otherType)), 0L);
        other.emitReturn(other.constInt(9));
        other.endFunction();
        IRModule otherModule = other.build();

        // module pkg.Outer: de caller doet een interface-call met de
        // gekwalificeerde naam, op een receiver van het interface-type.
        IRBuilder caller = new IRBuilder("pkg.Outer");
        caller.beginFunction("Outer_run", IRType.I32, List.of(
                new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("h", helpType)), 0L);
        var receiver = new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("h", helpType);
        caller.emitCall(CallKind.INTERFACE, "pkg.Outer$IHelp_value", IRType.I32,
                List.of(), List.of(receiver));
        caller.emitReturn(caller.constInt(0));
        caller.endFunction();
        IRModule callerModule = caller.build();

        return List.of(helpModule, implModule, otherModule, callerModule);
    }

    @Test
    void itableOfImplementingClassPointsAtItsOwnImplementation() throws Exception {
        String ll = compileAndReadLl(frontendShapedModules(), boehm());

        // De itable van de implementerende klasse moet naar de eigen functie
        // wijzen, niet naar die van de interface.
        assertTrue(ll.contains("_ZNabupkg_Outer$ImplEitable"),
                "Verwacht itable voor pkg.Outer$Impl:\n" + ll);
        assertTrue(itableMentions(ll, "_ZNabupkg_Outer$ImplEitable", "Impl_value"),
                "De itable van Impl moet @Impl_value bevatten:\n" + ll);
        assertTrue(itableMentions(ll, "_ZNabupkg_Outer$OtherEitable", "Other_value"),
                "De itable van Other moet @Other_value bevatten:\n" + ll);
    }

    /**
     * Een interface zonder eigen methoden die een andere uitbreidt (picocli's
     * {@code IScope extends IGetter}), met een implementerende klasse: een call
     * via het statische type IScope naar de geërfde {@code get()} hoort via de
     * itable te lopen. Eerder telde IScope (geen methoden) en IGetter (alleen
     * via IScope geïmplementeerd) als "zonder implementers", en werd het een
     * directe call naar de body-loze interface-functie.
     */
    @Test
    void callThroughMethodlessSubinterfaceUsesTheItable() throws Exception {
        final String getter = "Lpkg/Outer$IGetter;";
        final String scope = "Lpkg/Outer$IScope;";
        IRBuilder igetter = new IRBuilder(Flags.INTERFACE, "pkg.Outer$IGetter");
        igetter.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        igetter.beginFunction("IGetter_get", IRType.I32, List.of(
                new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("this", new IRType.Ptr(IRType.I8, getter))),
                Flags.ABSTRACT);
        igetter.endFunction();

        IRBuilder iscope = new IRBuilder(Flags.INTERFACE, "pkg.Outer$IScope");
        iscope.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        iscope.interfaces(List.of(new IRType.Ptr(IRType.I8, getter)));

        IRBuilder impl = new IRBuilder("pkg.Outer$UserObject");
        impl.interfaces(List.of(new IRType.Ptr(IRType.I8, scope)));
        impl.beginFunction("UserObject_get", IRType.I32, List.of(
                new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("this",
                        new IRType.Ptr(IRType.I8, "Lpkg/Outer$UserObject;"))), 0L);
        impl.emitReturn(impl.constInt(5));
        impl.endFunction();

        IRBuilder caller = new IRBuilder("pkg.Outer");
        IRType scopeType = new IRType.Ptr(IRType.I8, scope);
        caller.beginFunction("Outer_read", IRType.I32, List.of(
                new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("s", scopeType)), 0L);
        var receiver = new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("s", scopeType);
        var value = caller.emitCall(CallKind.INTERFACE, "pkg.Outer$IGetter_get", IRType.I32,
                List.of(), List.of(receiver));
        caller.emitReturn(value);
        caller.endFunction();

        String ll = compileAndReadLl(List.of(igetter.build(), iscope.build(), impl.build(), caller.build()), boehm());
        String read = ll.substring(ll.indexOf("define i32 @Outer_read("));
        read = read.substring(0, read.indexOf("\n}"));
        assertTrue(read.contains(".ifnptr"), "De call via IScope moet de itable gebruiken:\n" + read);
        assertFalse(read.contains("@IGetter_get("), "Geen directe call naar IGetter_get:\n" + read);
        assertTrue(itableMentions(ll, "_ZNabupkg_Outer$UserObjectEitable", "UserObject_get"),
                "De itable van UserObject moet @UserObject_get bevatten:\n" + ll);
    }

    @Test
    void interfaceCallLoadsItableIndirectlyWithoutDirectCall() throws Exception {
        String ll = compileAndReadLl(frontendShapedModules(), boehm());

        assertTrue(ll.contains(".itable"), "De interface-call moet de itable laden:\n" + ll);
        assertTrue(ll.contains(".ifnptr"), "De interface-call moet indirect aanroepen:\n" + ll);
        assertFalse(ll.contains("call i32 @\"pkg.Outer$IHelp_value\""),
                "Geen directe call naar de interface-functie verwacht:\n" + ll);
    }

    /**
     * Interface <b>zonder</b> implementerende klasse in de batch: de itable van
     * elke runtime-instantie is dan leeg, dus de call moet DIRECT naar de
     * interface-functie vallen (declare/default-body of C-implementatie) — zo
     * dispatchen annotatie-instanties uit de C-runtime.
     */
    @Test
    void interfaceWithoutImplementersDispatchesDirectly() throws Exception {
        IRBuilder help = new IRBuilder(Flags.INTERFACE, "pkg.Outer$IHelp");
        help.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        IRType helpType = new IRType.Ptr(IRType.I8, HELP);
        var thisOfHelp = new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("this", helpType);
        help.beginFunction("IHelp_value", IRType.I32, List.of(thisOfHelp), Flags.ABSTRACT);
        help.endFunction();
        IRModule helpModule = help.build();

        IRBuilder caller = new IRBuilder("pkg.Outer");
        caller.beginFunction("Outer_run", IRType.I32, List.of(
                new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("h", helpType)), 0L);
        var receiver = new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("h", helpType);
        caller.emitCall(CallKind.INTERFACE, "pkg.Outer$IHelp_value", IRType.I32,
                List.of(), List.of(receiver));
        caller.emitReturn(caller.constInt(0));
        caller.endFunction();

        String ll = compileAndReadLl(List.of(helpModule, caller.build()), boehm());

        assertTrue(ll.contains("call i32 @IHelp_value"),
                "Directe call naar de interface-functie verwacht:\n" + ll);
        assertFalse(ll.contains(".ifnptr"),
                "Geen itable-dispatch verwacht bij ontbrekende implementer:\n" + ll);
    }

    /**
     * Een klasse met zowel een methode {@code init} als een constructor: beide
     * heten {@code Impl_init} in de IR. Zonder onderscheid zou de vtable naar de
     * constructor wijzen.
     */
    @Test
    void vtablePointsAtTheMethodWhenAClassHasBothInitAndConstructor() throws Exception {
        IRType implType = new IRType.Ptr(IRType.I8, "Lpkg/Impl;");

        // De frontend schrijft de constructor met dezelfde IR-naam als een
        // methode 'init': beide heten 'Impl_init'.
        IRBuilder builder = new IRBuilder("pkg.Impl");
        var thisOfImpl = new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("this", implType);
        var argOfImpl = new io.github.potjerodekool.nabu.backend.ir.values.IRValue.Temp("a", IRType.I32);
        builder.beginFunction("Impl_init", IRType.VOID, List.of(thisOfImpl, argOfImpl), 0L);
        builder.endFunction();
        builder.beginFunction("Impl_init", IRType.VOID, List.of(thisOfImpl, argOfImpl), 0L, true);
        builder.endFunction();
        IRModule module = builder.build();

        String ll = compileAndReadLl(List.of(module), boehm());

        // De vtable moet de methode bevatten, niet het constructor-symbool.
        String vtable = globalInitializer(ll, "_ZNabupkg_ImplEvtable");
        assertTrue(vtable.contains("@Impl_init"),
                "Verwacht @Impl_init (de methode) in de vtable:\n" + ll);
        assertFalse(vtable.contains("Impl_init$ctor"),
                "De constructor mag niet in de vtable staan:\n" + ll);
        // De constructor bestaat naast de methode onder een eigen symbool.
        assertTrue(ll.contains("define void @\"Impl_init$ctor\""),
                "Constructor-symbool ontbreekt:\n" + ll);
    }

    /** De initialiserende constanten van de global met de gegeven naam. */
    private static String globalInitializer(String ll, String name) {
        // LLVM quoteert een symboolnaam alleen als dat nodig is (bv. bij een '$').
        int at = ll.indexOf("@\"" + name + "\"");
        int quoted = at < 0 ? at : at + 1;
        if (quoted < 0) at = ll.indexOf("@" + name);
        if (at < 0) return "";
        int start = ll.indexOf(" = ", at);
        if (start < 0) return "";
        int end = ll.indexOf('\n', start);
        return ll.substring(start, end < 0 ? ll.length() : end);
    }

    /**
     * Objecten uit de C-runtime (bv. een java.lang.reflect.Field) hebben geen
     * eigen itable, maar belanden wél achter een interface-referentie
     * ({@code AnnotatedElement.isAnnotationPresent}). De runtime geeft ze de
     * library-itable, waarin elke slot naar de functie van de interface zelf
     * wijst — zoals een directe aanroep. Een abstracte methode uit de eigen
     * sources heeft geen definitie: die slot blijft leeg (anders een
     * undefined reference bij het linken).
     */
    @Test
    void libraryItableIsRegisteredAndSkipsAbstractSourceMethods() throws Exception {
        String ll = compileAndReadLl(frontendShapedModules(), boehm());

        assertTrue(ll.contains("@_ZNabuLibraryEitable = internal constant"),
                "Verwacht een library-itable: " + ll);
        assertFalse(itableMentions(ll, "_ZNabuLibraryEitable", "IHelp_value"),
                "Een abstracte source-methode heeft geen definitie om naar te wijzen: " + ll);
        assertTrue(ll.contains("call void @nabu_set_object_itable(ptr @_ZNabuLibraryEitable)"),
                "De library-itable moet aan de runtime worden doorgegeven: " + ll);
    }

    /** Of de global met de gegeven naam in zijn initialisator naar {@code symbol} verwijst. */
    private static boolean itableMentions(String ll, String globalName, String symbol) {
        return globalInitializer(ll, globalName).contains(symbol);
    }
}