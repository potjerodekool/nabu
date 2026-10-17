package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRGlobal;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Emitteert IRGlobal objecten naar LLVM globals.
 *
 * Beide stringrepresentaties worden ondersteund:
 *   - IRGlobal met ConstString initializer → [N x i8] global
 *   - Inline ConstString in IRValue        → via ConstantResolver
 *
 * Alle globals worden opgeslagen in globalValueMap zodat
 * Named-lookups ("@naam") in InstructionEmitter werken.
 */
public class GlobalEmitter {

    private final LLVMModuleRef               mod;
    private final TypeMapper types;
    private final ConstantResolver            constants;
    private final Map<String, LLVMValueRef>   globalValueMap;

    public GlobalEmitter(LLVMModuleRef mod,
                         TypeMapper types,
                         ConstantResolver constants,
                         Map<String, LLVMValueRef> globalValueMap) {
        this.mod            = mod;
        this.types          = types;
        this.constants      = constants;
        this.globalValueMap = globalValueMap;
    }

    /**
     * Emitteert één IRGlobal naar de LLVM-module.
     * Veilig om aan te roepen vóór enige functie-body.
     */
    public void emit(IRGlobal global) {
        // String-literals krijgen speciale behandeling:
        // hun type is [N x i8], niet ptr
        if (global.initializer() instanceof IRValue.ConstString(String value)) {
            emitStringGlobal(global.name(), value, global.linkage(), global.constant());
            constants.registerStringGlobal(global.name(), value);
            return;
        }

        LLVMTypeRef  llvmType   = types.map(global.type());
        LLVMValueRef llvmGlobal = lookupExisting(global.name());

        if (llvmGlobal == null) {
            llvmGlobal = LLVMAddGlobal(mod, llvmType,
                    new BytePointer(global.name()));

            LLVMSetLinkage(llvmGlobal, ConstantResolver.toLLVMLinkage(global.linkage()));
            LLVMSetGlobalConstant(llvmGlobal, global.constant() ? 1 : 0);

            if (global.initializer() != null) {
                LLVMSetInitializer(llvmGlobal,
                    constants.resolveInitializer(global.initializer(), global.linkage()));
            } else {
                LLVMSetInitializer(llvmGlobal, LLVMConstNull(llvmType));
            }
        } else if (global.initializer() != null) {
            // Zelfde naam, tweede keer (definitie naast extern-declaratie
            // uit een andere IRModule): merge de initializer — de initializer
            // van de definitie wint; een null-initializer overschrijft nooit.
            LLVMSetInitializer(llvmGlobal,
                constants.resolveInitializer(global.initializer(), global.linkage()));
        }

        globalValueMap.put("@" + global.name(), llvmGlobal);
    }

    /**
     * Bestaande LLVM-global met deze naam (globalValueMap of de module zelf),
     * zodat identieke namen uit verschillende IRModules niet tot LLVM's
     * automatische hernoeming (`.43`, `.64`, …) leiden.
     */
    private LLVMValueRef lookupExisting(String name) {
        LLVMValueRef existing = globalValueMap.get("@" + name);
        if (existing != null) return existing;
        return LLVMGetNamedGlobal(mod, new BytePointer(name));
    }

    /**
     * Emitteert een string-global via LLVMConstStringInContext.
     * Geen builder of insert-point nodig.
     */
    public void emitStringGlobal(String name, String text,
                                  IRGlobal.Linkage linkage, boolean constant) {
        // Gebruik de cache in ConstantResolver voor deduplicatie
        LLVMValueRef existing = globalValueMap.get("@" + name);
        if (existing != null) return;
/*
        LLVMValueRef strConst = LLVMConstStringInContext(
                constants.resolveString(text, linkage) != null ? null : null, // guard — gebruik directe aanroep:
                (String) null, 0, 0);
*/

        // Directe aanroep zonder cache-omweg:
        // LLVMConstStringInContext → [N x i8] array
        LLVMValueRef rawConst = LLVMConstStringInContext(
                getCtxFromMod(), new BytePointer(text), text.length(), 0);

        LLVMTypeRef  strType  = LLVMTypeOf(rawConst);
        LLVMValueRef global   = LLVMAddGlobal(mod, strType,
                new BytePointer(name));

        LLVMSetLinkage(global, ConstantResolver.toLLVMLinkage(linkage));
        LLVMSetGlobalConstant(global, constant ? 1 : 0);
        LLVMSetInitializer(global, rawConst);

        globalValueMap.put("@" + name, global);
    }

    // LLVMGetModuleContext is beschikbaar in LLVM 21
    private org.bytedeco.llvm.LLVM.LLVMContextRef getCtxFromMod() {
        return LLVMGetModuleContext(mod);
    }
}
