package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRGlobal;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.util.HashMap;
import java.util.List;
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
                resolveJavaString(c.value());

            case IRValue.ConstClass c ->
                // ConstClass wordt een pointer naar een string met de
                // klassenaam. De IRType-string is "ptr"; de JVM-descriptor
                // ("Lpkg/Cls;") is leidend en wordt naar de interne naam
                // (pkg/Cls) genormaliseerd — dat is het formaat van de
                // reflectie-registry en van nabu_instanceof.
                resolveString(classNameFromDescriptor(c.type()), IRGlobal.Linkage.PRIVATE);

            case IRValue.Values v ->
                // Meerdere waarden: retourneer de laatste waarde
                resolveLastValue(v.values());

            default -> throw new IllegalArgumentException(
                "Not a constant: " + value.getClass().getSimpleName());
        };
    }

    /**
     * Resolveert meerdere waarden en retourneert de laatste.
     */
    private LLVMValueRef resolveLastValue(List<IRValue> values) {
        LLVMValueRef last = null;
        for (IRValue v : values) {
            last = resolve(v);
        }
        return last;
    }

    /**
     * Levert de interne klassenaam uit het type van een {@code ConstClass}:
     * JVM-descriptor {@code Lpkg/Cls;} → {@code pkg/Cls}. Zonder descriptor
     * valt terug op de generieke type-string (oude gedrag).
     */
    static String classNameFromDescriptor(final IRType type) {
        if (!(type instanceof IRType.Ptr ptr) || ptr.jvmDescriptor() == null) {
            return type.toString();
        }
        final String desc = ptr.jvmDescriptor();
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1);
        }
        // Een array houdt zijn volledige JVM-descriptor als naam
        // ("[Ljava/lang/String;", "[C"), zoals Class.getName() die (met slashes)
        // geeft. Eerder werd het element half uitgepakt ("[java/lang/String"),
        // een vorm die Class.isArray()/getComponentType() niet herkenden:
        // picocli's String[].class gaf dan null als componenttype.
        return desc;
    }

    /**
     * Legt vast hoe een java.lang.String-object eruitziet, zodra de klasse
     * (uit JDK-bytecode) een layout heeft. Vanaf dan is een Java-string-
     * literal een statisch String-object en geen kale C-string meer.
     */
    public void setStringLayout(final LLVMTypeRef stringStruct,
                                final LLVMTypeRef headerType,
                                final LLVMValueRef typeInfo,
                                final LLVMValueRef vtable,
                                final LLVMValueRef itable) {
        // Verwacht: { header, byte[] value, byte coder, ... }.
        if (stringStruct == null || typeInfo == null
                || LLVMCountStructElementTypes(stringStruct) < 3
                || LLVMGetTypeKind(LLVMStructGetTypeAtIndex(stringStruct, 1)) != LLVMPointerTypeKind
                || LLVMGetTypeKind(LLVMStructGetTypeAtIndex(stringStruct, 2)) != LLVMIntegerTypeKind) {
            return;
        }
        this.stringStruct = stringStruct;
        this.stringHeaderType = headerType;
        this.stringTypeInfo = typeInfo;
        this.stringVtable = vtable;
        this.stringItable = itable;
    }

    /**
     * Onthoudt de tekst van een string-literal-global ({@code .str.<module>.<n>}).
     * Die globals worden als C-string geëmitteerd vóórdat de String-layout
     * bestaat; een verwijzing ernaar als Java-waarde gaat daarom via
     * {@link #javaStringForGlobal}.
     */
    public void registerStringGlobal(final String name, final String text) {
        final String plain = name.startsWith("@") ? name.substring(1) : name;
        if (plain.startsWith(".str.")) {
            stringGlobalTexts.put(plain, text);
        }
    }

    /**
     * Het String-object voor een string-literal-global, of null als de naam
     * geen literal is, String niet is meegecompileerd of er nu een C-string
     * gevraagd wordt.
     */
    public LLVMValueRef javaStringForGlobal(final String name) {
        if (stringStruct == null || rawStrings || name == null) return null;
        final String text = stringGlobalTexts.get(
                name.startsWith("@") ? name.substring(1) : name);
        return text != null ? resolveJavaString(text) : null;
    }

    /**
     * Tijdelijk kale C-strings voor string-constanten: de argumenten van een
     * C-runtime-helper (type- en veldnamen) zijn {@code const char*}.
     */
    /** Of operanden nu als kale C-strings gaan (argumenten van een {@code nabu_*}-helper). */
    public boolean isRawStrings() {
        return rawStrings;
    }

    public void setRawStrings(final boolean rawStrings) {
        this.rawStrings = rawStrings;
    }

    /**
     * Een Java-string-literal: een statisch java.lang.String-object als de
     * klasse is meegecompileerd, anders de C-string van {@link #resolveString}.
     */
    public LLVMValueRef resolveJavaString(final String text) {
        if (stringStruct == null || rawStrings) {
            return resolveString(text, IRGlobal.Linkage.PRIVATE);
        }
        return javaStringCache.computeIfAbsent(text, this::emitJavaString);
    }

    private LLVMValueRef emitJavaString(final String text) {
        // Zelfde codering als java.lang.String: LATIN1 als elk char < 256 is,
        // anders UTF16 (little endian).
        boolean latin1 = true;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0xFF) {
                latin1 = false;
                break;
            }
        }
        final byte[] bytes = new byte[latin1 ? text.length() : text.length() * 2];
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (latin1) {
                bytes[i] = (byte) c;
            } else {
                bytes[2 * i] = (byte) c;
                bytes[2 * i + 1] = (byte) (c >> 8);
            }
        }
        final String suffix = Integer.toHexString(text.hashCode()) + "." + javaStringCache.size();

        // byte[]: i32 lengte op offset 0, i32 elementgrootte (1) op offset 4,
        // de data op offset 8 (zie storeArrayElemSize in InstructionEmitter).
        // The upper 24 bits of the element-size word are the array type id; "[B" is always id 1
        // (InstructionEmitter.RUNTIME_ARRAY_TYPES), so the array of a literal is a byte[] for getClass().
        final LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
        final LLVMTypeRef i8 = LLVMInt8TypeInContext(ctx);
        final LLVMValueRef[] byteValues = new LLVMValueRef[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            byteValues[i] = LLVMConstInt(i8, bytes[i] & 0xFF, 0);
        }
        final LLVMValueRef data = LLVMConstArray(i8,
                new PointerPointer<>(byteValues), bytes.length);
        final LLVMValueRef arrayConst = LLVMConstStructInContext(ctx,
                new PointerPointer<>(new LLVMValueRef[]{
                        LLVMConstInt(i32, bytes.length, 0), LLVMConstInt(i32, 1 | (1 << 8), 0), data}),
                3, 0);
        final LLVMValueRef arrayGlobal = LLVMAddGlobal(mod, LLVMTypeOf(arrayConst),
                new BytePointer(".jstr.bytes." + suffix));
        LLVMSetLinkage(arrayGlobal, LLVMPrivateLinkage);
        LLVMSetInitializer(arrayGlobal, arrayConst);

        // Header: type-info, vtable, itable, en de rest op nul.
        final int headerFields = LLVMCountStructElementTypes(stringHeaderType);
        final LLVMValueRef[] header = new LLVMValueRef[headerFields];
        header[0] = stringTypeInfo;
        header[1] = stringVtable != null ? stringVtable
                : LLVMConstNull(LLVMStructGetTypeAtIndex(stringHeaderType, 1));
        header[2] = stringItable != null ? stringItable
                : LLVMConstNull(LLVMStructGetTypeAtIndex(stringHeaderType, 2));
        for (int i = 3; i < headerFields; i++) {
            header[i] = LLVMConstNull(LLVMStructGetTypeAtIndex(stringHeaderType, i));
        }

        final int fields = LLVMCountStructElementTypes(stringStruct);
        final LLVMValueRef[] values = new LLVMValueRef[fields];
        values[0] = constStruct(stringHeaderType, header);
        values[1] = arrayGlobal;
        values[2] = LLVMConstInt(LLVMStructGetTypeAtIndex(stringStruct, 2), latin1 ? 0 : 1, 0);
        for (int i = 3; i < fields; i++) {
            values[i] = LLVMConstNull(LLVMStructGetTypeAtIndex(stringStruct, i));
        }

        // Niet constant: String.hashCode() bewaart de hash in het object.
        final LLVMValueRef global = LLVMAddGlobal(mod, stringStruct,
                new BytePointer(".jstr." + suffix));
        LLVMSetLinkage(global, LLVMPrivateLinkage);
        LLVMSetInitializer(global, constStruct(stringStruct, values));
        return global;
    }

    private LLVMValueRef constStruct(final LLVMTypeRef type, final LLVMValueRef[] values) {
        final PointerPointer<LLVMValueRef> pp = new PointerPointer<>(values);
        final BytePointer name = LLVMGetStructName(type);
        return name != null && !name.isNull()
                ? LLVMConstNamedStruct(type, pp, values.length)
                : LLVMConstStructInContext(ctx, pp, values.length, 0);
    }

    /**
     * Maakt een string-global aan of hergebruikt een bestaande.
     * Veilig om buiten een functie-body aan te roepen.
     *
     * Resultaat is een ptr naar de eerste byte van [N x i8].
     */
    private LLVMTypeRef stringStruct;
    private LLVMTypeRef stringHeaderType;
    private LLVMValueRef stringTypeInfo;
    private LLVMValueRef stringVtable;
    private LLVMValueRef stringItable;
    private boolean rawStrings;
    private final java.util.Map<String, String> stringGlobalTexts = new java.util.HashMap<>();
    private final java.util.Map<String, LLVMValueRef> javaStringCache = new java.util.HashMap<>();

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
