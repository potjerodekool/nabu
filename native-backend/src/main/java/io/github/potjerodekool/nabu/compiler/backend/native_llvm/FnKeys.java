package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.util.List;

import static org.bytedeco.llvm.global.LLVM.LLVMGetNamedFunction;

/**
 * Overload-sleutels voor LLVM-functiedeclaraties.
 *
 * De IR gebruikt `Owner_methode`-namen zonder descriptor, waardoor
 * overloaded JVM-methoden (bv. `Range.min()` en `Range.min(int)`) met de
 * zelfde IR-functienaam terechtkomen. Zonder aparte sleutel deelt LLVM
 * AddFunction de EERSTGEdelareerde signature, waardoor GetParam(i) van
 * een latere overload een garbage-pointer oplevert (jniLLVM-crash).
 *
 * De sleutel is `<fnName>#<paramdesc>`; de param-desc bevat een 'P'
 * slot als de callee impliciete 'this' draagt (callKind != STATIC of
 * callee-side IRFunction params met %this). #
 * '.' is niet te gebruiken (legaal in LLVM-id's), `#` wel uniek.
 */
final class FnKeys {

    private FnKeys() {
    }

    /**
     * De parameterlijst waarmee de callee van {@code call} wordt gedeclareerd.
     *
     * <p>Een niet-statische call krijgt de JVM-receiver als eerste argument en de
     * callee heeft die ook als eerste parameter. De overload-sleutel moet over
     * die lijst worden gebouwd, anders wijst de sleutel naar een declaratie die
     * één argument korter is.
     */
    static List<IRType> callParams(final io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.Call call) {
        final List<IRType> declared = call.paramTypes();
        if (call.callKind() == io.github.potjerodekool.nabu.backend.ir.CallKind.STATIC) {
            return declared;
        }
        final List<IRType> withReceiver = new java.util.ArrayList<>(declared.size() + 1);
        withReceiver.add(new IRType.Ptr(IRType.I8));
        withReceiver.addAll(declared);
        return withReceiver;
    }

    /** Levert de canonieke overload-sleutel: `<fnName>#<types>` */
    static String fnKey(final String fnName, final List<IRType> paramTypes) {
        final var sb = new StringBuilder(fnName).append('#');
        if (paramTypes != null) {
            for (final var t : paramTypes) {
                sb.append(typeKey(t));
            }
        }
        return sb.toString();
    }

