package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;

/**
 * Normalisatie van call-targets die de frontend onder een eigenaarsgebonden
 * naam schrijft terwijl er native één implementatie bestaat.
 *
 * <p>Voorbeeld: {@code EnumCodeGenerator} maakt in de gegenereerde
 * {@code values()}-methode een {@code $VALUES.clone()}-aanroep met de
 * enum-klasse als owner, dus {@code picocli.CommandLine$ScopeType_clone}.
 * Die owner-keuze is bewust: de JVM-verifier accepteert
 * {@code invokevirtual java/lang/Object.clone()} op een array-receiver alleen
 * wanneer de owner het arraytype is (JVMS §6.5) — zie ook de ASM-backend,
 * die de array-clone-owner juist uit de receiver afleidt.
 *
 * <p>Native bestaat er geen {@code enum_clone}: het is een array-kopie die
 * via {@code java.lang.Object_clone} in de runtime wordt afgehandeld. Zonder
 * normalisatie declareert fase 2.5 tien ad-hoc externen
 * ({@code picocli..._clone}) waarvan er geen enkele wordt gedefinieerd —
 * undefined references bij het linken. Na normalisatie delen al die calls
 * het ene, reeds aanwezige {@code java.lang.Object_clone}-symbool.
 */
final class CallTargets {

    private static final String OBJECT_CLONE = "java.lang.Object_clone";

    private CallTargets() {
    }

    /**
     * Vervangt een array-clone-aanroep met eigenaar door de generieke
     * {@code java.lang.Object_clone}-aanroep. Alle andere calls — inclusief
     * een eigen {@code clone} op een niet-array receiver, zoals
     * {@code java.util.Vector_clone} — blijven ongewijzigd.
     */
    static IRInstruction.Call normalize(final IRInstruction.Call call) {
        final String fn = call.function();
        if (fn == null || OBJECT_CLONE.equals(fn) || !fn.endsWith("_clone")) {
            return call;
        }
        // Bytecode noemt het array-type zelf als eigenaar (`[I.clone()`).
        final boolean arrayOwner = fn.startsWith("[");
        if (!arrayOwner
                && (call.args().isEmpty() || !isArray(call.args().getFirst().type()))) {
            return call;
        }
        // Direct, niet virtueel: de receiver is een array en heeft geen
        // vtable. Bij een receiver die statisch als Object getypeerd is
        // (`Assert.notNull(types).clone()` in picocli) koos de backend anders
        // het clone-slot uit de vtable van Object en sprong hij in data.
        return new IRInstruction.Call(
                CallKind.SPECIAL, call.returnType(), call.paramTypes(),
                call.result(), OBJECT_CLONE, call.args(), call.location());
    }

    private static boolean isArray(final IRType type) {
        return type instanceof IRType.Ptr ptr
                && ptr.jvmDescriptor() != null
                && ptr.jvmDescriptor().startsWith("[");
    }
}
