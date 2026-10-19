package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRAnnotation;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.backend.nativeimage.GraalVMNativeImageConfig.ReflectEntry;
import io.github.potjerodekool.nabu.tools.FileManager;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Berekent de transitieve sluiting van bytecode-only klassen (JDK en
 * libraries op het classpath, zonder Nabu-source) die bereikbaar zijn vanaf
 * de broncode en de reflectie-configuratie, en lowert
 * elke bereikte, daadwerkelijk AANGEROEPEN methode naar IR via
 * {@link BytecodeMethodLowering}.
 *
 * <p><b>Methode-niveau reachability (i.p.v. hele-klasse):</b> een eerdere
 * versie van deze klasse lowerde ELKE methode van ELKE bereikte klasse,
 * inclusief methodes die nooit aangeroepen worden. Dat liet de sluiting
 * vanaf alleen {@code java.util.regex.Pattern}/{@code Matcher} ontsporen tot
 * ~5000 klassen (bijna heel java.base: serialisatie, crypto-providers, NIO,
 * de classfile-API, ...) - niet omdat die code nodig is, maar omdat elke
 * bereikte klasse ZIJN VOLLEDIGE methodeset meesleepte, inclusief bv.
 * {@code Pattern.readObject} (Serializable), wat weer
 * {@code java.lang.invoke.MethodHandle.invokeExact} binnenhaalt - een
 * signature-polymorphic aanroepmechanisme dat niet via Nabu's gewone
 * name-mangled aanroepconventie op te lossen is, en dat de daadwerkelijke
 * acceptatietests (die niets serialiseren) nooit nodig hebben.
 *
 * <p>Deze versie lowert daarom alleen:
 * <ul>
 *   <li>voor klassen die de broncode aanroept: de methodes waarvan de
 *       NAAM in een aanroep vanuit de Nabu-source voorkomt (alle overloads,
 *       zie {@link #queueSourceCalls});</li>
 *   <li>voor klassen uit de reflectie-configuratie: de constructors en
 *       methodes die de configuratie noemt (zie {@link #queueReflectionRoot});</li>
 *   <li>voor elke andere, transitief bereikte klasse: alleen de exacte
 *       (naam+descriptor) methodes die ECHT vanuit een reeds-gelowerde
 *       bytecode-methode aangeroepen worden (zie {@link MethodRef}), plus
 *       - voor virtuele/interface-aanroepen - de overrides daarvan in elke
 *       reeds bereikte subklasse (zie {@link #virtualSignatures}/
 *       {@link #directSubtypes}: een class-hierarchy-analyse-achtige
 *       benadering, niet een volledig sluitend points-to-algoritme, maar
 *       ruim voldoende om de eerdere complete-sluiting-aanpak te vervangen
 *       zonder vtable-correctheid te verliezen voor klassen die al bereikt
 *       zijn op het moment dat de override relevant wordt).</li>
 * </ul>
 *
 * <p>{@code <clinit>} wordt, onafhankelijk van wat er verder aangeroepen
 * wordt, altijd gelowered zodra een klasse z'n "vorm" (super/interfaces/
 * velden) krijgt - static state moet correct geïnitialiseerd worden zodra
 * een klasse gebruikt wordt, ongeacht welke methode het eerst aanroept.
 *
 * <p>Klassen die al als Nabu-source gecompileerd worden (en dus al een
 * {@code IRModule} hebben via {@code IrGeneratingVisitor}) worden hier
 * overgeslagen via {@code alreadyCompiledQualifiedNames}.
 */
public final class BytecodeReachabilityCompiler {

    private final BytecodeClassLoader classLoader;
    private final Set<String> alreadyCompiledQualifiedNames;

    /** internalName -> IRBuilder, voor elke klasse waarvan de vorm al is aangemaakt. */
    private final Map<String, IRBuilder> shapeBuilders = new HashMap<>();
    /** internalName -> classNode, voor elke klasse die al via classLoader geladen is (vorm of niet). */
    private final Map<String, ClassNode> loadedClassNodes = new HashMap<>();
    private final Set<String> shapedClasses = new HashSet<>();
    private final Set<String> notFound = new HashSet<>();

    /** "ownerInternal#methodName#methodDesc" van elke al gelowerde (of als extern/abstract vastgestelde) methode. */
    private final Set<String> loweredMethods = new HashSet<>();

    /**
     * VM-gebonden klassen die de C-runtime (runtime/jcl_lite.c) zelf
     * modelleert: hun objecten zijn geen instanties met de velden uit de
     * classfile (een {@code Class} is daar bv. een naam, een {@code Method}
     * een record uit de reflectie-registry). Bytecode van deze klassen zou
     * die objecten als gewone instanties uitlezen, en werkt bovendien niet
     * zonder VM-opstart ({@code System.initPhase1}) en natives. Ze worden
     * daarom niet uit bytecode gecompileerd.
     */
    private static final Set<String> RUNTIME_PROVIDED = Set.of(
            "java/lang/Class",
            "java/lang/ClassLoader",
            "java/lang/System",
            "java/lang/Runtime",
            "java/lang/Thread",
            "java/lang/reflect/AccessibleObject",
            "java/lang/reflect/Executable",
            "java/lang/reflect/Method",
            "java/lang/reflect/Field",
            "java/lang/reflect/Constructor",
            "java/lang/reflect/Parameter",
            "java/lang/reflect/Proxy",
            "java/io/File");

    /**
     * Gesynthetiseerde opstartklasse: het deel van {@code System.initPhase1}
     * dat de JDK-bytecode nodig heeft, als bytecode (de native backend
     * roept {@code Bootstrap.init()} vóór de {@code <clinit>}s aan). System
     * zelf is VM-gebonden en wordt niet gecompileerd; zijn statische velden
     * zijn gewone globals.
     */
    public static final String BOOTSTRAP_CLASS = "nabu/runtime/Bootstrap";

    /** Methoden die altijd gelowered worden, ook zonder aanroep vanuit source. */
    private static final List<MethodRef> RUNTIME_ROOTS = List.of(
            new MethodRef(BOOTSTRAP_CLASS, "init", "()V", false));

    /**
     * Klassen die de JDK alleen reflectief instantieert
     * ({@code Class.forName(naam).getDeclaredConstructor().newInstance()}),
     * per klasse die dat doet. Zodra de trigger in de sluiting zit, komt de
     * no-arg-constructor van elk doel erbij; de runtime bouwt ze dan via
     * {@code nabu_ctor_table}. Zonder deze roots zat bv. geen enkele
     * locale-provider-adapter in de sluiting en gaf String.format("%d")
     * een NullPointerException. (Vergelijkbaar met GraalVM's ingebouwde
     * reflectie-configuratie voor de JDK.)
     */
    private static final Map<String, List<String>> REFLECTIVE_INSTANTIATIONS = Map.of(
            "sun/util/locale/provider/LocaleProviderAdapter", List.of(
                    "sun/util/locale/provider/JRELocaleProviderAdapter",
                    "sun/util/locale/provider/FallbackLocaleProviderAdapter"),
            // ResourceBundle-klassen van de root-locale (java.base), via
            // Bundles.findBundleOf: Class.forName(module, naam) + newInstance.
            "sun/util/resources/Bundles", List.of(
                    "sun/text/resources/FormatData",
                    "sun/text/resources/BreakIteratorInfo",
                    "sun/text/resources/BreakIteratorResources"));

    /**
     * {@code static void init()}:
     * <pre>
     *   SharedSecrets.setJavaLangAccess(new System$1());
     *   System.out = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
     *   System.err = new PrintStream(new FileOutputStream(FileDescriptor.err), true);
     * </pre>
     * {@code System$1} is de eigen JavaLangAccess-implementatie van de JDK
     * (die {@code System.setJavaLangAccess} installeert); o.a. de
     * UTF-8-encoder van System.out gebruikt hem.
     */
    /**
     * Vervangt de body van JDK-methoden die aan de VM/het module-systeem
     * vastzitten door bytecode die in een gesloten wereld klopt (vergelijk
     * GraalVM's substitutions). Alleen de body; de rest van de klasse blijft
     * echte JDK-bytecode.
     *
     * <ul>
     *   <li>{@code ServiceLoader.newLookupIterator()} geeft een lege iterator:
     *       service-discovery loopt over module-layers en de
     *       class-loader-hiërarchie (ClassLoaders/BuiltinClassLoader), die
     *       een native programma niet heeft. Er zijn dus geen geïnstalleerde
     *       providers; de JDK valt dan terug op zijn ingebouwde defaults
     *       (bv. locale-data in JRELocaleProviderAdapter).</li>
     * </ul>
     */
    private static ClassNode substitute(final ClassNode classNode) {
        if (classNode == null) {
            return null;
        }
        EmbeddedResources.rewriteResourceCalls(classNode);
        io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions.JavaSubstitutions.apply(classNode);
        if ("java/util/ServiceLoader".equals(classNode.name)) {
            for (final MethodNode m : classNode.methods) {
                if ("newLookupIterator".equals(m.name) && "()Ljava/util/Iterator;".equals(m.desc)) {
                    replaceBody(m, insns -> {
                        insns.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                                "java/util/Collections", "emptyIterator", "()Ljava/util/Iterator;", false));
                        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
                    });
                }
            }
        }
        if ("java/util/concurrent/atomic/AtomicMarkableReference".equals(classNode.name)) {
            substituteVarHandleCas(classNode, "casPair", "pair",
                    "Ljava/util/concurrent/atomic/AtomicMarkableReference$Pair;");
        }
        if ("java/util/concurrent/ThreadLocalRandom".equals(classNode.name)) {
            // With -Djava.util.secureRandomSeed=true the seed comes from
            // SecureRandom.getSeed, which drags in the whole security-provider
            // framework (providers, PKCS7/X.509, URLs, jar verification). Not
            // set by default; the seed then comes from the clock, as here.
            for (final MethodNode m : classNode.methods) {
                for (final var insn : m.instructions.toArray()) {
                    if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                            && "java/security/SecureRandom".equals(call.owner)
                            && "getSeed".equals(call.name) && "(I)[B".equals(call.desc)) {
                        m.instructions.set(insn,
                                new org.objectweb.asm.tree.IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
                    }
                }
            }
        }
        final Set<String> noOp = NO_OP.get(classNode.name);
        if (noOp != null) {
            for (final MethodNode m : classNode.methods) {
                if (noOp.contains(m.name) && m.desc.endsWith(")V")
                        && (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    replaceBody(m, insns -> insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN)));
                }
            }
        }
        final Set<String> unsupported = UNSUPPORTED.get(classNode.name);
        if (unsupported != null) {
            for (final MethodNode m : classNode.methods) {
                final boolean hasBody = (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
                final boolean all = unsupported.contains("*") && !m.name.startsWith("<");
                if (hasBody && (all || unsupported.contains(m.name))) {
                    replaceBody(m, insns -> throwUnsupported(insns, classNode.name + "." + m.name));
                }
            }
        }
        return classNode;
    }

    /**
     * Methods that cannot work in a native program, per class ({@code *}: all
     * methods except constructors and initializers). Method handles and
     * var handles are invoked signature-polymorphically and compiled to
     * bytecode at run time; there is no class loader to load that bytecode.
     * Their body throws {@link UnsupportedOperationException}, which keeps the
     * code they would reach (the method-handle compiler, the class-file API)
     * out of the closure. Like on the JVM, the exception only surfaces where
     * the program actually uses them; classes compiled from bytecode
     * initialize lazily.
     */
    private static final Map<String, Set<String>> UNSUPPORTED = Map.ofEntries(
            Map.entry("java/lang/invoke/MethodHandles", Set.of("*")),
            Map.entry("java/lang/invoke/MethodHandles$Lookup", Set.of("*")),
            Map.entry("java/lang/invoke/VarHandles", Set.of("*")),
            Map.entry("java/lang/invoke/LambdaForm", Set.of("compileToBytecode")),
            Map.entry("java/lang/invoke/ClassSpecializer$Factory", Set.of("*")),
            Map.entry("java/lang/invoke/InvokerBytecodeGenerator", Set.of("*")),
            Map.entry("java/lang/classfile/ClassFile", Set.of("*")),
            // Serialization reads and writes the fields of arbitrary classes
            // reflectively; the reflection metadata only covers configured
            // classes, and there is no Unsafe allocation.
            Map.entry("java/io/ObjectInputStream", Set.of("*")),
            Map.entry("java/io/ObjectOutputStream", Set.of("*")),
            Map.entry("java/io/ObjectStreamClass", Set.of("*")));

    /**
     * Void methods whose body becomes empty. {@code Tripwire.trip} only
     * reports (through the platform logger) when a debug property is set.
     */
    private static final Map<String, Set<String>> NO_OP = Map.of(
            "java/util/stream/Tripwire", Set.of("trip"));

    private static void throwUnsupported(final org.objectweb.asm.tree.InsnList insns, final String what) {
        insns.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
        insns.add(new org.objectweb.asm.tree.LdcInsnNode(what + " is not supported in a native executable"));
        insns.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false));
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ATHROW));
    }

    /**
     * Een klasse die haar veld via een {@code VarHandle} compare-and-set't:
     * VarHandles (signature-polymorf, MethodHandles.lookup() met
     * caller-sensitive controle) bestaan native niet. De {@code <clinit>}
     * zoekt de VarHandle dan niet op, en {@code casMethod(cmp, val)} wordt
     * een gesynchroniseerde vergelijk-en-zet op het veld. Zonder dit gooide
     * AtomicMarkableReference.<clinit> een IllegalCallerException, waardoor
     * elke ParallelListResourceBundle (sun.text.resources.FormatData) faalde.
     */
    private static void substituteVarHandleCas(final ClassNode classNode, final String casMethod,
                                               final String field, final String fieldDesc) {
        final String casDesc = "(" + fieldDesc + fieldDesc + ")Z";
        for (final MethodNode m : classNode.methods) {
            if ("<clinit>".equals(m.name)) {
                replaceBody(m, insns -> insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN)));
            } else if (casMethod.equals(m.name) && casDesc.equals(m.desc)) {
                m.access |= Opcodes.ACC_SYNCHRONIZED;
                replaceBody(m, insns -> {
                    final var differs = new org.objectweb.asm.tree.LabelNode();
                    insns.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, classNode.name, field, fieldDesc));
                    insns.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
                    insns.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ACMPNE, differs));
                    insns.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 2));
                    insns.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD, classNode.name, field, fieldDesc));
                    insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_1));
                    insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
                    insns.add(differs);
                    insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
                    insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
                });
            }
        }
    }

    private static void replaceBody(final MethodNode method,
                                    final java.util.function.Consumer<org.objectweb.asm.tree.InsnList> body) {
        final var insns = new org.objectweb.asm.tree.InsnList();
        body.accept(insns);
        method.instructions = insns;
        method.tryCatchBlocks = new ArrayList<>();
        method.localVariables = null;
        method.maxStack = Math.max(method.maxStack, 3);
    }

    private static ClassNode bootstrapClass() {
        final ClassNode node = new ClassNode();
        node.version = org.objectweb.asm.Opcodes.V17;
        node.access = org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL
                | org.objectweb.asm.Opcodes.ACC_SUPER;
        node.name = BOOTSTRAP_CLASS;
        node.superName = "java/lang/Object";

        final MethodNode init = new MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC
                | org.objectweb.asm.Opcodes.ACC_STATIC, "init", "()V", null, null);
        final var code = init.instructions;
        code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.NEW, "java/lang/System$1"));
        code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
        code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "java/lang/System$1", "<init>", "()V", false));
        code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                "jdk/internal/access/SharedSecrets", "setJavaLangAccess",
                "(Ljdk/internal/access/JavaLangAccess;)V", false));
        for (final String stream : List.of("out", "err")) {
            code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.NEW, "java/io/PrintStream"));
            code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
            code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.NEW, "java/io/FileOutputStream"));
            code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
            code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC,
                    "java/io/FileDescriptor", stream, "Ljava/io/FileDescriptor;"));
            code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                    "java/io/FileOutputStream", "<init>", "(Ljava/io/FileDescriptor;)V", false));
            code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
            code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                    "java/io/PrintStream", "<init>", "(Ljava/io/OutputStream;Z)V", false));
            code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTSTATIC,
                    "java/lang/System", stream, "Ljava/io/PrintStream;"));
        }
        // System.in = new BufferedInputStream(new FileInputStream(FileDescriptor.in)),
        // as in System.initPhase1. Without it System.in was null.
        code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.NEW, "java/io/BufferedInputStream"));
        code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
        code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.NEW, "java/io/FileInputStream"));
        code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
        code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC,
                "java/io/FileDescriptor", "in", "Ljava/io/FileDescriptor;"));
        code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "java/io/FileInputStream", "<init>", "(Ljava/io/FileDescriptor;)V", false));
        code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "java/io/BufferedInputStream", "<init>", "(Ljava/io/InputStream;)V", false));
        code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTSTATIC,
                "java/lang/System", "in", "Ljava/io/InputStream;"));
        code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
        init.maxStack = 6;
        init.maxLocals = 0;
        node.methods.add(init);
        return node;
    }
    /** {@code owner#name}-paren die al vanuit een Nabu-source-aanroep op de worklist gezet zijn. */
    private final Set<String> sourceQueued = new HashSet<>();

    /**
     * ownerInternal -> "methodName#methodDesc" die virtueel/interface tegen
     * die exacte klasse is aangeroepen - voor override-propagatie naar
     * (nu of later bereikte) subklassen.
     */
    private final Map<String, Set<String>> virtualSignatures = new HashMap<>();

    /** ancestorInternal (superklasse OF interface) -> reeds ontdekte directe subtypes. */
    private final Map<String, Set<String>> directSubtypes = new HashMap<>();

    /**
     * ancestorInternal -> alle reeds gevormde (transitieve) subtypes. Voor
     * richting (b) volstaat {@link #directSubtypes} niet: die keten heeft een
     * gat zolang een tussenliggende klasse nog niet gevormd is. Integer werd
     * gevormd vóór Number; toen Object.toString() daarna virtueel werd
     * geregistreerd, bereikte de propagatie Integer niet (Object -> Number
     * ontbrak nog) en wees Integers vtable-slot naar Object.toString.
     */
    private final Map<String, Set<String>> transitiveSubtypes = new HashMap<>();

    /**
     * Classes whose instances exist at run time: {@code NEW} in lowered
     * bytecode, a constructor called from source, or a constructor reachable
     * through reflection. Overrides are only lowered for these (rapid type
     * analysis); a class that only appears as a type (a field, a cast, a
     * superclass) keeps its inherited vtable entries.
     */
    private final Set<String> instantiated = new HashSet<>();

    /**
     * Classes that are initialized at run time (JLS 12.4.1): instantiated,
     * a static field accessed, a static method called, or a subclass
     * initialized. Only these get their {@code <clinit>} lowered.
     */
    private final Set<String> initialized = new HashSet<>();

    private final Deque<String> shapeWorklist = new ArrayDeque<>();
    private final Deque<MethodRef> methodWorklist = new ArrayDeque<>();
    private final Deque<String> initWorklist = new ArrayDeque<>();
    private final Deque<String> instantiateWorklist = new ArrayDeque<>();

    /**
     * {@code -Dnabu.reachability.why=<text>}: print, for every lowered method
     * whose name contains the text, the chain of reasons back to a root.
     * For finding out why the closure contains a class.
     */
    private static final String WHY = System.getProperty("nabu.reachability.why");

    /** Node -> the node that put it on a worklist (only with {@link #WHY}). */
    private final Map<String, String> why = new HashMap<>();

    /** The node being processed, recorded as the reason for what it queues. */
    private String context = "root";

    private void queueMethod(final MethodRef ref) {
        if (WHY != null) {
            why.putIfAbsent(methodNode(ref.ownerInternalName(), ref.name(), ref.desc()), context);
        }
        methodWorklist.add(ref);
    }

    private void queueInit(final String internalName) {
        if (WHY != null) {
            why.putIfAbsent("init " + internalName, context);
        }
        initWorklist.add(internalName);
    }

    private void queueInstantiate(final String internalName) {
        if (WHY != null) {
            why.putIfAbsent("new " + internalName, context);
        }
        instantiateWorklist.add(internalName);
    }

    private static String methodNode(final String owner, final String name, final String desc) {
        return owner + "." + name + (desc != null ? desc : "");
    }

    private void printWhy() {
        if (WHY == null) {
            return;
        }
        if (WHY.startsWith("@")) {
            // "@prefix": the edges that enter the classes under prefix.
            final String prefix = WHY.substring(1);
            final Set<String> edges = new java.util.TreeSet<>();
            for (final var entry : why.entrySet()) {
                if (entry.getKey().replaceFirst("^(init|new) ", "").startsWith(prefix)
                        && !entry.getValue().replaceFirst("^(init|new) ", "").startsWith(prefix)) {
                    edges.add(entry.getValue() + "\n    -> " + entry.getKey());
                }
            }
            edges.forEach(edge -> System.err.println("[BytecodeReachability] enters " + edge));
            return;
        }
        for (final String node : why.keySet()) {
            if (!node.contains(WHY)) {
                continue;
            }
            final StringBuilder chain = new StringBuilder("[BytecodeReachability] why " + node);
            final Set<String> seen = new HashSet<>();
            for (String next = why.get(node); next != null && seen.add(next); next = why.get(next)) {
                chain.append("\n    <- ").append(next);
            }
            System.err.println(chain);
        }
    }

    private final List<IRModule> resultModules = new ArrayList<>();

    private int methodsLowered = 0;
    private int classesShaped = 0;

    public BytecodeReachabilityCompiler(final FileManager fileManager,
                                        final Set<String> alreadyCompiledQualifiedNames) {
        this(fileManager, alreadyCompiledQualifiedNames, null);
    }

    public BytecodeReachabilityCompiler(final FileManager fileManager,
                                        final Set<String> alreadyCompiledQualifiedNames,
                                        final String jdkHomeOverride) {
        this.classLoader = new BytecodeClassLoader(fileManager, jdkHomeOverride);
        this.alreadyCompiledQualifiedNames = alreadyCompiledQualifiedNames;
    }

    /**
     * @param reflectionRoots klassen die het programma via reflectie gebruikt,
     *                          uit de GraalVM-configuratie
     *                          ({@code META-INF/native-image/**}). De rest van
     *                          de sluiting volgt uit de aanroepen in de
     *                          broncode en in de gelowerde bytecode.
     * @param alreadyCompiledModules de Nabu-source IRModules die al gebouwd
     *                          zijn - gescand op aanroepen naar JDK- en
     *                          library-klassen.
     * @return een IRModule per bereikte, nog niet gecompileerde klasse.
     */
    public List<IRModule> compileClosure(final List<ReflectEntry> reflectionRoots,
                                         final List<IRModule> alreadyCompiledModules) {
        final long start = System.nanoTime();

        for (final ReflectEntry root : reflectionRoots) {
            queueReflectionRoot(root);
        }
        for (final MethodRef root : RUNTIME_ROOTS) {
            ensureShape(root.ownerInternalName());
            queueInit(root.ownerInternalName());
            queueMethod(root);
        }
        // The C runtime creates these exceptions as real objects
        // (nabu_exception_table), through their (String) constructor.
        for (final String exception : io.github.potjerodekool.nabu.backend.RuntimeExceptions.CLASSES) {
            ensureShape(exception);
            queueInstantiate(exception);
            queueMethod(new MethodRef(exception, "<init>", "(Ljava/lang/String;)V", false));
        }

        drainWorklists();

        // Nabu-source roept methodes aan van JDK- en library-klassen, ook van
        // klassen die pas via de sluiting gevormd zijn
        // (bv. `new LinkedHashSet(collection)`); zonder die methodes te
        // loweren bindt zo'n call in de backend aan een toevallig wél
        // gelowerde overload met een ander parameterprofiel. Herhalen tot er
        // niets meer bijkomt: elke ronde kan nieuwe klassen vormen.
        final Map<String, Boolean> sourceCalls = collectSourceCalls(alreadyCompiledModules);
        while (queueSourceCalls(sourceCalls) | queueReflectiveInstantiations()) {
            drainWorklists();
        }

        // builder.build() geeft steeds hetzelfde (muterende) IRModule-object
        // terug - dus pas HIER, nadat de worklist volledig uitgeput is (alle
        // methodes van elke gevormde klasse zijn dan toegevoegd), elke
        // klasse's module precies één keer aan het resultaat toevoegen.
        for (final IRBuilder builder : shapeBuilders.values()) {
            resultModules.add(builder.build());
        }

        final long totalMs = (System.nanoTime() - start) / 1_000_000L;
        System.err.println("[BytecodeReachability] closure done: " + classesShaped
                + " classes shaped, " + methodsLowered + " methods lowered, "
                + notFound.size() + " not found/skipped, total " + totalMs + " ms");
        printWhy();

        return resultModules;
    }

    /**
     * Sluiting met hele klassen als wortel (alle constructors en methodes),
     * zonder broncode. Voor tests.
     */
    public List<IRModule> compileClosure(final Iterable<String> rootInternalNames) {
        final List<ReflectEntry> roots = new ArrayList<>();
        for (final String root : rootInternalNames) {
            roots.add(new ReflectEntry(root, true, true, true, true, List.of(), List.of()));
        }
        return compileClosure(roots, List.of());
    }

    /**
     * Vormt een klasse uit de reflectie-configuratie en zet de leden die de
     * configuratie noemt op de worklist: constructors en methodes volgens de
     * {@code all*}-vlaggen, plus de expliciet genoemde methodes (alle
     * overloads met die naam). Velden horen bij de vorm.
     */
    private void queueReflectionRoot(final ReflectEntry root) {
        final String internal = root.name().replace('.', '/');
        context = "reflection config";
        if (alreadyCompiledQualifiedNames.contains(internal.replace('/', '.'))) {
            return;
        }
        final ClassNode classNode = loadClassNode(internal);
        if (classNode == null) {
            System.err.println("[BytecodeReachability] warning: class " + root.name()
                    + " from the native-image configuration was not found");
            return;
        }
        ensureShape(internal);
        if (!shapedClasses.contains(internal)) {
            return;
        }
        queueInit(internal);
        if (root.allDeclaredConstructors() || root.allPublicConstructors()
                || root.methods().stream().anyMatch(m -> "<init>".equals(m.name()))) {
            queueInstantiate(internal);
        }
        for (final MethodNode methodNode : classNode.methods) {
            final boolean isPublic = (methodNode.access & Opcodes.ACC_PUBLIC) != 0;
            final boolean wanted;
            if ("<init>".equals(methodNode.name)) {
                wanted = root.allDeclaredConstructors() || (root.allPublicConstructors() && isPublic);
            } else if ("<clinit>".equals(methodNode.name)) {
                wanted = false;
            } else {
                wanted = root.allDeclaredMethods() || (root.allPublicMethods() && isPublic);
            }
            if (wanted) {
                queueMethod(new MethodRef(internal, methodNode.name, methodNode.desc, false));
            }
        }
        for (final var method : root.methods()) {
            queueMethod(new MethodRef(internal, method.name(), null, false));
        }
    }

    /** Alle aanroepdoelen uit de Nabu-source-modules, met of de aanroep virtueel dispatcht. */
    private static Map<String, Boolean> collectSourceCalls(final List<IRModule> modules) {
        final Map<String, Boolean> calls = new HashMap<>();
        for (final IRModule module : modules) {
            for (final var fn : module.functions()) {
                for (final var block : fn.blocks()) {
                    for (final IRInstruction insn : block.instructions()) {
                        if (insn instanceof IRInstruction.Call call && call.function() != null) {
                            final boolean virtual = call.callKind() == CallKind.VIRTUAL
                                    || call.callKind() == CallKind.INTERFACE;
                            calls.merge(call.function(), virtual, Boolean::logicalOr);
                        }
                    }
                }
            }
        }
        return calls;
    }

    /**
     * Zet voor elke source-aanroep naar een al gevormde bytecode-klasse de
     * aangeroepen methode op de worklist. De aanroepnaam draagt geen
     * descriptor, dus alle overloads met die naam worden gelowered (zelfde
     * afweging als {@link #findCalledSeedMethodNames}).
     *
     * @return of er iets nieuws op de worklist is gezet.
     */
    private boolean queueSourceCalls(final Map<String, Boolean> sourceCalls) {
        boolean queued = false;
        context = "source";
        // De source-frontend schrijft de eigenaar van een constructor-aanroep
        // met underscores (`java_util_LinkedHashSet_init`) en die van een
        // methode-aanroep met punten; daarom op de underscore-vorm matchen.
        final Map<String, String> shapedByFlatName = new HashMap<>();
        for (final String shaped : shapedClasses) {
            shapedByFlatName.put(shaped.replace('/', '_'), shaped);
        }
        for (final var entry : sourceCalls.entrySet()) {
            final String function = entry.getKey();
            for (int split = function.indexOf('_'); split > 0; split = function.indexOf('_', split + 1)) {
                String owner = shapedByFlatName.get(
                        function.substring(0, split).replace('.', '_'));
                if (owner == null) {
                    owner = shapeSourceReferencedClass(function.substring(0, split));
                }
                if (owner == null || split + 1 >= function.length()) {
                    continue;
                }
                String name = function.substring(split + 1);
                if ("init".equals(name)) {
                    name = "<init>";
                } else if ("clinit".equals(name)) {
                    name = "<clinit>";
                }
                if (sourceQueued.add(owner + "#" + name)) {
                    queueMethod(new MethodRef(owner, name, null, entry.getValue()));
                    // Source can also read the class's static fields, which
                    // this scan does not see; initialize it on any call.
                    queueInit(owner);
                    if ("<init>".equals(name)) {
                        queueInstantiate(owner);
                    }
                    queued = true;
                }
            }
        }
        return queued;
    }

    private final Set<String> reflectiveQueued = new HashSet<>();

    /**
     * Zet de no-arg-constructors uit {@link #REFLECTIVE_INSTANTIATIONS} op de
     * worklist voor elke trigger die inmiddels gevormd is.
     *
     * @return of er iets nieuws op de worklist is gezet.
     */
    private boolean queueReflectiveInstantiations() {
        boolean queued = false;
        context = "reflective instantiation";
        for (final var entry : REFLECTIVE_INSTANTIATIONS.entrySet()) {
            if (!shapedClasses.contains(entry.getKey())) {
                continue;
            }
            for (final String target : entry.getValue()) {
                if (!reflectiveQueued.add(target) || loadClassNode(target) == null) {
                    continue;
                }
                ensureShape(target);
                if (shapedClasses.contains(target)) {
                    queueMethod(new MethodRef(target, "<init>", "()V", false));
                    queueInstantiate(target);
                    queued = true;
                }
            }
        }
        return queued;
    }

    /** Eigenaar-kandidaten die {@link #shapeSourceReferencedClass} al probeerde. */
    private final Set<String> triedSourceOwners = new HashSet<>();

    /**
     * Een klasse die alleen vanuit Nabu-source aangeroepen wordt (picocli's
     * {@code new ProcessBuilder(cmd)}) zit nog niet in de sluiting;
     * vorm haar alsnog als de aanroepnaam een laadbare klasse noemt. Anders
     * bleven constructor én methodes ongedefinieerd (link-fout).
     *
     * @param prefix de eigenaar-vorm uit de aanroepnaam: met punten
     *               ({@code java.lang.ProcessBuilder}) of, bij een
     *               constructor, met underscores ({@code java_lang_ProcessBuilder}).
     * @return de interne naam als de klasse nu gevormd is, anders null.
     */
    private String shapeSourceReferencedClass(final String prefix) {
        final String internal = prefix.indexOf('.') >= 0
                ? prefix.replace('.', '/')
                : prefix.replace('_', '/');
        final int slash = internal.lastIndexOf('/');
        if (slash <= 0 || slash + 1 >= internal.length()
                || !Character.isUpperCase(internal.charAt(slash + 1))) {
            return null;
        }
        if (alreadyCompiledQualifiedNames.contains(internal)
                || alreadyCompiledQualifiedNames.contains(internal.replace('/', '.'))
                || notFound.contains(internal)
                || !triedSourceOwners.add(internal)) {
            return null;
        }
        if (loadClassNode(internal) == null) {
            return null;
        }
        ensureShape(internal);
        if (!shapedClasses.contains(internal)) {
            return null;
        }
        queueInit(internal);
        return internal;
    }

    private void drainWorklists() {
        while (!methodWorklist.isEmpty() || !shapeWorklist.isEmpty()
                || !initWorklist.isEmpty() || !instantiateWorklist.isEmpty()) {
            while (!methodWorklist.isEmpty()) {
                processMethodRef(methodWorklist.poll());
            }
            while (!shapeWorklist.isEmpty()) {
                ensureShape(shapeWorklist.poll());
            }
            while (!instantiateWorklist.isEmpty()) {
                markInstantiated(instantiateWorklist.poll());
            }
            while (!initWorklist.isEmpty()) {
                markInitialized(initWorklist.poll());
            }
        }
    }

    /** An instance of the class exists: lower its overrides of the signatures already called on its ancestors. */
    private void markInstantiated(final String internalName) {
        ensureShape(internalName);
        if (!shapedClasses.contains(internalName) || !instantiated.add(internalName)) {
            return;
        }
        context = "new " + internalName;
        queueInit(internalName);
        final ClassNode classNode = loadedClassNodes.get(internalName);
        if (classNode == null) {
            // A Nabu-source class: its own overrides are compiled from source.
            return;
        }
        // Direction (a): the class can declare overrides of signatures that
        // were already registered against an ancestor, also over several
        // hops (an interface call on java.util.List while this class is
        // ArrayList, with AbstractList/AbstractCollection in between). The
        // class itself counts too: calls on it so far only lowered
        // declarations (see dispatchOnly).
        final Set<String> selfAndAncestors = transitiveAncestors(classNode);
        selfAndAncestors.add(internalName);
        for (final String ancestor : selfAndAncestors) {
            final Set<String> sigs = virtualSignatures.get(ancestor);
            if (sigs == null) {
                continue;
            }
            for (final String sig : sigs) {
                queueOverrideIfDeclared(internalName, classNode, sig);
            }
        }
    }

    /** The class is initialized: lower its {@code <clinit>}, after its superclass's (JLS 12.4.2). */
    private void markInitialized(final String internalName) {
        ensureShape(internalName);
        if (!shapedClasses.contains(internalName) || !initialized.add(internalName)) {
            return;
        }
        context = "init " + internalName;
        final ClassNode classNode = loadedClassNodes.get(internalName);
        final IRBuilder builder = shapeBuilders.get(internalName);
        if (classNode == null || builder == null) {
            return;
        }
        if (classNode.superName != null) {
            queueInit(classNode.superName);
        }
        lowerClinitIfPresent(builder, internalName.replace('/', '.'), classNode);
    }

    /**
     * Maakt (idempotent) de "vorm" van een klasse aan: laadt de classNode,
     * registreert super/interfaces/velden als IRField/IRGlobal (zie taak uit
     * eerdere sessie - static-veld-globals, anders faalt native codegen met
     * "Onbekende global: ..."), lowert {@code <clinit>} onvoorwaardelijk, en
     * doet de override-propagatie in beide richtingen (zie klassejavadoc).
     */
    private void ensureShape(final String internalName) {
        if (shapedClasses.contains(internalName) || notFound.contains(internalName)) {
            return;
        }
        if (RUNTIME_PROVIDED.contains(internalName)) {
            // Grens van de sluiting: instanties van deze klasse maakt de
            // C-runtime, in zijn eigen representatie. Aanroepen blijven
            // externe symbolen die de runtime (of een stub) levert.
            notFound.add(internalName);
            return;
        }
        final String qualifiedName = internalName.replace('/', '.');
        if (alreadyCompiledQualifiedNames.contains(qualifiedName)) {
            shapedClasses.add(internalName);
            return;
        }

        final ClassNode classNode = loadClassNode(internalName);
        if (classNode == null) {
            notFound.add(internalName);
            return;
        }
        shapedClasses.add(internalName);
        classesShaped++;
        if (classesShaped % 50 == 0) {
            System.err.println("[BytecodeReachability] " + classesShaped + " classes shaped, "
                    + methodsLowered + " methods lowered, worklist: " + shapeWorklist.size()
                    + " shapes + " + methodWorklist.size() + " methods remaining, last: " + qualifiedName);
        }

        final long classFlags = AccessFlags.toNabuFlags(classNode.access);
        final var builder = new IRBuilder(classFlags, qualifiedName);
        shapeBuilders.put(internalName, builder);
        // build() geeft steeds hetzelfde (muterende) IRModule-object terug.
        builder.build().setBytecodeAnnotations(irAnnotations(classNode.visibleAnnotations));

        if (classNode.superName != null) {
            builder.superType(new IRType.Ptr(IRType.I8, "L" + classNode.superName + ";"));
            shapeWorklist.add(classNode.superName);
            directSubtypes.computeIfAbsent(classNode.superName, k -> new HashSet<>()).add(internalName);
        }
        if (!classNode.interfaces.isEmpty()) {
            final List<IRType> ifaceTypes = new ArrayList<>();
            for (final String itf : classNode.interfaces) {
                ifaceTypes.add(new IRType.Ptr(IRType.I8, "L" + itf + ";"));
                shapeWorklist.add(itf);
                directSubtypes.computeIfAbsent(itf, k -> new HashSet<>()).add(internalName);
            }
            builder.interfaces(ifaceTypes);
        }

        // Elk veld (static of instance) - struct-layout/metadata, en voor
        // static velden bovendien een IRGlobal onder EXACT de key die
        // emitFieldInsn's GETSTATIC/PUTSTATIC-lowering verwacht.
        for (final FieldNode fieldNode : classNode.fields) {
            final long fieldFlags = AccessFlags.toNabuFlags(fieldNode.access);
            final boolean isStatic = (fieldNode.access & Opcodes.ACC_STATIC) != 0;
            final IRType fieldType = DescriptorMapper.mapType(Type.getType(fieldNode.desc));

            final IRField field = IRField.field(fieldFlags, fieldNode.name, fieldType, null);
            field.setBytecodeAnnotations(irAnnotations(fieldNode.visibleAnnotations));
            builder.field(field);

            if (isStatic) {
                final IRType ownerType = new IRType.Ptr(IRType.I8, "L" + classNode.name + ";");
                builder.declareGlobal(qualifiedName + "_" + fieldNode.name, fieldType,
                        constantValue(fieldNode.value, fieldType), ownerType, true);
            }
        }

        for (final String ancestor : transitiveAncestors(classNode)) {
            transitiveSubtypes.computeIfAbsent(ancestor, k -> new HashSet<>()).add(internalName);
        }

        // Overrides (direction (a)) follow in markInstantiated, the
        // <clinit> in markInitialized: a class that is only shaped (a
        // field type, a cast, a superclass) needs neither.
    }

    /**
     * De beginwaarde van een statisch veld uit zijn {@code ConstantValue}-
     * attribuut (JVMS 4.7.2). Javac initialiseert zo'n constante niet in
     * {@code <clinit>}; zonder initializer bleef bv. {@code BreakIterator.DONE}
     * 0 in plaats van -1 en liep picocli's {@code TextTable.copy} voorbij het
     * einde ({@code substring(7, -1)}). String-constanten blijven null: een
     * {@code ConstString}-initializer wordt een C-string, geen String-object.
     */
    private static IRValue constantValue(final Object value, final IRType fieldType) {
        if (value instanceof Integer i) {
            return fieldType instanceof IRType.Bool
                    ? new IRValue.ConstBool(i != 0)
                    : new IRValue.ConstInt(i, fieldType);
        }
        if (value instanceof Long l) {
            return new IRValue.ConstInt(l, fieldType);
        }
        if (value instanceof Float f) {
            return new IRValue.ConstFloat(f, fieldType);
        }
        if (value instanceof Double d) {
            return new IRValue.ConstFloat(d, fieldType);
        }
        return null;
    }

    /**
     * Transitieve voorouders (superklasse-keten + alle interfaces, en hun
     * eigen voorouders) van {@code classNode} - leest alleen classNode's via
     * de (cachende) classLoader, zonder ze te "vormen"/loweren.
     */
    private Set<String> transitiveAncestors(final ClassNode classNode) {
        final Set<String> result = new HashSet<>();
        final Deque<String> pending = new ArrayDeque<>();
        if (classNode.superName != null) {
            pending.add(classNode.superName);
        }
        pending.addAll(classNode.interfaces);
        while (!pending.isEmpty()) {
            final String next = pending.poll();
            if (!result.add(next)) {
                continue;
            }
            final ClassNode ancestorNode = loadClassNode(next);
            if (ancestorNode == null) {
                continue;
            }
            if (ancestorNode.superName != null) {
                pending.add(ancestorNode.superName);
            }
            pending.addAll(ancestorNode.interfaces);
        }
        return result;
    }

    private void queueOverrideIfDeclared(final String internalName,
                                         final ClassNode classNode,
                                         final String nameAndDesc) {
        final int sep = nameAndDesc.indexOf('#');
        final String name = nameAndDesc.substring(0, sep);
        final String desc = nameAndDesc.substring(sep + 1);
        // Ook een implementatie die de klasse erft van een superklasse die de
        // interface zelf niet implementeert: LinkedHashMap$LinkedKeyIterator
        // implementeert Iterator, maar hasNext() staat in de abstracte
        // LinkedHashIterator. Zonder die te loweren wees de itable naar de
        // abstracte Iterator.hasNext en leek elke LinkedHashSet leeg.
        for (ClassNode node = classNode; node != null;
             node = node.superName != null ? loadClassNode(node.superName) : null) {
            for (final MethodNode m : node.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) {
                    if (node == classNode || (m.access & org.objectweb.asm.Opcodes.ACC_ABSTRACT) == 0) {
                        queueMethod(new MethodRef(internalName, name, desc, true));
                    }
                    return;
                }
            }
        }
    }

    private void lowerClinitIfPresent(final IRBuilder builder, final String qualifiedName, final ClassNode classNode) {
        // Een enum krijgt ook zijn values(): Enum.valueOf loopt via de runtime
        // (Class is VM-gebonden), die de constanten met values() opvraagt
        // (nabu_enum_table). Wordt values() nergens direct aangeroepen, dan
        // faalde valueOf (LocaleProviderAdapter$Type.valueOf("FALLBACK")).
        if ((classNode.access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0) {
            final String valuesDesc = "()[L" + classNode.name + ";";
            for (final MethodNode m : classNode.methods) {
                if ("values".equals(m.name) && valuesDesc.equals(m.desc)
                        && (m.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) {
                    queueMethod(new MethodRef(classNode.name, m.name, m.desc, false));
                }
            }
        }
        for (final MethodNode m : classNode.methods) {
            if ("<clinit>".equals(m.name)) {
                // Als gelowered registreren: de worklist (bv. de val-terug op
                // "alle methodes" van een seed zonder source-aanroepen) loweerde
                // hem anders nog eens, en de twee gelijknamige functies werden
                // overloads waarvan de .clinit.once-bewaking een stub koos.
                if (loweredMethods.add(classNode.name + "#" + m.name + "#" + m.desc)) {
                    lowerOne(builder, qualifiedName, classNode, m);
                }
                return;
            }
        }
    }

    private void processMethodRef(final MethodRef ref) {
        ensureShape(ref.ownerInternalName());
        if (notFound.contains(ref.ownerInternalName())) {
            return;
        }

        // Zoek de klasse die de methode daadwerkelijk IMPLEMENTEERT (kan een
        // voorouder zijn als ref.ownerInternalName() de methode overerft
        // zonder te overriden) - exact dezelfde hiërarchie-zoekstrategie als
        // ClassLayouts.resolveFunction gebruikt voor de native backend.
        String declaringInternal = ref.ownerInternalName();
        ClassNode declaringNode = loadClassNode(declaringInternal);
        final List<MethodNode> matches = new ArrayList<>();
        while (declaringNode != null) {
            if (alreadyCompiledQualifiedNames.contains(declaringInternal.replace('/', '.'))) {
                // De keten loopt hier de Nabu-source-wereld binnen (bv. een
                // JCL-stub die handmatig als Nabu-source bestaat, niet als
                // bytecode gelowered moet worden) - de implementatie bestaat
                // al onder Nabu's eigen naamconventie, niets hier te loweren.
                declaringNode = null;
                break;
            }
            matches.clear();
            for (final MethodNode m : declaringNode.methods) {
                if (!m.name.equals(ref.name())) {
                    continue;
                }
                if (ref.desc() == null || m.desc.equals(ref.desc())) {
                    matches.add(m);
                }
            }
            if (!matches.isEmpty()) {
                break;
            }
            declaringInternal = declaringNode.superName;
            declaringNode = declaringInternal != null ? loadClassNode(declaringInternal) : null;
        }

        if (matches.isEmpty()) {
            // Niet in de super-klasse-keten: een default-methode van een
            // (super-)interface, bv. `Checksum.update(byte[])` aangeroepen op
            // een CRC32-subklasse. Zonder die te loweren bestaat de methode
            // nergens en bindt de aanroep aan een naamgenoot met een ander
            // parameterprofiel.
            final Deque<String> pending = new ArrayDeque<>();
            final Set<String> seen = new HashSet<>();
            for (String current = ref.ownerInternalName(); current != null; ) {
                final ClassNode node = loadClassNode(current);
                if (node == null || !seen.add(current)) break;
                if (node.interfaces != null) pending.addAll(node.interfaces);
                current = node.superName;
            }
            while (!pending.isEmpty() && matches.isEmpty()) {
                final String itf = pending.poll();
                if (!seen.add(itf)
                        || alreadyCompiledQualifiedNames.contains(itf.replace('/', '.'))) {
                    continue;
                }
                final ClassNode node = loadClassNode(itf);
                if (node == null) continue;
                for (final MethodNode m : node.methods) {
                    if (m.name.equals(ref.name())
                            && (ref.desc() == null || m.desc.equals(ref.desc()))) {
                        matches.add(m);
                    }
                }
                if (!matches.isEmpty()) {
                    declaringInternal = itf;
                    declaringNode = node;
                } else if (node.interfaces != null) {
                    pending.addAll(node.interfaces);
                }
            }
        }

        if (matches.isEmpty()) {
            // Niet gevonden (bv. interface-default elders, of een methode
            // die alleen via een subklasse-override bestaat) - niets te
            // loweren hier; als het een echte bug is meldt de latere
            // link-fase een ontbrekend symbool.
            return;
        }

        ensureShape(declaringInternal);
        final IRBuilder declaringBuilder = shapeBuilders.get(declaringInternal);
        if (declaringBuilder == null) {
            return;
        }

        for (final MethodNode match : matches) {
            // Een bridge die alleen het returntype anders maakt (covariante
            // return: `Buffer limit(int)` naast `ByteBuffer limit(int)`) krijgt
            // dezelfde IR-naam en overload-sleutel als zijn doel - de backend
            // sleutelt op naam + parameters. Beide bodies belandden dan in één
            // LLVM-functie; de bridge kwam eerst en riep virtueel zichzelf aan.
            // De vtable heeft hem niet nodig (override-matching negeert het
            // returntype), dus het doel loweren in plaats van de bridge.
            final MethodNode bridgeTarget = returnOnlyBridgeTarget(declaringNode, match);
            final MethodNode m = bridgeTarget != null ? bridgeTarget : match;
            final String key = declaringInternal + "#" + m.name + "#" + m.desc;
            if (WHY != null) {
                final String node = methodNode(declaringInternal, m.name, m.desc);
                final String refNode = methodNode(ref.ownerInternalName(), ref.name(), ref.desc());
                if (!node.equals(refNode)) {
                    why.putIfAbsent(node, refNode);
                }
                context = node;
            }
            if (!loweredMethods.contains(key)) {
                if (dispatchOnly(ref, declaringNode, m)) {
                    if (!declarationStubs.containsKey(key)) {
                        declarationStubs.put(key, lowerDeclaration(declaringBuilder,
                                declaringInternal.replace('/', '.'), declaringNode, m));
                    }
                } else {
                    final IRFunction stub = declarationStubs.remove(key);
                    if (stub != null) {
                        declaringBuilder.build().removeFunction(stub);
                    }
                    loweredMethods.add(key);
                    lowerOne(declaringBuilder, declaringInternal.replace('/', '.'), declaringNode, m);
                    methodsLowered++;
                }
            }

            if (ref.virtual()) {
                final String sig = m.name + "#" + m.desc;
                final boolean isNewSignature = virtualSignatures
                        .computeIfAbsent(ref.ownerInternalName(), k -> new HashSet<>())
                        .add(sig);
                if (isNewSignature) {
                    propagateToKnownSubtypes(ref.ownerInternalName(), sig);
                }
            }
        }
    }

    /** key ("owner#name#desc") -> the body-less declaration lowered by {@link #lowerDeclaration}. */
    private final Map<String, IRFunction> declarationStubs = new HashMap<>();

    /**
     * Whether a virtual call only needs the method's vtable slot, not its
     * body: the receiver's static type is a class without instances, so the
     * call always dispatches to a subclass. An instantiated subclass that
     * inherits this implementation lowers it later (see
     * {@link #markInstantiated}, {@link #propagateToKnownSubtypes}).
     * Interface methods (default methods of lambda objects, which are not
     * tracked as instantiated), private and final methods are always lowered.
     */
    private boolean dispatchOnly(final MethodRef ref, final ClassNode declaringNode, final MethodNode method) {
        return ref.virtual()
                && !instantiated.contains(ref.ownerInternalName())
                && (declaringNode.access & Opcodes.ACC_INTERFACE) == 0
                && (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC
                        | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                && !method.name.startsWith("<");
    }

    /** Lowers {@code method} as an abstract declaration: it gets its vtable slot, its body is not reached. */
    private IRFunction lowerDeclaration(final IRBuilder builder, final String qualifiedName,
                                        final ClassNode classNode, final MethodNode method) {
        final MethodNode declaration = new MethodNode(method.access | Opcodes.ACC_ABSTRACT,
                method.name, method.desc, method.signature, null);
        lowerOne(builder, qualifiedName, classNode, declaration);
        final List<IRFunction> functions = builder.build().functions();
        return functions.get(functions.size() - 1);
    }

    /**
     * Het doel van een bridge-methode die alleen in returntype verschilt: een
     * niet-bridge-methode in dezelfde klasse met dezelfde naam en exact
     * dezelfde parametertypes; anders null (geen bridge, of een generieke
     * bridge met andere parametertypes, die wél nodig is voor dispatch).
     */
    private static MethodNode returnOnlyBridgeTarget(final ClassNode owner, final MethodNode method) {
        if ((method.access & org.objectweb.asm.Opcodes.ACC_BRIDGE) == 0 || owner == null) {
            return null;
        }
        final String params = method.desc.substring(0, method.desc.indexOf(')') + 1);
        for (final MethodNode candidate : owner.methods) {
            if (candidate != method
                    && (candidate.access & org.objectweb.asm.Opcodes.ACC_BRIDGE) == 0
                    && candidate.name.equals(method.name)
                    && candidate.desc.startsWith(params)) {
                return candidate;
            }
        }
        return null;
    }

    /** Richting (b) uit de klassejavadoc: al bereikte subtypes van {@code ancestor} herchecken voor een nieuwe signature. */
    private void propagateToKnownSubtypes(final String ancestor, final String nameAndDesc) {
        for (final String subtype : transitiveSubtypes.getOrDefault(ancestor, Set.of())) {
            if (!instantiated.contains(subtype)) {
                continue;
            }
            final ClassNode subtypeNode = loadedClassNodes.get(subtype);
            if (subtypeNode != null) {
                queueOverrideIfDeclared(subtype, subtypeNode, nameAndDesc);
            }
        }
    }

    private ClassNode loadClassNode(final String internalName) {
        return loadedClassNodes.computeIfAbsent(internalName,
                name -> BOOTSTRAP_CLASS.equals(name) ? bootstrapClass()
                        : EmbeddedResources.CLASS.equals(name) ? EmbeddedResources.build()
                        : AnnotationAccessors.CLASS.equals(name) ? AnnotationAccessors.build()
                        : withAnnotationAccessors(substitute(classLoader.load(name))));
    }

    /** Runtime-zichtbare annotaties, voor de reflectie-metadata (zie {@link IRAnnotation}). */
    private static List<IRAnnotation> irAnnotations(final List<AnnotationNode> annotations) {
        if (annotations == null) {
            return List.of();
        }
        final List<IRAnnotation> result = new ArrayList<>();
        for (final AnnotationNode annotation : annotations) {
            result.add(irAnnotation(annotation));
        }
        return result;
    }

    private static IRAnnotation irAnnotation(final AnnotationNode annotation) {
        final Map<String, Object> values = new java.util.LinkedHashMap<>();
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                values.put((String) annotation.values.get(i), irAnnotationValue(annotation.values.get(i + 1)));
            }
        }
        return new IRAnnotation(Type.getType(annotation.desc).getInternalName(), values);
    }

    private static Object irAnnotationValue(final Object value) {
        if (value instanceof AnnotationNode nested) {
            return irAnnotation(nested);
        }
        if (value instanceof List<?> list) {
            final List<Object> items = new ArrayList<>();
            for (final Object item : list) {
                items.add(irAnnotationValue(item));
            }
            return items;
        }
        return value;
    }

    private ClassNode withAnnotationAccessors(final ClassNode classNode) {
        if (classNode != null) {
            AnnotationAccessors.implementElements(classNode, classLoader::load);
        }
        return classNode;
    }

    private void lowerOne(final IRBuilder builder,
                          final String qualifiedName,
                          final ClassNode classNode,
                          final MethodNode methodNode) {
        final Set<String> typeRefs = new HashSet<>();
        final Set<MethodRef> methodRefs = new HashSet<>();
        final var lowering = new BytecodeMethodLowering(
                builder, qualifiedName, classNode, methodNode, typeRefs, methodRefs);
        try {
            lowering.lower();
        } catch (final RuntimeException e) {
            // Zonder deze wrapper verdwijnt de naam van de klasse/methode
            // die daadwerkelijk faalde in een kale stacktrace (bv. een
            // simpele NoSuchElementException diep in de stack-simulatie) -
            // met deze context is elke toekomstige crash meteen
            // herleidbaar tot de exacte bytecode-methode, zonder een
            // extra round-trip nodig te hebben.
            throw new RuntimeException(
                    "Bytecode lowering failed in " + qualifiedName + "." + methodNode.name
                            + methodNode.desc + ": " + e.getMessage(), e);
        }
        // Synthetische lambda-implementatieklassen (zie
        // BytecodeMethodLowering.emitLambdaMetafactoryIndy) horen niet via
        // de worklist geladen te worden (ze bestaan niet op de classpath) -
        // rechtstreeks aan het resultaat toevoegen. (De klasse-brede module
        // van `builder` zelf wordt pas aan het eind van compileClosure() één
        // keer toegevoegd, zie daar.)
        resultModules.addAll(lowering.extraModules());

        for (final String typeRef : typeRefs) {
            shapeWorklist.add(typeRef);
        }
        for (final MethodRef methodRef : methodRefs) {
            queueMethod(methodRef);
        }
        queueActiveUses(methodNode);
    }

    /** Instantiations and initializations (JLS 12.4.1) in a lowered method. */
    private void queueActiveUses(final MethodNode methodNode) {
        for (final var insn : methodNode.instructions) {
            switch (insn.getOpcode()) {
                case Opcodes.NEW -> queueInstantiate(((org.objectweb.asm.tree.TypeInsnNode) insn).desc);
                case Opcodes.GETSTATIC, Opcodes.PUTSTATIC ->
                        queueInit(((org.objectweb.asm.tree.FieldInsnNode) insn).owner);
                case Opcodes.INVOKESTATIC -> {
                    final String owner = ((org.objectweb.asm.tree.MethodInsnNode) insn).owner;
                    if (!owner.startsWith("[")) {
                        queueInit(owner);
                    }
                }
                default -> {
                }
            }
        }
    }
}