    /**
 * Variant van {@link #fnKey} waarin elke pointer als 'P' telt. Een call-site
 * waarvan de receiver-IRType geen descriptor draagt (bv. een `load i8*`)
 * zou anders het symbol missen dat met descriptors is gedeclareerd
 * (`String_substring#PI32I32` vs `#P523beb0aI32I32`).
 */
static String fnKeyLoose(final String fnName, final List<IRType> paramTypes) {
    final var sb = new StringBuilder(fnName).append('#');
    if (paramTypes != null) {
        for (final var t : paramTypes) {
            sb.append(t instanceof IRType.Ptr ? "P" : typeKey(t));
        }
    }
    return sb.toString();
}

/**
 * Deterministische onderscheidingscode voor de parameterlijst van een
 * overloade declaratie: precies dezelfde type-keys als de overload-sleutel
 * uit {@link #fnKey}, maar zonder de functienaam.
 *
 * <p>Dient als suffix voor het LLVM-symbool van een overload; zie
 * {@link #stableSymbol}.
 */
static String symbolSuffix(final List<IRType> paramTypes) {
    final var sb = new StringBuilder();
    if (paramTypes != null) {
        for (final var t : paramTypes) {
            sb.append(typeKey(t));
        }
    }
    return sb.toString();
}

/**
 * Symboolnaam voor een nieuwe declaratie onder {@code fnName} in {@code mod}.
 *
 * <p>LLVM uniekt dubbele functienamen automatisch met een volgnummer uit de
 * symbolentabel ({@code java.lang.String_indexOf.694}). Dat nummer schuift
 * zodra er ook maar één symbol eerder of later bijkomt in de module, waardoor
 * de undefined-symbolen die de runtime moet definiëren bij iedere
 * herbouw weglopen. Daarom krijgt een bezette naam een suffix uit de
 * parameter-keys: deterministisch, en gelijk aan de overload-sleutel die de
 * call-emissie al gebruikt om de juiste functiewaarde te vinden.
 *
 * <p>Een vrije naam blijft ongewijzigd (de bestaande runtime-symbolen zoals
 * {@code java_lang_Exception_init} zijn kaal). Alleen ad-hoc- en
 * overload-declaraties komen hier; namen die al een échte IRFunction dragen
 * worden vóór deze call geblokkeerd (fase 2.5 respectievelijk de
 * key-controle in {@code declareExternalConstructor}).
 */
static String stableSymbol(final LLVMModuleRef mod,
                           final String fnName,
                           final List<IRType> paramTypes) {
    final LLVMValueRef existing = LLVMGetNamedFunction(mod, new BytePointer(fnName));
    if (existing == null || existing.isNull()) {
        return fnName;
    }
    final String suffix = symbolSuffix(paramTypes);
    String candidate = fnName + "$" + suffix;
    // Defensief: alleen bereikbaar wanneer twee declaraties dezelfde
    // suffix produceren (afgeknapte profiellijst); volgorde is
    // deterministisch, dus ook deze uitweg is stabiel.
    int n = 2;
    while (true) {
        final LLVMValueRef clash = LLVMGetNamedFunction(mod, new BytePointer(candidate));
        if (clash == null || clash.isNull()) return candidate;
        candidate = fnName + "$" + suffix + "_" + n++;
    }
}

/**
 * De parameterlijst van een IRFunction, met de *impliciete receiver* genormaliseerd
 * naar een descriptor-loze pointer.
 *
 * <p>De receiver mag nooit meewegen in de overload-sleutel: de declaratie
 * beschrijft `this` met de descriptor van de eigen klasse, terwijl een call-site
 * `this` met het statische receiver-type beschrijft — dat mag een subklasse zijn
 * en hoeft dus niet gelijk te zijn. Zonder normalisatie mist de call zijn eigen
 * symbool (`PI32I32` vs `P523beb0aI32I32`).
 */
/**
 * De overdrachtelijke (expliciete) parameters van {@code fn}: de impliciete
 * receiver wordt weggelaten.
 *
 * <p>De receiver mag nooit meewegen in een overload-sleutel. De declaratie
 * beschrijft `this` met de descriptor van de eigen klas, terwijl een call-site
 * `this` met het statische receiver-type beschrijft — dat mag een subklasse zijn.
 * Vergelijken kan dan niet. Door beide kanten uitsluitend over de *expliciete*
 * parameters te sleutelen, vallen ze samen.
 */
static List<IRType> declaredParamsOf(final IRFunction fn) {
    final List<IRType> out = new java.util.ArrayList<>(fn.params.size());
    for (final var p : fn.params) {
        final var name = IRValue.nameOf(p);
        if (out.isEmpty() && ("%this".equals(name) || "%this$0".equals(name))) {
            continue;
        }
        out.add(p.type());
    }
    return out;
}

static List<IRType> declarationParams(final List<IRType> params, final boolean isStatic) {
    if (isStatic || params == null || params.isEmpty()) return params;
    final List<IRType> out = new java.util.ArrayList<>(params.size());
    out.add(new IRType.Ptr(IRType.I8));
    out.addAll(params.subList(1, params.size()));
    return out;
}

private static String typeKey(final IRType t) {
        if (t == null) {
            return "O";
        }
        if (t instanceof IRType.Int i) {
            return "I" + i.bits();
        }
        if (t instanceof IRType.Float f) {
            return "F" + f.bits();
        }
        if (t instanceof IRType.Bool) {
            return "Z";
        }
        if (t instanceof IRType.Void) {
            return "V";
        }
        if (t instanceof IRType.Ptr p) {
            // Referentietypes onderscheiden: `usage(PrintWriter, ColorScheme)`
            // (void) en `usage(StringBuilder, Help)` (String) hebben allebei
            // drie pointer-parameters. Zonder de descriptor in de sleutel
            // krijgen die dezelfde sleutel en belanden alle bodies in één
            // LLVM-functie. Handmatige IR zonder descriptor houdt de oude 'P'.
            final var desc = p.jvmDescriptor();
            return desc == null ? "P" : "P" + Integer.toHexString(desc.hashCode());
        }
        if (t instanceof IRType.Array a) {
            return "[" + typeKey(a.elem()) + "]";
        }
        return "O";
    }
}
