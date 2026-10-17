package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Synthetiseert, voor een {@code invokedynamic} met
 * {@code LambdaMetafactory.metafactory} als bootstrap, een echte klasse die
 * de functionele interface implementeert - exact wat de JVM-runtime zelf
 * doet (een hidden class spinnen), maar dan ahead-of-time tijdens het
 * compileren in plaats van lazy tijdens uitvoering.
 *
 * <p>De klasse krijgt een veld per gevangen (captured) variabele, een
 * constructor die ze opslaat, en de SAM-methode die de velden laadt en
 * doorgeeft aan de echte implementatiemethode (de {@code implMethod}-
 * handle uit {@code bsmArgs[1]}). Dit dekt de vier gebruikelijke gevallen
 * voor {@code implMethod}'s invocatie-tag:
 * <ul>
 *   <li>{@code H_INVOKESTATIC} - gewone statische aanroep;</li>
 *   <li>{@code H_INVOKEVIRTUAL}/{@code H_INVOKEINTERFACE} - instantie-aanroep,
 *       waarbij de ontvanger ofwel de EERSTE captured variabele is (gebonden
 *       method reference, bv. {@code someObj::toString}) ofwel de EERSTE
 *       SAM-parameter (ongebonden, bv. {@code String::toUpperCase});</li>
 *   <li>{@code H_INVOKESPECIAL} - idem, voor private/super-aanroepen;</li>
 *   <li>{@code H_NEWINVOKESPECIAL} - constructor-reference (bv.
 *       {@code ArrayList::new}): een nieuw object van de implementatie-
 *       klasse wordt aangemaakt i.p.v. een aanroep op een ontvanger.</li>
 * </ul>
 */
final class LambdaClassSynthesizer {

    private static final AtomicLong COUNTER = new AtomicLong();

    private LambdaClassSynthesizer() {
    }

    record Result(IRModule module, String qualifiedName, String internalName,
                   List<IRType> capturedTypes) {
    }

