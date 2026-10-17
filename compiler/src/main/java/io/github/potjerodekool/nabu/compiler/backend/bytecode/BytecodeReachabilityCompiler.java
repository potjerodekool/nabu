package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.FileManager;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
 * Berekent de transitieve sluiting van bytecode-only klassen (geen Nabu-
 * source) die bereikbaar zijn vanaf een gegeven set seed-klassen, en lowert
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
 *   <li>voor SEED-klassen: de methodes waarvan de NAAM daadwerkelijk
 *       voorkomt in een aanroep vanuit de al-gecompileerde Nabu-source
 *       (zie {@link #findCalledSeedMethodNames}) - bij ontbrekende/
 *       onherkende matches valt een seed terug op "lower alle methodes",
 *       zodat deze heuristiek nooit iets stilletjes laat vallen;</li>
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
            "java/io/File",
            "java/io/BufferedReader");

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
        return classNode;
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

    private final Deque<String> shapeWorklist = new ArrayDeque<>();
    private final Deque<MethodRef> methodWorklist = new ArrayDeque<>();

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
     * @param seedInternalNames JVM-interne klassenamen (slashes) die het
     *                          startpunt van de sluiting vormen, bv.
     *                          {@code "java/util/regex/Pattern"}.
     * @param alreadyCompiledModules de Nabu-source IRModules die al gebouwd
     *                          zijn - gescand op aanroepen naar de seed-
     *                          klassen, om te bepalen WELKE methodes van die
     *                          seeds daadwerkelijk nodig zijn (zie
     *                          {@link #findCalledSeedMethodNames}).
     * @return een IRModule per bereikte, nog niet gecompileerde klasse.
     */
    public List<IRModule> compileClosure(final Iterable<String> seedInternalNames,
                                         final List<IRModule> alreadyCompiledModules) {
        final long start = System.nanoTime();

        for (final String seedInternal : seedInternalNames) {
            seedShape(seedInternal, alreadyCompiledModules);
        }
        for (final MethodRef root : RUNTIME_ROOTS) {
            ensureShape(root.ownerInternalName());
            methodWorklist.add(root);
        }

        drainWorklists();

        // De seed-scan hierboven dekt alleen de seed-klassen. Nabu-source roept
        // ook methodes aan van klassen die pas via de sluiting gevormd zijn
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
        System.err.println("[BytecodeReachability] sluiting klaar: " + classesShaped
                + " klassen gevormd, " + methodsLowered + " methodes gelowered, "
                + notFound.size() + " niet gevonden/overgeslagen, totaal " + totalMs + " ms");

        return resultModules;
    }

    /** Backwards-compatibele overload zonder Nabu-source-scan (lowert dan alle methodes van de seeds zelf). */
    public List<IRModule> compileClosure(final Iterable<String> seedInternalNames) {
        return compileClosure(seedInternalNames, List.of());
    }

    private void seedShape(final String seedInternal, final List<IRModule> alreadyCompiledModules) {
        shapeWorklist.add(seedInternal);
        // Vorm meteen aanmaken zodat classNode beschikbaar is om de
        // aangeroepen-methodenamen tegen te matchen.
        ensureShape(seedInternal);
        final ClassNode classNode = loadedClassNodes.get(seedInternal);
        if (classNode == null) {
            return;
        }

        final Set<String> calledNames = findCalledSeedMethodNames(seedInternal, alreadyCompiledModules);
        if (calledNames.isEmpty()) {
            // Scan vond niets (bv. omdat deze seed uitsluitend door een
            // andere bytecode-klasse wordt aangeroepen, niet rechtstreeks
            // door Nabu-source) - veiligheidsnet: lower dan gewoon alle
            // methodes van deze seed, zoals de oude aanpak altijd deed. Dit
            // mag nooit stilletjes NIETS loweren.
            System.err.println("[BytecodeReachability] seed " + seedInternal
                    + ": geen aanroepen gevonden in Nabu-source, val terug op volledige klasse");
            for (final MethodNode methodNode : classNode.methods) {
                methodWorklist.add(new MethodRef(seedInternal, methodNode.name, methodNode.desc, false));
            }
            return;
        }
        for (final MethodNode methodNode : classNode.methods) {
            if (calledNames.contains(methodNode.name)) {
                methodWorklist.add(new MethodRef(seedInternal, methodNode.name, methodNode.desc, false));
            }
        }
    }

    /**
     * Scant alle {@code IRInstruction.Call}-instructies in de al-
     * gecompileerde Nabu-source-modules op aanroepen naar {@code seedInternal}
     * (herkend aan de {@code "<qualifiedSeedName>_"}-naamprefix - Nabu's
     * eigen, descriptor-loze aanroepnaamconventie, zie
     * BYTECODE_COMPILATION_PLAN.md's noot over het overload-naamsbotsings-
     * risico). Omdat de descriptor niet in de naam zit, kan dit alleen de
     * METHODENAAM opleveren, niet de exacte overload - de aanroeper lowert
     * daarom ALLE overloads met die naam in de seed-klasse.
     */
    private Set<String> findCalledSeedMethodNames(final String seedInternal,
                                                   final List<IRModule> alreadyCompiledModules) {
        final String prefix = seedInternal.replace('/', '.') + "_";
        // Constructor-aanroepen schrijft de frontend met underscores
        // (`java_lang_ProcessBuilder_init`); die vorm ook herkennen, anders
        // werd de constructor van een alleen-uit-source gebruikte klasse
        // nooit gelowered (link-fout).
        final String flatPrefix = seedInternal.replace('/', '_') + "_";
        final Set<String> names = new HashSet<>();
        for (final IRModule module : alreadyCompiledModules) {
            for (final var fn : module.functions()) {
                for (final var block : fn.blocks()) {
                    for (final IRInstruction insn : block.instructions()) {
                        if (insn instanceof IRInstruction.Call call
                                && call.function() != null
                                && (call.function().startsWith(prefix)
                                    || call.function().startsWith(flatPrefix))) {
                            String name = call.function().substring(prefix.length());
                            if ("init".equals(name)) {
                                name = "<init>";
                            } else if ("clinit".equals(name)) {
                                name = "<clinit>";
                            }
                            names.add(name);
                        }
                    }
                }
            }
        }
        return names;
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
                    methodWorklist.add(new MethodRef(owner, name, null, entry.getValue()));
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
                    methodWorklist.add(new MethodRef(target, "<init>", "()V", false));
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
     * {@code new ProcessBuilder(cmd)}) zit niet in de sluiting van de seeds;
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
        return shapedClasses.contains(internal) ? internal : null;
    }

    private void drainWorklists() {
        while (!methodWorklist.isEmpty() || !shapeWorklist.isEmpty()) {
            while (!methodWorklist.isEmpty()) {
                processMethodRef(methodWorklist.poll());
            }
            while (!shapeWorklist.isEmpty()) {
                ensureShape(shapeWorklist.poll());
            }
        }
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
            System.err.println("[BytecodeReachability] " + classesShaped + " klassen gevormd, "
                    + methodsLowered + " methodes gelowered, worklist: " + shapeWorklist.size()
                    + " vormen + " + methodWorklist.size() + " methodes resterend, laatste: " + qualifiedName);
        }

        final long classFlags = AccessFlags.toNabuFlags(classNode.access);
        final var builder = new IRBuilder(classFlags, qualifiedName);
        shapeBuilders.put(internalName, builder);

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

            builder.field(IRField.field(fieldFlags, fieldNode.name, fieldType, null));

            if (isStatic) {
                final IRType ownerType = new IRType.Ptr(IRType.I8, "L" + classNode.name + ";");
                builder.declareGlobal(qualifiedName + "_" + fieldNode.name, fieldType,
                        constantValue(fieldNode.value, fieldType), ownerType, true);
            }
        }

        // Richting (a): deze klasse kan overrides declareren van signatures
        // die al tegen een voorouder geregistreerd zijn, ook via meerdere
        // hops (bv. een interface-aanroep tegen java.util.List terwijl deze
        // klasse ArrayList is, met AbstractList/AbstractCollection ertussen).
        for (final String ancestor : transitiveAncestors(classNode)) {
            transitiveSubtypes.computeIfAbsent(ancestor, k -> new HashSet<>()).add(internalName);
            final Set<String> sigs = virtualSignatures.get(ancestor);
            if (sigs == null) {
                continue;
            }
            for (final String sig : sigs) {
                queueOverrideIfDeclared(internalName, classNode, sig);
            }
        }

        lowerClinitIfPresent(builder, qualifiedName, classNode);

        // Richting (b): als deze klasse op haar beurt weer ancestor is van
        // al eerder bereikte subtypes (via directSubtypes), hoeft hier niets
        // extra te gebeuren - dat gebeurt bij het REGISTREREN van een
        // virtuele signature (zie processMethodRef), niet bij shape-aanmaak.
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
                        methodWorklist.add(new MethodRef(internalName, name, desc, true));
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
                    methodWorklist.add(new MethodRef(classNode.name, m.name, m.desc, false));
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
            if (loweredMethods.add(key)) {
                lowerOne(declaringBuilder, declaringInternal.replace('/', '.'), declaringNode, m);
                methodsLowered++;
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
                        : substitute(classLoader.load(name)));
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
                    "Bytecode-lowering mislukt in " + qualifiedName + "." + methodNode.name
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
            methodWorklist.add(methodRef);
        }
    }
}
