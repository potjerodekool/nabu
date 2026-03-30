package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.ir.IRGlobal;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.util.HashMap;
import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Resolveert IRValue constanten naar LLVMValueRef objecten.
 *
 * Ondersteunt:
 *   - ConstInt  (i32 / i64)
 *   - ConstFloat (f32 / f64)
 *   - ConstBool (i1)
 *   - ConstString als inline literal EN als IRGlobal
 *   - ConstNull
 *   - ConstUndef
 *
 * String-globals worden gecached en hergebruikt (deduplicatie).
 * Gebruikt GEEN builder-aanroepen — veilig buiten functie-bodies.
 */
public class ConstantResolver {

    private final LLVMContextRef ctx;
    private final LLVMModuleRef mod;
    private final io.github.potjerodekool.nabu.compiler.backend.native_llvm.TypeMapper types;

    // Cache: string-inhoud → LLVMValueRef van de global
    private final Map<String, LLVMValueRef> stringCache = new HashMap<>();

    // Gedeeld met de emitter: alle globals onder "@naam"
    private final Map<String, LLVMValueRef> globalValueMap;

    public ConstantResolver(LLVMContextRef ctx,
                            LLVMModuleRef mod,
                            TypeMapper types,
                            Map<String, LLVMValueRef> globalValueMap) {
        this.ctx            = ctx;
        this.mod            = mod;
        this.types          = types;
        this.globalValueMap = globalValueMap;
    }

    /**
     * Resolveert een constante IRValue naar een LLVMValueRef.
     * Gooit een uitzondering voor niet-constante waarden.
     */
    public LLVMValueRef resolve(IRValue value) {
        return switch (value) {
            case IRValue.ConstInt c ->
                LLVMConstInt(types.map(c.type()), c.value(), 1);

            case IRValue.ConstFloat c ->
                LLVMConstReal(types.map(c.type()), c.value());

            case IRValue.ConstBool c ->
                LLVMConstInt(LLVMInt1TypeInContext(ctx), c.value() ? 1 : 0, 0);

            case IRValue.ConstNull c ->
                LLVMConstNull(types.map(c.type()));

            case IRValue.ConstUndef c ->
                LLVMGetUndef(types.map(c.type()));

            case IRValue.ConstString c ->
                resolveString(c.value(), IRGlobal.Linkage.PRIVATE);

            default -> throw new IllegalArgumentException(
                "Geen constante: " + value.getClass().getSimpleName());
        };
    }

    /**
     * Maakt een string-global aan of hergebruikt een bestaande.
     * Veilig om buiten een functie-body aan te roepen.
     *
     * Resultaat is een ptr naar de eerste byte van [N x i8].
     */
    public LLVMValueRef resolveString(String text, IRGlobal.Linkage linkage) {
        return stringCache.computeIfAbsent(text, s -> {
            String name = ".str." + Math.abs(s.hashCode());

            // LLVMConstStringInContext maakt [N x i8] — geen builder nodig
            LLVMValueRef strConst = LLVMConstStringInContext(ctx,
                    new BytePointer(s), s.length(), 0);

            LLVMTypeRef strType  = LLVMTypeOf(strConst);
            LLVMValueRef global   = LLVMAddGlobal(mod, strType,
                    new BytePointer(name));

            LLVMSetLinkage(global, toLLVMLinkage(linkage));
            LLVMSetGlobalConstant(global, 1);
            LLVMSetInitializer(global, strConst);

            // Registreer zodat Named-lookups werken
            globalValueMap.put("@" + name, global);
            return global;
        });
    }

    /**
     * Resolveert een IRGlobal-initializer naar een LLVMValueRef.
     * Gebruikt LLVMConst* — geen builder-aanroepen.
     */
    public LLVMValueRef resolveInitializer(IRValue value, IRGlobal.Linkage linkage) {
        return switch (value) {
            case IRValue.ConstString c -> resolveString(c.value(), linkage);
            default                   -> resolve(value);
        };
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    public static int toLLVMLinkage(IRGlobal.Linkage linkage) {
        return switch (linkage) {
            case EXTERNAL -> LLVMExternalLinkage;
            case INTERNAL -> LLVMInternalLinkage;
            case PRIVATE  -> LLVMPrivateLinkage;
        };
    }
}