    /**
     * @param enclosingQualifiedName de klasse waarin de invokedynamic-
     *                                call-site staat (puur voor een
     *                                leesbare synthetische klassenaam).
     * @param samMethodName           naam van de SAM-methode (indy's eigen
     *                                {@code name}-veld, bv. {@code "accept"}).
     * @param factoryDesc             indy's eigen descriptor: (captured
     *                                typen...)functionele-interface-type.
     * @param bsmArgs                 {@code [samMethodType, implMethodHandle,
     *                                instantiatedMethodType]}.
     * @param noteReference           callback om extra klassenaam-referenties
     *                                (functionele interface, implMethod-
     *                                eigenaar) aan de bereikbaarheidssluiting
     *                                toe te voegen.
     */
    static Result synthesize(final String enclosingQualifiedName,
                             final String samMethodName,
                             final String factoryDesc,
                             final Object[] bsmArgs,
                             final Consumer<String> noteReference,
                             final Consumer<MethodRef> noteMethodReference) {
        final Type factoryType = Type.getMethodType(factoryDesc);
        final Type[] capturedArgTypes = factoryType.getArgumentTypes();
        final String functionalInterfaceInternalName =
                factoryType.getReturnType().getInternalName();
        noteReference.accept(functionalInterfaceInternalName);

        if (bsmArgs.length < 2 || !(bsmArgs[0] instanceof Type samMethodTypeConst)) {
            throw new UnsupportedOperationException(
                    "LambdaMetafactory.metafactory zonder samMethodType-constante "
                            + "(bsmArgs[0]) nog niet ondersteund");
        }
        if (!(bsmArgs[1] instanceof Handle implHandle)) {
            throw new UnsupportedOperationException(
                    "LambdaMetafactory.metafactory zonder implMethod-Handle "
                            + "(bsmArgs[1]) nog niet ondersteund");
        }
        noteReference.accept(implHandle.getOwner());

        // altMetafactory heeft, naast dezelfde eerste drie argumenten als
        // metafactory, een vierde "flags"-argument plus optioneel markers
        // (extra interfaces zoals Serializable) en/of bridges (extra
        // overbruggingsmethoden met een ander erasure-profiel). Markers zijn
        // puur declaratief (geen methoden om te implementeren) en worden
        // gewoon als extra interfaces toegevoegd. Bridges zouden een TWEEDE
        // methode met dezelfde SAM-naam maar een ander descriptor vereisen -
        // dat botst met Nabu's eigen (bestaande, elders al als risico
        // gedocumenteerde) naamschema zonder descriptor-suffix, dus die
        // combinatie wordt expliciet afgewezen i.p.v. stilzwijgend verkeerde
        // code te genereren.
        final List<IRType> extraInterfaces = new ArrayList<>();
        if (bsmArgs.length > 3 && bsmArgs[3] instanceof Integer flagsBoxed) {
            final int flags = flagsBoxed;
            final int flagSerializable = 1;    // LambdaMetafactory.FLAG_SERIALIZABLE
            final int flagMarkers = 2;          // LambdaMetafactory.FLAG_MARKERS
            final int flagBridges = 4;          // LambdaMetafactory.FLAG_BRIDGES
            int idx = 4;
            if ((flags & flagMarkers) != 0 && idx < bsmArgs.length
                    && bsmArgs[idx] instanceof Integer markerCountBoxed) {
                final int markerCount = markerCountBoxed;
                idx++;
                for (int i = 0; i < markerCount && idx < bsmArgs.length; i++, idx++) {
                    if (bsmArgs[idx] instanceof Type markerType) {
                        final String markerInternalName = markerType.getInternalName();
                        noteReference.accept(markerInternalName);
                        extraInterfaces.add(new IRType.Ptr(IRType.I8, "L" + markerInternalName + ";"));
                    }
                }
            }
            if ((flags & flagBridges) != 0 && idx < bsmArgs.length
                    && bsmArgs[idx] instanceof Integer bridgeCountBoxed && bridgeCountBoxed > 0) {
                throw new UnsupportedOperationException(
                        "LambdaMetafactory.altMetafactory met bridge-methoden (FLAG_BRIDGES) "
                                + "nog niet ondersteund voor " + enclosingQualifiedName
                                + " - vereist een overload van de SAM-methode met een ander "
                                + "descriptor, wat botst met Nabu's eigen naamschema zonder "
                                + "descriptor-suffix (zie BYTECODE_COMPILATION_PLAN.md, "
                                + "\"Aanvullende bevinding\")");
            }
            if ((flags & flagSerializable) != 0) {
                extraInterfaces.add(new IRType.Ptr(IRType.I8, "Ljava/io/Serializable;"));
            }
        }

        final Type[] samParamTypes = samMethodTypeConst.getArgumentTypes();
        final Type samReturnType = samMethodTypeConst.getReturnType();

        final String syntheticQualifiedName = enclosingQualifiedName
                + "$$Lambda$" + COUNTER.incrementAndGet();
        final String syntheticInternalName = syntheticQualifiedName.replace('.', '/');
        final IRType thisType = new IRType.Ptr(IRType.I8, "L" + syntheticInternalName + ";");

        final var builder = new IRBuilder(Flags.SYNTHETIC | Flags.FINAL | Flags.PUBLIC,
                syntheticQualifiedName);
        final List<IRType> allInterfaces = new ArrayList<>();
        allInterfaces.add(new IRType.Ptr(IRType.I8, "L" + functionalInterfaceInternalName + ";"));
        allInterfaces.addAll(extraInterfaces);
        builder.interfaces(allInterfaces);

        final List<IRType> capturedTypes = new ArrayList<>();
        for (final Type t : capturedArgTypes) {
            capturedTypes.add(DescriptorMapper.mapType(t));
        }

        for (int i = 0; i < capturedTypes.size(); i++) {
            builder.field(IRField.field(Flags.PRIVATE | Flags.FINAL,
                    "arg$" + i, capturedTypes.get(i), null));
        }

        emitConstructor(builder, syntheticQualifiedName, thisType, capturedTypes);
        emitSamMethod(builder, syntheticQualifiedName, thisType, samMethodName,
                samParamTypes, samReturnType, capturedTypes, implHandle, noteMethodReference);

        return new Result(builder.build(), syntheticQualifiedName, syntheticInternalName,
                capturedTypes);
    }

    private static void emitConstructor(final IRBuilder builder,
                                         final String qualifiedName,
                                         final IRType thisType,
                                         final List<IRType> capturedTypes) {
        final List<IRValue> params = new ArrayList<>();
        params.add(new IRValue.Temp("%this", thisType));
        for (int i = 0; i < capturedTypes.size(); i++) {
            params.add(new IRValue.Temp("%c" + i, capturedTypes.get(i)));
        }

        builder.beginFunction(qualifiedName + "_init", IRType.VOID, params,
                Flags.PUBLIC, true);

        final IRValue thisParam = params.get(0);
        for (int i = 0; i < capturedTypes.size(); i++) {
            final var named = new IRValue.Named("arg$" + i, capturedTypes.get(i), thisType, false);
            builder.emitStore(new IRValue.Values(thisParam, named), params.get(i + 1));
        }
        builder.endFunction();
    }

    private static void emitSamMethod(final IRBuilder builder,
                                      final String qualifiedName,
                                      final IRType thisType,
                                      final String samMethodName,
                                      final Type[] samParamTypes,
                                      final Type samReturnType,
                                      final List<IRType> capturedTypes,
                                      final Handle implHandle,
                                      final Consumer<MethodRef> noteMethodReference) {
        final IRType samReturnIrType = DescriptorMapper.mapType(samReturnType);

        final List<IRValue> params = new ArrayList<>();
        params.add(new IRValue.Temp("%this", thisType));
        for (int i = 0; i < samParamTypes.length; i++) {
            params.add(new IRValue.Temp("%s" + i, DescriptorMapper.mapType(samParamTypes[i])));
        }

        builder.beginFunction(qualifiedName + "_" + samMethodName, samReturnIrType, params,
                Flags.PUBLIC, false);

        final IRValue thisParam = params.get(0);

        // Gevangen velden eerst laden (GETFIELD-equivalent), dan de echte
        // SAM-parameters - samen de volledige argumentenlijst voor de
        // implementatiemethode, in exact de volgorde die LambdaMetafactory's
        // javadoc beschrijft: captured args, dan invoked args.
        final List<IRValue> allArgs = new ArrayList<>();
        for (int i = 0; i < capturedTypes.size(); i++) {
            final var named = new IRValue.Named("arg$" + i, capturedTypes.get(i), thisType, false);
            allArgs.add(builder.emitLoad(capturedTypes.get(i), new IRValue.Values(thisParam, named)));
        }
        // Het ASM-type per argument, voor de box/unbox-aanpassing hieronder;
        // null voor gevangen waarden (die hebben al exact het type dat de
        // implementatiemethode verwacht).
        final List<Type> allArgAsmTypes = new ArrayList<>();
        for (int i = 0; i < capturedTypes.size(); i++) {
            allArgAsmTypes.add(null);
        }
        for (int i = 0; i < samParamTypes.length; i++) {
            allArgs.add(params.get(i + 1));
            allArgAsmTypes.add(samParamTypes[i]);
        }

        final String implOwner = implHandle.getOwner();
        final String implOwnerQualified = implOwner.replace('/', '.');
        final String implName = implHandle.getName();
        final Type implMethodType = Type.getMethodType(implHandle.getDesc());
        final IRType implReturnType = DescriptorMapper.mapType(implMethodType.getReturnType());

        // De implementatiemethode van de method-handle is de ENIGE plek waar
        // deze lambda-wrapper echt in terechtkomt - zonder deze registratie
        // weet BytecodeReachabilityCompiler's methode-niveau worklist niet
        // dat deze methode nodig is (hij wordt nergens via een gewone
        // INVOKEVIRTUAL/INVOKESTATIC-instructie aangeroepen, alleen via deze
        // handle), en faalt native codegen later met "Onbekende functie".
        noteMethodReference.accept(new MethodRef(implOwner, implHandle.getName(), implHandle.getDesc(),
                implHandle.getTag() == Opcodes.H_INVOKEVIRTUAL || implHandle.getTag() == Opcodes.H_INVOKEINTERFACE));

        final IRValue result;
        if (implHandle.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
            // Constructor-reference (bv. ArrayList::new): alle args zijn
            // constructor-argumenten, geen apart ontvanger-element.
            final var newObjType = new IRType.Ptr(IRType.I8, "L" + implOwner + ";");
            final var newObj = builder.emitHeapAlloc(implOwner, newObjType);
            final List<IRValue> ctorArgs = new ArrayList<>();
            ctorArgs.add(newObj);
            ctorArgs.addAll(allArgs);
            final List<IRType> ctorParamTypes = new ArrayList<>();
            for (final Type t : implMethodType.getArgumentTypes()) {
                ctorParamTypes.add(DescriptorMapper.mapType(t));
            }
            adaptArgs(builder, ctorArgs, allArgAsmTypes, 1, implMethodType.getArgumentTypes(),
                    noteMethodReference);
            builder.emitCall(CallKind.SPECIAL, implOwnerQualified + "_init", IRType.VOID,
                    ctorParamTypes, ctorArgs);
            result = newObj;
        } else {
            final CallKind callKind = switch (implHandle.getTag()) {
                case Opcodes.H_INVOKESTATIC -> CallKind.STATIC;
                case Opcodes.H_INVOKEVIRTUAL -> CallKind.VIRTUAL;
                case Opcodes.H_INVOKEINTERFACE -> CallKind.INTERFACE;
                case Opcodes.H_INVOKESPECIAL -> CallKind.SPECIAL;
                default -> throw new UnsupportedOperationException(
                        "LambdaMetafactory implMethod-Handle met onbekende tag "
                                + implHandle.getTag() + " nog niet ondersteund");
            };

            final String fullImplName = "<init>".equals(implName)
                    ? implOwnerQualified + "_init"
                    : implOwnerQualified + "_" + implName;

            final List<IRValue> callArgs;
            final List<IRType> callParamTypes = new ArrayList<>();
            for (final Type t : implMethodType.getArgumentTypes()) {
                callParamTypes.add(DescriptorMapper.mapType(t));
            }
            if (callKind == CallKind.STATIC) {
                callArgs = allArgs;
            } else {
                // Instantie-aanroep: de ontvanger is het EERSTE element van
                // allArgs (gevangen receiver bij een gebonden reference, of
                // anders de eerste SAM-parameter bij een ongebonden
                // reference - in beide gevallen correct omdat allArgs al in
                // (captured..., samParams...)-volgorde is opgebouwd).
                callArgs = allArgs;
            }

            // Bij een instantie-aanroep is callArgs[0] de ontvanger; de
            // parameters van de implementatiemethode beginnen daarna.
            final int receiverCount = callKind == CallKind.STATIC ? 0 : 1;
            final List<Type> asmTypes = new ArrayList<>(allArgAsmTypes);
            adaptArgs(builder, callArgs, asmTypes, receiverCount, implMethodType.getArgumentTypes(),
                    noteMethodReference);

            result = builder.emitCall(callKind, fullImplName, implReturnType, callParamTypes, callArgs);
        }

        if (samReturnIrType == IRType.VOID) {
            builder.emitReturn(null);
        } else if (implHandle.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
            builder.emitReturn(result);
        } else {
            builder.emitReturn(adapt(builder, result, implMethodType.getReturnType(), samReturnType,
                    noteMethodReference));
        }
        builder.endFunction();
    }

    // LambdaMetafactory past argumenten en resultaat aan tussen de signatuur
    // van de SAM-methode en die van de implementatiemethode (boxing,
    // unboxing, primitieve verbreding) - bv. `IntConsumer.accept(int)` dat
    // doorstuurt naar `Consumer.accept(Object)`, of een `Function.apply` dat
    // de `long` van `Duration::getSeconds` als Object teruggeeft. Zonder die
    // aanpassing ging een i32 naar een ptr-parameter.

    private static void adaptArgs(final IRBuilder builder,
                                  final List<IRValue> args,
                                  final List<Type> argAsmTypes,
                                  final int firstParamIndex,
                                  final Type[] implParamTypes,
                                  final Consumer<MethodRef> noteMethodReference) {
        // argAsmTypes loopt gelijk met de args ZONDER een eventueel vooraan
        // toegevoegd constructor-object.
        final int offset = args.size() - argAsmTypes.size();
        for (int i = 0; i < implParamTypes.length; i++) {
            final int argIndex = firstParamIndex + i;
            final int typeIndex = argIndex - offset;
            if (argIndex >= args.size() || typeIndex < 0 || typeIndex >= argAsmTypes.size()) {
                continue;
            }
            final Type from = argAsmTypes.get(typeIndex);
            if (from == null) {
                continue;
            }
            args.set(argIndex, adapt(builder, args.get(argIndex), from, implParamTypes[i],
                    noteMethodReference));
        }
    }

    private static boolean isPrimitive(final Type type) {
        return type.getSort() >= Type.BOOLEAN && type.getSort() <= Type.DOUBLE;
    }

    /** Wrapper-klasse (interne naam) van een primitief type. */
    private static String wrapperOf(final Type primitive) {
        return switch (primitive.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            default -> "java/lang/Double";
        };
    }

    private static IRValue adapt(final IRBuilder builder,
                                 final IRValue value,
                                 final Type from,
                                 final Type to,
                                 final Consumer<MethodRef> noteMethodReference) {
        if (value == null || from.getSort() == Type.VOID || to.getSort() == Type.VOID) {
            return value;
        }
        final boolean fromPrimitive = isPrimitive(from);
        final boolean toPrimitive = isPrimitive(to);
        if (fromPrimitive && !toPrimitive) {
            // Boxing: Wrapper.valueOf(primitive)
            final String wrapper = wrapperOf(from);
            final String desc = "(" + from.getDescriptor() + ")L" + wrapper + ";";
            noteMethodReference.accept(new MethodRef(wrapper, "valueOf", desc, false));
            return builder.emitCall(CallKind.STATIC, wrapper.replace('/', '.') + "_valueOf",
                    new IRType.Ptr(IRType.I8, "L" + wrapper + ";"),
                    List.of(DescriptorMapper.mapType(from)), List.of(value));
        }
        if (!fromPrimitive && toPrimitive) {
            // Unboxing: wrapper.xxxValue()
            final String wrapper = wrapperOf(to);
            final String method = to.getClassName() + "Value";
            noteMethodReference.accept(new MethodRef(wrapper, method, "()" + to.getDescriptor(), true));
            final IRValue receiver = builder.emitCast(value, new IRType.Ptr(IRType.I8, "L" + wrapper + ";"));
            return builder.emitCall(CallKind.VIRTUAL, wrapper.replace('/', '.') + "_" + method,
                    DescriptorMapper.mapType(to), List.of(), List.of(receiver));
        }
        if (fromPrimitive && from.getSort() != to.getSort()) {
            // Primitieve verbreding (int -> long, float -> double, ...).
            return builder.emitCast(value, DescriptorMapper.mapType(to));
        }
        return value;
    }
}
