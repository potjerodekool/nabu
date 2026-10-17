package io.github.potjerodekool.nabu.backend.ir;

import io.github.potjerodekool.nabu.backend.asm.JvmSignatureBuilder;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction.BinaryOp.Op;
import io.github.potjerodekool.nabu.backend.ir.optimize.TypeInference;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.lang.model.element.CompoundAttribute;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.type.ArrayType;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.CompileException;

import java.util.*;

/**
 * Vertaalt de Nabu AST naar een IRModule.
 * <p>
 * Implementeert TreeVisitor<IRValue, IRBuilder>:
 * - R = IRValue ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø elke visit-methode geeft de IR-waarde terug
 * die de expressie vertegenwoordigt (null voor statements)
 * - P = IRBuilder ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø de actieve builder wordt als parameter doorgegeven
 * <p>
 * Gebruik:
 * var visitor = new IrGeneratingVisitor();
 * compilationUnit.accept(visitor, null);
 * IRModule module = visitor.getModule();
 */
public class IrGeneratingVisitor extends AbstractTreeVisitor<IRValue, IRBuilder> {

    private IRBuilder builder;
    private int ternaryCounter;
    private IRModule module;
    private final List<IRModule> modules = new ArrayList<>();

    private final ScopeTracker scope = new ScopeTracker();

    // Naam van de klasse die nu verwerkt wordt (voor this-verwijzingen)
    private String currentClassName;

    // TypeElement van de klasse die nu verwerkt wordt (voor outer-instance
    // resolution: JLS ├âÔÇÜ├é┬º8.8 synthetic outer `this$0`-keten).
    private TypeElement currentTypeElement;

    // Teller voor unieke lambda-functienamen
    private int lambdaCounter = 0;

    // Loop-context stack voor break/continue
    private final Deque<IRBasicBlock> breakTargets = new ArrayDeque<>();
    private final Deque<IRBasicBlock> continueTargets = new ArrayDeque<>();

    // Gelabelde break/continue doelen
    private final Map<String, IRBasicBlock> labeledBreakTargets = new LinkedHashMap<>();
    private final Map<String, IRBasicBlock> labeledContinueTargets = new LinkedHashMap<>();

    // Labels die wachten op een continue target (gevuld door de volgende lus)
    private final Deque<String> pendingLabels = new ArrayDeque<>();

    // Try-catch ranges die verzameld worden voor de ASM-backends
    private final List<TryCatchRange> tryCatchRanges = new ArrayList<>();

    // Veld-initializers die op klasseniveau (zonder actief basisblok)
    // geregistreerd worden; de constructor(s) en <clinit> emitteren ze.
    private final Map<Element, ExpressionTree> pendingFieldInitializers = new LinkedHashMap<>();

    /**
     * Een anonieme klasse (JLS §15.9.5) zoals de aanmaakplek haar ziet: of ze
     * een enclosing instance heeft (aangemaakt in een niet-statische context),
     * welke lokale variabelen/parameters ze vangt, en hoe haar constructor de
     * super-constructor aanroept. De body wordt na de omsluitende klasse als
     * eigen module geëmitteerd (zie {@link #emitPendingAnonymousClasses}).
     */
    private record AnonymousClassInfo(ClassDeclaration declaration,
                                      boolean hasOuter,
                                      List<Element> captures,
                                      String superInitName,
                                      List<IRType> superParamTypes) {
    }

    /** Nog te emitteren anonieme klassen, in volgorde van aanmaak. */
    private final List<AnonymousClassInfo> pendingAnonymousClasses = new ArrayList<>();
    /** Alle bekende anonieme klassen op gekwalificeerde naam. */
    private final Map<String, AnonymousClassInfo> anonymousClasses = new HashMap<>();
    /** De anonieme klasse die nu geëmitteerd wordt, of null. */
    private AnonymousClassInfo currentAnonymous;

    // Bronbestand van de verwerkte compilatie-eenheid (voor debuginfo van
    // top-level ├âãÆ├åÔÇÖ├âÔÇÜ├é┬®n geneste klassen).
    private String currentSourceFile;
    private String currentSourcePath;

    public record TryCatchRange(String tryStart, String tryEnd, String handler, String exceptionType) {}

    // -------------------------------------------------------
    // Resultaat
    // -------------------------------------------------------

    public IRModule getModule() {
        return modules.isEmpty() ? null : modules.getFirst();
    }

    public List<IRModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<TryCatchRange> getTryCatchRanges() {
        return Collections.unmodifiableList(tryCatchRanges);
    }

    // -------------------------------------------------------
    // Fallback
    // -------------------------------------------------------

    @Override
    public IRValue visitUnknown(Tree tree, IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // CompilationUnit
    // -------------------------------------------------------

    @Override
    public IRValue visitCompilationUnit(CompilationUnit compilationUnit,
                                        IRBuilder param) {
        String fileName = compilationUnit.getFileObject() != null
                ? compilationUnit.getFileObject().getFileName()
                : "<onbekend>";

        final var noTopLevel = compilationUnit.getClasses().stream()
                .noneMatch(it -> it.getNestingKind() == NestingKind.TOP_LEVEL);
        if (noTopLevel) {
            System.err.println("[IR-CU] Compilatie-eenheid zonder top-level class (alleen geneste/anonieme): "
                    + fileName + " ; klassen: "
                    + compilationUnit.getClasses().stream()
                            .map(it -> it.getKind() + ":" + it.getSimpleName())
                            .toList());
        }

        // Bronbestand registreren voor debuginfo
        int lastSlash = fileName.lastIndexOf('/');
        String dir = lastSlash >= 0 ? fileName.substring(0, lastSlash) : ".";
        String file = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;

        // Elke top-level class krijgt een eigen IRModule.
        // Voorheen werd de hele CU in ``n module ge├âãÆ├åÔÇÖ├âÔÇÜ├é┬¬mmit waardoor methodes en
        // constructors van meerdere classes in de eerste class terechtkwamen.
        currentSourceFile = file;
        currentSourcePath = dir;
        for (ClassDeclaration cls : compilationUnit.getClasses()) {
            try {
                emitClassModule(cls);
            } catch (final RuntimeException npe) {
                npe.printStackTrace();
                throw npe;
            }
        }

        return null;
    }

    /**
     * Bouwt een eigen IRModule voor de gegeven klasse-declaratie (top-level
     * ├âãÆ├åÔÇÖ├âÔÇÜ├é┬®n genest); geneste klassen (bv. picocli's Tracer, RunLast) krijgen
     * hiermee net als top-level classes hun eigen module en classfile.
     */
    private void emitClassModule(final ClassDeclaration cls) {
        // State van de (eventuele) outer classe bewaren: een nested-class-emissie
        // vervangt de visitor-module/builder-fields, en de outer-loops in
        // visitClass draaien n├âãÆ├é┬í de nested-emissie nog door in hun eigen module.
        final var previousModule = module;
        final var previousBuilder = builder;
        final var previousPendingFieldInits = new LinkedHashMap<>(pendingFieldInitializers);

        resetClassState();
        pendingFieldInitializers.clear();

        final var classSymbol = cls.getClassSymbol();
        final var moduleName = classSymbol.getQualifiedName();
        long classFlags = Flags.parse(classSymbol.getModifiers());
        if (classSymbol.getKind().isInterface()) {
            classFlags |= Flags.INTERFACE;
        }
        if (classSymbol.getKind() == ElementKind.ENUM) {
            // De parser zet alleen Kind.ENUM; de ACC_ENUM-vlag moet
            // expliciet naar de IR-vlaggen vertaald worden.
            classFlags |= Flags.ENUM;
        }
        builder = new IRBuilder(classFlags, moduleName);
        module = builder.build();
        module.setSourceFile(currentSourceFile, currentSourcePath);

        acceptTree(cls, builder);

        // Een nested-class-emissie diep in de geneste-modules-loop VERVANGT
        // de visitor-module/builder-fields; zonder herstel verdwijnt de
        // module van de OUTER klasse en wordt de innerlijke dubbel
        // geregistreerd (bv. picocli's Model$CaseAwareLinkedMap vs
        // ├â┬ó├óÔÇÜ┬¼├é┬ª$CaseAwareKeySet).
        modules.add(module);
        module = previousModule;
        builder = previousBuilder;
        if (previousPendingFieldInits != null) {
            pendingFieldInitializers.clear();
            pendingFieldInitializers.putAll(previousPendingFieldInits);
        }
    }

    private void resetClassState() {
        currentClassName = null;
        currentTypeElement = null;
        lambdaCounter = 0;
        scope.reset();
        breakTargets.clear();
        continueTargets.clear();
        labeledBreakTargets.clear();
        labeledContinueTargets.clear();
        pendingLabels.clear();
        tryCatchRanges.clear();
    }

    /**
     * IRNamed voor het synthetic outer-instance veld `this$0` van de
     * class die nu verwerkt wordt (ownerType = current class).
     */
    private IRValue.Named syntheticOuterFieldNamed() {
        final var currentClass = currentTypeElement;
        if (currentClass == null) {
            return null;
        }
        final var outerElement = outerOf(currentClass);
        return new IRValue.Named(
                "this$0",
                TypeMirrorToIRType.map(outerElement.asType()),
                TypeMirrorToIRType.map(currentClass.asType()),
                false
        );
    }

    // element-side MEMBER (lang.model.element.NestingKind), los van de
    // tree.element.NestingKind import hierboven.
    private static final io.github.potjerodekool.nabu.lang.model.element.NestingKind
            elementNestingMember = io.github.potjerodekool.nabu.lang.model.element.NestingKind.MEMBER;

    private TypeElement currentTypeElementOf(final io.github.potjerodekool.nabu.lang.model.element.Element classElement) {
        if (classElement instanceof TypeElement typeElement) {
            return typeElement;
        }
        return null;
    }

    /** De enclosing class van een member class, of null. */
    private TypeElement outerOf(final TypeElement typeElement) {
        final var enclosing = typeElement.getEnclosingElement();
        return enclosing instanceof TypeElement outerType
                ? outerType : null;
    }

    /**
     * True voor een niet-statische member class zonder enclosing instance
     * (JLS ├âÔÇÜ├é┬º8.1.3): enumeration/records/interfaces zijn impliciet statisch.
     */
    private boolean needsOuterInstance(final TypeElement typeElement) {
        if (typeElement != null
                && typeElement.getNestingKind() == io.github.potjerodekool.nabu.lang.model.element.NestingKind.ANONYMOUS) {
            final var info = anonymousClasses.get(qualifiedNameOf(typeElement));
            return info != null && info.hasOuter();
        }
        if (typeElement == null
                || typeElement.getNestingKind() != elementNestingMember
                || typeElement.isStatic()
                || typeElement.getKind().isInterface()
                || typeElement.getKind() == ElementKind.ENUM
                || typeElement.getKind() == ElementKind.ANNOTATION_TYPE
                || typeElement.getKind() == ElementKind.RECORD) {
            return false;
        }
        final var outer = outerOf(typeElement);
        return outer != null;
    }

    /**
     * Bool voor constructeurs van member classes: elke <init> neemt de
     * enclosing instance als eerste parameter (JLS ├âÔÇÜ├é┬º8.8.7).
     */
    private boolean isOuterInstanceCtor(final Element methodSymbolElement) {
        if (!(methodSymbolElement instanceof ExecutableElement executableElement)) {
            return false;
        }
        if (executableElement.getKind() != ElementKind.CONSTRUCTOR) {
            return false;
        }
        final var declaring = executableElement.getEnclosingElement();
        if (!(declaring instanceof TypeElement typeElement)) {
            return false;
        }
        if (typeElement.getNestingKind() != elementNestingMember
                || typeElement.isStatic()
                || typeElement.getKind() == ElementKind.ENUM
                || typeElement.getKind() == ElementKind.RECORD
                || typeElement.getKind().isInterface()) {
            return false;
        }
        final var outer = outerOf(typeElement);
        return outer != null;
    }

    /**
     * Resolueert de enclosing-instance-waarde (JLS ├âÔÇÜ├é┬º15.9.2) voor instanties
     * van targetClass vanuit de huidige klasse-chain: chain[0] is de
     * huidige class (waarde = `this`); elke volgende stap laadt het
     * synthetic veld `this$0` van de vorige class. De constructor-context
     * heeft de raw outer-param (`this$0`) al in scope ├â┬ó├óÔÇÜ┬¼├óÔé¼┬Ø die wordt op
     * de eerste stap gebruikt.
     */
    private IRValue enclosingInstanceValue(final TypeElement targetClass) {
        if (targetClass == null
                || currentTypeElement == null) {
            return null;
        }

        final var thisValue = scope.lookup("this").orElse(null);
        if (thisValue == null) {
            return null;
        }

        final var targetName = qualifiedNameOf(targetClass);
        var chainClass = currentTypeElement;
        var value = thisValue;
        while (chainClass != null) {
            if (qualifiedNameOf(chainClass).equals(targetName)) {
                return value;
            }
            final var outer = outerOf(chainClass);
            if (outer == null) {
                return null;
            }
            // Als de chain-class niet stond voor een synthetische
            // this$0-veldlaag volgt er geen enclosing instance meer.
            if (chainClass != currentTypeElement
                    && !needsOuterInstance(chainClass)) {
                return null;
            }

            IRValue stepValue = chainClass == currentTypeElement
                    ? scope.lookup("this$0").orElse(null)
                    : null;
            if (stepValue == null) {
                stepValue = builder.emitLoad(new IRValue.Values(
                        value,
                        new IRValue.Named(
                                "this$0",
                                TypeMirrorToIRType.map(outer.asType()),
                                TypeMirrorToIRType.map(chainClass.asType()),
                                false
                        )
                ));
            }
            value = stepValue;
            chainClass = outer;
        }

        return null;
    }

    /**
     * De ontvanger van een instantieveld dat zonder {@code this.} wordt
     * benoemd: {@code this} als de huidige klasse het veld declareert of erft,
     * anders de enclosing instance die dat doet (JLS §15.11, §8.1.3). Een
     * inner class die een veld van de outer class leest
     * ({@code targetMap} in picocli's {@code CaseAwareKeySet}) las het anders
     * met de layout van de outer class uit {@code this}.
     */
    private IRValue implicitFieldReceiver(final Element field) {
        final IRValue thisVal = scope.lookup("this").orElse(null);
        if (thisVal == null
                || currentTypeElement == null
                || !(field.getEnclosingElement() instanceof TypeElement owner)
                || isSameOrSubclass(currentTypeElement, owner)) {
            return thisVal;
        }
        for (var outer = outerOf(currentTypeElement); outer != null; outer = outerOf(outer)) {
            if (isSameOrSubclass(outer, owner)) {
                final var outerValue = enclosingInstanceValue(outer);
                return outerValue != null ? outerValue : thisVal;
            }
        }
        return thisVal;
    }

    /**
     * Als {@link #implicitFieldReceiver}, maar voor methoden: ook via
     * (super-)interfaces geërfde methoden (default-methoden) horen bij
     * {@code this}.
     */
    private IRValue implicitMethodReceiver(final Element method) {
        final IRValue thisVal = scope.lookup("this").orElse(null);
        if (thisVal == null
                || currentTypeElement == null
                || method == null
                || !(method.getEnclosingElement() instanceof TypeElement owner)
                || isSubtypeOf(currentTypeElement, owner)) {
            return thisVal;
        }
        for (var outer = outerOf(currentTypeElement); outer != null; outer = outerOf(outer)) {
            if (isSubtypeOf(outer, owner)) {
                final var outerValue = enclosingInstanceValue(outer);
                return outerValue != null ? outerValue : thisVal;
            }
        }
        return thisVal;
    }

    /** {@code type} is {@code owner}, of erft ervan via super-klassen of interfaces. */
    private boolean isSubtypeOf(final TypeElement type, final TypeElement owner) {
        final var ownerName = qualifiedNameOf(owner);
        final var seen = new HashSet<String>();
        final Deque<TypeElement> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            final TypeElement current = pending.poll();
            if (!seen.add(qualifiedNameOf(current))) continue;
            if (qualifiedNameOf(current).equals(ownerName)) return true;
            if (current.getSuperclass() instanceof io.github.potjerodekool.nabu.type.DeclaredType superType
                    && superType.asTypeElement() != null) {
                pending.add(superType.asTypeElement());
            }
            for (final var itf : current.getInterfaces()) {
                if (itf instanceof io.github.potjerodekool.nabu.type.DeclaredType itfType
                        && itfType.asTypeElement() != null) {
                    pending.add(itfType.asTypeElement());
                }
            }
        }
        return false;
    }

    private boolean isSameOrSubclass(final TypeElement type, final TypeElement owner) {
        final var ownerName = qualifiedNameOf(owner);
        final var seen = new HashSet<String>();
        TypeElement current = type;
        while (current != null && seen.add(qualifiedNameOf(current))) {
            if (qualifiedNameOf(current).equals(ownerName)) {
                return true;
            }
            current = current.getSuperclass() instanceof io.github.potjerodekool.nabu.type.DeclaredType superType
                    ? superType.asTypeElement()
                    : null;
        }
        return false;
    }

    private String qualifiedNameOf(final TypeElement typeElement) {
        return typeElement.getQualifiedName() != null
                ? typeElement.getQualifiedName()
                : String.valueOf(typeElement.getSimpleName());
    }

    // -------------------------------------------------------
    // Klasse-declaratie
    // -------------------------------------------------------

    @Override
    public IRValue visitClass(ClassDeclaration classDeclaration,
                              IRBuilder param) {
        currentClassName = classDeclaration.getSimpleName();

        final var classSymbol = classDeclaration.getClassSymbol();
        currentTypeElement = currentTypeElementOf(classSymbol);
        final AnonymousClassInfo anonymous = currentTypeElement != null
                ? anonymousClasses.get(qualifiedNameOf(currentTypeElement)) : null;
        if (anonymous != null) {
            // De korte naam van een anonieme klasse ("1") is niet uniek; de
            // gekwalificeerde naam zonder package (CommandLine$1) wel.
            final String qualified = qualifiedNameOf(currentTypeElement);
            currentClassName = qualified.substring(qualified.lastIndexOf('.') + 1);
        }
        if (classSymbol != null) {
            final var annotationMirrors = classSymbol.getAnnotationMirrors();
            module.setAnnotations(annotationMirrors.stream()
                    .filter(a -> a instanceof CompoundAttribute)
                    .map(a -> (CompoundAttribute) a)
                    .toList());

            // Set super type and interfaces with full generic info
            final var superMirror = classSymbol.getSuperclass();
            if (superMirror != null
                    && superMirror.getKind() != TypeKind.NONE
                    && !isJavaLangObject(superMirror)) {
                builder.superType(TypeMirrorToIRType.map(superMirror));
            }

            final var ifaces = classSymbol.getInterfaces();
            if (ifaces != null && !ifaces.isEmpty()) {
                builder.interfaces(ifaces.stream()
                        .map(TypeMirrorToIRType::map)
                        .toList());
            }

            // Generate class-level generic signature
            final var classSignature = JvmSignatureBuilder.buildClassSignature(classSymbol);
            if (classSignature != null) {
                module.setGenericSignature(classSignature);
            }
        }

        // JLS ├âÔÇÜ├é┬º8.8.1: een niet-statische member class draagt een synthetic
        // outer-instance veld `this$0` (typed als de enclosing class). De
        // constructors van de klasse vullen het veld; qualified-this
        // (`Ansi.this`) leest het veld.
        if (currentTypeElement != null
                && needsOuterInstance(currentTypeElement)) {
            final var outerElement = outerOf(currentTypeElement);
            final var outerType = TypeMirrorToIRType.map(outerElement.asType());
            module.emitField(IRField.field(
                    Flags.PRIVATE | Flags.FINAL | Flags.SYNTHETIC,
                    "this$0",
                    outerType,
                    null
            ));
        }

        if (classDeclaration.getKind() == Kind.RECORD) {
            final var compactConstructorOptional = TreeFilter.constructorsIn(classDeclaration.getEnclosedElements()).stream()
                    .filter(c -> c.hasFlag(Flags.COMPACT_RECORD_CONSTRUCTOR))
                    .findFirst();

            compactConstructorOptional.ifPresent(compactConstructor -> compactConstructor.getParameters().forEach(recordComponent -> {

                module.emitField(
                        IRField.recordComponent(
                                recordComponent.getName().getName(),
                                TypeMirrorToIRType.map(recordComponent.getType())
                        )
                );

                acceptTree(recordComponent, param);
            }));
        }

        // Velden eerst (zodat de pending-initializers geregistreerd zijn
        // v├âãÆ├åÔÇÖ├âÔÇÜ├é┬│├âãÆ├åÔÇÖ├âÔÇÜ├é┬│rdat de constructor/clinit ze emitteert), daarna methoden.
        for (Tree member : classDeclaration.getEnclosedElements()) {
            if (member instanceof VariableDeclaratorTree variableDeclaratorTree) {
                acceptTree(variableDeclaratorTree, builder);
            }
        }
        if (anonymous != null) {
            // Gevangen lokale variabelen als synthetische velden, ná de
            // gedeclareerde velden (computeFieldIndex telt alleen die laatste).
            for (final Element captured : anonymous.captures()) {
                module.emitField(IRField.field(
                        Flags.PRIVATE | Flags.FINAL | Flags.SYNTHETIC,
                        capturedFieldName(captured),
                        TypeMirrorToIRType.map(captured.asType()),
                        null));
            }
        }
        for (Tree member : classDeclaration.getEnclosedElements()) {
            if (member instanceof Function function) {
                if (anonymous != null && function.getMethodSymbol() != null
                        && function.getMethodSymbol().getKind() == ElementKind.CONSTRUCTOR) {
                    // De impliciete constructor van de frontend kent de
                    // super-argumenten en gevangen waarden niet; zie hieronder.
                    continue;
                }
                acceptTree(function, builder);
            }
        }
        if (anonymous != null) {
            emitAnonymousConstructor(anonymous);
        }
        emitBridgeMethods();
        // Geneste klassen krijgen elk hun eigen IRModule (actor: nested
        // klassen zoals picocli's RunLast/Tracer leiden eigen classfiles).
        for (Tree member : classDeclaration.getEnclosedElements()) {
            if (member instanceof ClassDeclaration nestedClassDeclaration) {
                emitClassModule(nestedClassDeclaration);
            }
        }
        emitPendingAnonymousClasses();
        return null;
    }

    // -------------------------------------------------------
    // Anonieme klassen (JLS §15.9.5)
    // -------------------------------------------------------

    private static String capturedFieldName(final Element captured) {
        return "val$" + captured.getSimpleName();
    }

    /** Emitteert de verzamelde anonieme klassen, elk als eigen module. */
    private void emitPendingAnonymousClasses() {
        while (!pendingAnonymousClasses.isEmpty()) {
            final AnonymousClassInfo info = pendingAnonymousClasses.removeFirst();
            final AnonymousClassInfo previous = currentAnonymous;
            final String previousClassName = currentClassName;
            final TypeElement previousTypeElement = currentTypeElement;
            currentAnonymous = info;
            try {
                emitClassModule(info.declaration());
            } finally {
                currentAnonymous = previous;
                currentClassName = previousClassName;
                currentTypeElement = previousTypeElement;
            }
        }
    }

    /**
     * De constructor van een anonieme klasse:
     * {@code <init>(this, [outer], gevangen..., superArgs...)}. Zoals javac
     * slaat hij eerst de enclosing instance en de gevangen waarden op (de
     * super-constructor mag al virtueel terugroepen), roept dan de
     * super-constructor aan en draait de veld-initializers.
     */
    private void emitAnonymousConstructor(final AnonymousClassInfo info) {
        final var thisType = new IRType.Ptr(IRType.I8,
                TypeMirrorToIRType.toJvmDescriptor(currentTypeElement.asType()));
        final IRValue thisParam = new IRValue.Temp("%this", thisType);
        final List<IRValue> params = new ArrayList<>();
        params.add(thisParam);
        IRValue outerParam = null;
        if (info.hasOuter()) {
            final var outerElement = outerOf(currentTypeElement);
            outerParam = new IRValue.Temp("%this$0", new IRType.Ptr(IRType.I8,
                    TypeMirrorToIRType.toJvmDescriptor(outerElement.asType())));
            params.add(outerParam);
        }
        final List<IRValue> captureParams = new ArrayList<>();
        for (final Element captured : info.captures()) {
            final IRValue p = new IRValue.Temp("%cap$" + captured.getSimpleName(),
                    TypeMirrorToIRType.map(captured.asType()));
            captureParams.add(p);
            params.add(p);
        }
        final List<IRValue> superParams = new ArrayList<>();
        for (int i = 0; i < info.superParamTypes().size(); i++) {
            final IRValue p = new IRValue.Temp("%sup" + i, info.superParamTypes().get(i));
            superParams.add(p);
            params.add(p);
        }

        scope.reset();
        builder.beginFunction(currentClassName + "_init", IRType.VOID, params, 0L, true);
        if (outerParam != null) {
            builder.emitStore(new IRValue.Values(thisParam, syntheticOuterFieldNamed()), outerParam);
        }
        for (int i = 0; i < captureParams.size(); i++) {
            final Element captured = info.captures().get(i);
            builder.emitStore(new IRValue.Values(thisParam, new IRValue.Named(
                    capturedFieldName(captured),
                    TypeMirrorToIRType.map(captured.asType()),
                    thisType,
                    false)), captureParams.get(i));
        }
        final List<IRValue> superArgs = new ArrayList<>();
        superArgs.add(thisParam);
        superArgs.addAll(superParams);
        builder.emitCall(CallKind.SPECIAL, info.superInitName(), IRType.VOID,
                info.superParamTypes(), superArgs);
        scope.pushScope();
        scope.define("this", thisParam);
        emitPendingFieldInitializers(true, true);
        scope.popScope();
        builder.emitReturn(null);
        builder.endFunction();
    }

    /**
     * De lokale variabelen en parameters die de body van een anonieme klasse
     * gebruikt maar niet zelf declareert: de gevangen waarden, in volgorde
     * van eerste gebruik.
     */
    private static List<Element> computeCaptures(final ClassDeclaration declaration) {
        final Set<Element> declared = new HashSet<>();
        final Set<Element> used = new LinkedHashSet<>();
        // AbstractTreeVisitor daalt niet af in o.a. try/catch, array-toegang
        // en instanceof; die vullen we hier aan, anders ontbrak bv. een in een
        // try-body gebruikte variabele en werd de ontvanger null.
        new io.github.potjerodekool.nabu.tree.AbstractTreeVisitor<Object, Object>() {
            private void visitAll(final Object p, final Tree... trees) {
                for (final Tree tree : trees) {
                    if (tree != null) {
                        acceptTree(tree, p);
                    }
                }
            }

            private void visitAll(final Object p, final List<? extends Tree> trees) {
                if (trees != null) {
                    trees.forEach(tree -> visitAll(p, tree));
                }
            }

            @Override
            public Object visitTryStatement(final TryStatementTree tree, final Object p) {
                visitAll(p, tree.getResources());
                visitAll(p, tree.getBody());
                visitAll(p, tree.getCatchers());
                visitAll(p, tree.getFinalizer());
                return null;
            }

            @Override
            public Object visitCatch(final CatchTree tree, final Object p) {
                visitAll(p, tree.getVariable(), tree.getBody());
                return null;
            }

            @Override
            public Object visitArrayAccess(final ArrayAccessExpressionTree tree, final Object p) {
                visitAll(p, tree.getExpression(), tree.getIndex());
                return null;
            }

            @Override
            public Object visitNewArray(final NewArrayExpression tree, final Object p) {
                visitAll(p, tree.getDimensions());
                visitAll(p, tree.getElements());
                return null;
            }

            @Override
            public Object visitInstanceOfExpression(final InstanceOfExpression tree, final Object p) {
                visitAll(p, tree.getExpression(), tree.getTypeExpression());
                return null;
            }

            @Override
            public Object visitTypePattern(final TypePattern tree, final Object p) {
                visitAll(p, tree.getVariableDeclarator());
                return null;
            }

            @Override
            public Object visitMemberReference(final MemberReference tree, final Object p) {
                visitAll(p, tree.getExpression());
                return null;
            }

            @Override
            public Object visitLabeledStatement(final LabeledStatement tree, final Object p) {
                visitAll(p, tree.getStatement());
                return null;
            }

            @Override
            public Object visitSynchronizedStatement(final SynchronizedStatement tree, final Object p) {
                visitAll(p, tree.getExpression(), tree.getBody());
                return null;
            }

            @Override
            public Object visitYieldStatement(final YieldStatement tree, final Object p) {
                visitAll(p, tree.getExpression());
                return null;
            }

            @Override
            public Object visitAssertStatement(final AssertStatement tree, final Object p) {
                visitAll(p, tree.getCondition(), tree.getDetail());
                return null;
            }

            @Override
            public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variable, final Object p) {
                if (variable.getName() instanceof CIdentifierTree ci && ci.getSymbol() != null) {
                    declared.add(ci.getSymbol());
                }
                return super.visitVariableDeclaratorStatement(variable, p);
            }

            @Override
            public Object visitIdentifier(final IdentifierTree identifier, final Object p) {
                if (identifier instanceof CIdentifierTree ci && ci.getSymbol() != null) {
                    final var kind = ci.getSymbol().getKind();
                    if (kind == ElementKind.LOCAL_VARIABLE || kind == ElementKind.PARAMETER
                            || kind == ElementKind.EXCEPTION_PARAMETER
                            || kind == ElementKind.RESOURCE_VARIABLE
                            || kind == ElementKind.BINDING_VARIABLE) {
                        used.add(ci.getSymbol());
                    }
                }
                return super.visitIdentifier(identifier, p);
            }
        }.acceptTree(declaration, null);
        final List<Element> captures = new ArrayList<>();
        for (final Element e : used) {
            if (!declared.contains(e)) {
                captures.add(e);
            }
        }
        return captures;
    }

    /**
     * {@code new X(args) { body }}: registreert de anonieme klasse (eenmalig;
     * een veld-initializer wordt per constructor opnieuw geëmitteerd) en
     * construeert een instantie via haar constructor
     * {@code (outer?, gevangen..., args...)}.
     */
    private IRValue emitAnonymousInstance(final NewClassExpression newClass, final TypeElement anonymousType) {
        final List<IRValue> superArgs = new ArrayList<>();
        final List<IRType> superArgTypes = new ArrayList<>();
        final List<TypeMirror> superArgMirrors = new ArrayList<>();
        for (final ExpressionTree arg : newClass.getArguments()) {
            final IRValue value = acceptTree(arg, builder);
            if (value != null) {
                superArgs.add(value);
                superArgTypes.add(value.type());
                superArgMirrors.add(arg.getType());
            }
        }

        final String qualifiedName = qualifiedNameOf(anonymousType);
        AnonymousClassInfo info = anonymousClasses.get(qualifiedName);
        if (info == null) {
            final boolean hasOuter = scope.lookup("this").isPresent();
            final List<Element> captures = computeCaptures(newClass.getClassDeclaration());
            final TypeMirror superMirror = anonymousType.getSuperclass();
            String superInitName = "java.lang.Object_super";
            List<IRType> superParamTypes = List.of();
            if (superMirror instanceof io.github.potjerodekool.nabu.type.DeclaredType superDeclared
                    && superDeclared.asTypeElement() != null
                    && !isJavaLangObject(superMirror)) {
                final var declared = resolveDeclaredCtorParamTypes(superMirror, superArgs.size(),
                        superArgTypes, superArgMirrors);
                superParamTypes = declared != null ? declared : List.copyOf(superArgTypes);
                superInitName = qualifiedNameOf(superDeclared.asTypeElement()) + "_super";
            }
            info = new AnonymousClassInfo(newClass.getClassDeclaration(), hasOuter, captures,
                    superInitName, superParamTypes);
            anonymousClasses.put(qualifiedName, info);
            pendingAnonymousClasses.add(info);
        }

        final IRType objectType = TypeMirrorToIRType.map(anonymousType.asType());
        final IRValue obj = builder.emitHeapAlloc("new_obj", objectType);
        final List<IRValue> args = new ArrayList<>();
        final List<IRType> paramTypes = new ArrayList<>();
        if (info.hasOuter()) {
            final IRValue thisVal = scope.lookup("this").orElse(null);
            final var outerElement = outerOf(anonymousType);
            final IRType outerType = new IRType.Ptr(IRType.I8,
                    TypeMirrorToIRType.toJvmDescriptor(outerElement.asType()));
            args.add(thisVal != null ? thisVal : new IRValue.ConstNull(outerType));
            paramTypes.add(outerType);
        }
        for (final Element captured : info.captures()) {
            args.add(readVariable(captured));
            paramTypes.add(TypeMirrorToIRType.map(captured.asType()));
        }
        args.addAll(superArgs);
        paramTypes.addAll(info.superParamTypes());

        final String initName = anonymousType.asType().getClassName().replace('.', '_') + "_init";
        builder.emitCall(CallKind.SPECIAL, initName, IRType.VOID, paramTypes, args);
        return obj;
    }

    /** De huidige waarde van een lokale variabele/parameter (of haar gevangen kopie). */
    private IRValue readVariable(final Element symbol) {
        final var local = scope.lookup(symbol.getSimpleName());
        if (local.isPresent()) {
            final IRValue ptr = local.get();
            if (ptr.type() instanceof IRType.Ptr ptrType && ptrType.jvmDescriptor() == null) {
                return builder.emitLoad(ptr);
            }
            return ptr;
        }
        final IRValue captured = loadCapturedVariable(symbol);
        return captured != null ? captured : new IRValue.ConstNull(TypeMirrorToIRType.map(symbol.asType()));
    }

    /** De waarde van een gevangen variabele binnen de body van de huidige anonieme klasse, of null. */
    private IRValue loadCapturedVariable(final Element symbol) {
        if (currentAnonymous == null || !currentAnonymous.captures().contains(symbol)) {
            return null;
        }
        final IRValue thisVal = scope.lookup("this").orElse(null);
        if (thisVal == null) {
            return null;
        }
        return builder.emitLoad(new IRValue.Values(thisVal, new IRValue.Named(
                capturedFieldName(symbol),
                TypeMirrorToIRType.map(symbol.asType()),
                TypeMirrorToIRType.map(currentTypeElement.asType()),
                false)));
    }

    /**
     * Bridge-methoden (JLS §15.12.4.5), zoals javac ze genereert: een methode
     * die een generieke methode van een supertype overschrijft, krijgt daarnaast
     * een synthetische methode met de ge-erasede signatuur van dat supertype,
     * die de argumenten cast en de echte methode virtueel aanroept.
     *
     * <p>Zonder bridge implementeert niets de ge-erasede interface-methode:
     * picocli's {@code ShortestFirst implements Comparator<String>} heeft alleen
     * {@code compare(String, String)}, terwijl {@code TimSort} (JDK-bytecode)
     * {@code compare(Object, Object)} aanroept — native viel de itable dan op de
     * abstracte stub terug, op de JVM zou het een AbstractMethodError zijn.
     *
     * <p>Alleen het generieke geval: een parameter van de supermethode is een
     * type-variabele (of bevat er een) en de ge-erasede parametertypes
     * verschillen van die van de eigen methode.
     */
    private void emitBridgeMethods() {
        if (currentTypeElement == null || currentTypeElement.getKind().isInterface()) {
            return;
        }
        final var ownMethods = io.github.potjerodekool.nabu.lang.model.element.ElementFilter
                .methodsIn(currentTypeElement.getEnclosedElements()).stream()
                .filter(m -> !m.isStatic() && !Flags.hasFlag(m.getFlags(), Flags.PRIVATE)
                        && !Flags.hasFlag(m.getFlags(), Flags.ABSTRACT))
                .toList();
        if (ownMethods.isEmpty()) {
            return;
        }
        // Sleutel: naam + ge-erasede parameters + returntype (JVMS §4.3.3).
        final Set<String> declared = new HashSet<>();
        for (final var m : ownMethods) {
            declared.add(erasedKey(m.getSimpleName(), m.getParameters().stream()
                    .map(p -> TypeMirrorToIRType.map(p.asType())).toList())
                    + returnDescriptorOf(m));
        }

        for (final TypeElement superType : allSupertypes(currentTypeElement)) {
            for (final var superMethod : io.github.potjerodekool.nabu.lang.model.element.ElementFilter
                    .methodsIn(superType.getEnclosedElements())) {
                if (superMethod.isStatic() || Flags.hasFlag(superMethod.getFlags(), Flags.PRIVATE)) {
                    continue;
                }
                final var superParams = superMethod.getParameters();
                final List<IRType> bridgeParamTypes = superParams.stream()
                        .map(p -> TypeMirrorToIRType.map(p.asType())).toList();
                final String key = erasedKey(superMethod.getSimpleName(), bridgeParamTypes)
                        + returnDescriptorOf(superMethod);
                if (declared.contains(key)) {
                    continue;
                }
                for (final var own : ownMethods) {
                    if (!own.getSimpleName().equals(superMethod.getSimpleName())
                            || own.getParameters().size() != superParams.size()) {
                        continue;
                    }
                    // Generieke parameters (JLS §15.12.4.5), of dezelfde
                    // parameters met een ander (covariant/gesubstitueerd)
                    // returntype: `String convert(String)` voor
                    // `T convert(String)` heeft een `Object convert(String)`
                    // nodig, anders AbstractMethodError via de interface.
                    final boolean paramBridge = superParams.stream().anyMatch(p -> mentionsTypeVariable(p.asType()))
                            && overridesErased(own, superMethod);
                    final boolean returnBridge = !paramBridge
                            && sameErasedParameters(own, superMethod)
                            && isReturnBridgeCandidate(own, superMethod);
                    if (!paramBridge && !returnBridge) {
                        continue;
                    }
                    emitBridge(own, superMethod, bridgeParamTypes);
                    declared.add(key);
                    break;
                }
            }
        }
    }

    private static String returnDescriptorOf(final ExecutableElement method) {
        return descOf(TypeMirrorToIRType.mapReturnType(method.getReturnType()));
    }

    private static boolean sameErasedParameters(final ExecutableElement own, final ExecutableElement superMethod) {
        for (int i = 0; i < own.getParameters().size(); i++) {
            if (!descOf(TypeMirrorToIRType.map(own.getParameters().get(i).asType()))
                    .equals(descOf(TypeMirrorToIRType.map(superMethod.getParameters().get(i).asType())))) {
                return false;
            }
        }
        return true;
    }

    /** Beide returntypes zijn referenties met een verschillende erasure. */
    private static boolean isReturnBridgeCandidate(final ExecutableElement own, final ExecutableElement superMethod) {
        final IRType ownReturn = TypeMirrorToIRType.mapReturnType(own.getReturnType());
        final IRType superReturn = TypeMirrorToIRType.mapReturnType(superMethod.getReturnType());
        return ownReturn instanceof IRType.Ptr && superReturn instanceof IRType.Ptr
                && !descOf(ownReturn).equals(descOf(superReturn));
    }

    /**
     * Of {@code own} de generieke {@code superMethod} kan overschrijven:
     * per parameter is de supermethode een type-variabele (dan volstaat een
     * referentietype) of hebben beide hetzelfde ge-erasede type.
     */
    private static boolean overridesErased(final ExecutableElement own, final ExecutableElement superMethod) {
        boolean differs = false;
        for (int i = 0; i < own.getParameters().size(); i++) {
            final var ownType = own.getParameters().get(i).asType();
            final var superType = superMethod.getParameters().get(i).asType();
            final var ownIr = TypeMirrorToIRType.map(ownType);
            final var superIr = TypeMirrorToIRType.map(superType);
            if (mentionsTypeVariable(superType)) {
                if (ownType.isPrimitiveType()) return false;
                if (!descOf(ownIr).equals(descOf(superIr))) differs = true;
            } else if (!descOf(ownIr).equals(descOf(superIr))) {
                return false;
            }
        }
        return differs;
    }

    private static boolean mentionsTypeVariable(final TypeMirror type) {
        if (type instanceof io.github.potjerodekool.nabu.type.TypeVariable) return true;
        if (type instanceof io.github.potjerodekool.nabu.type.ArrayType arrayType) {
            return mentionsTypeVariable(arrayType.getComponentType());
        }
        return false;
    }

    private static String erasedKey(final String name, final List<IRType> paramTypes) {
        final var sb = new StringBuilder(name).append('(');
        for (final var t : paramTypes) sb.append(descOf(t));
        return sb.append(')').toString();
    }

    /** Alle super-klassen en (super-)interfaces van {@code type}, zonder {@code type} zelf. */
    private List<TypeElement> allSupertypes(final TypeElement type) {
        final List<TypeElement> result = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final Deque<TypeElement> pending = new ArrayDeque<>();
        pending.add(type);
        seen.add(qualifiedNameOf(type));
        while (!pending.isEmpty()) {
            final TypeElement current = pending.poll();
            final List<TypeMirror> supers = new ArrayList<>(current.getInterfaces());
            if (current.getSuperclass() != null) supers.add(current.getSuperclass());
            for (final TypeMirror s : supers) {
                if (s instanceof io.github.potjerodekool.nabu.type.DeclaredType declaredType
                        && declaredType.asTypeElement() != null
                        && seen.add(qualifiedNameOf(declaredType.asTypeElement()))) {
                    result.add(declaredType.asTypeElement());
                    pending.add(declaredType.asTypeElement());
                }
            }
        }
        return result;
    }

    private void emitBridge(final ExecutableElement target,
                            final ExecutableElement superMethod,
                            final List<IRType> bridgeParamTypes) {
        final String fnName = currentClassName + "_" + target.getSimpleName();
        final var thisDescriptor = TypeMirrorToIRType.toJvmDescriptor(currentTypeElement.asType());
        final IRValue thisParam = new IRValue.Temp("%this", new IRType.Ptr(IRType.I8, thisDescriptor));
        final List<IRValue> params = new ArrayList<>();
        params.add(thisParam);
        for (int i = 0; i < bridgeParamTypes.size(); i++) {
            params.add(new IRValue.Temp("%bridge" + i, bridgeParamTypes.get(i)));
        }
        final IRType bridgeReturn = TypeMirrorToIRType.mapReturnType(superMethod.getReturnType());
        final IRType targetReturn = TypeMirrorToIRType.mapReturnType(target.getReturnType());
        final List<IRType> targetParamTypes = target.getParameters().stream()
                .map(p -> TypeMirrorToIRType.map(p.asType())).toList();

        scope.reset();
        builder.beginFunction(fnName, bridgeReturn, params,
                Flags.PUBLIC | Flags.SYNTHETIC | Flags.BRIDGE, false);
        final List<IRValue> args = new ArrayList<>();
        args.add(thisParam);
        for (int i = 0; i < targetParamTypes.size(); i++) {
            final IRValue param = params.get(i + 1);
            final IRType wanted = targetParamTypes.get(i);
            args.add(descOf(param.type()).equals(descOf(wanted))
                    ? param : builder.emitCast(param, wanted));
        }
        // Call-sites noemen de eigenaar gekwalificeerd (zoals visitMethodInvocation);
        // de definitie gebruikt de korte naam.
        final String callName = qualifiedNameOf(currentTypeElement) + "_" + target.getSimpleName();
        final IRValue result = builder.emitCall(CallKind.VIRTUAL, callName, targetReturn, targetParamTypes, args);
        builder.emitReturn(bridgeReturn == IRType.VOID ? null : result);
        builder.endFunction();
    }

    // -------------------------------------------------------
    // Methode / constructor declaratie
    // -------------------------------------------------------

    @Override
    public IRValue visitFunction(Function function, IRBuilder param) {
        final var methodSymbol = function.getMethodSymbol();
        if (methodSymbol == null) return null;

        // Locatie registreren
        builder.setLocation(
                currentClassName + ".nabu",
                function.getLineNumber(),
                function.getColumnNumber()
        );

        // Returntype
        IRType returnType = TypeMirrorToIRType.mapReturnType(
                methodSymbol.getReturnType());

        // Parameters
        List<IRValue> params = new ArrayList<>();
        for (VariableElement param2 : methodSymbol.getParameters()) {
            IRType paramType = TypeMirrorToIRType.map(param2.asType());
            params.add(new IRValue.Temp(
                    "%" + param2.getSimpleName(), paramType));
        }

        // Voor instantie-methoden: 'this' als eerste parameter
        boolean isStatic = methodSymbol.isStatic();
        if (!isStatic) {
            final var thisTypeMirror = methodSymbol.getEnclosingElement().asType();
            final var thisDescriptor = TypeMirrorToIRType.toJvmDescriptor(thisTypeMirror);
            params.addFirst(new IRValue.Temp("%this", new IRType.Ptr(IRType.I8, thisDescriptor)));
        }

        // JLS ├âÔÇÜ├é┬º8.8.1: een niet-statische member class neemt de enclosing
        // instance als eerste expliciete parameter op in iedere constructor:
        // Text(int) -> Text(Ansi, int) (vt./javac-compatibel `this$0`).
        final IRValue outerCtorParam;
        if (isOuterInstanceCtor(methodSymbol)) {
            final var outerElement = outerOf(
                    (TypeElement) methodSymbol.getEnclosingElement());
            final var outerParam = new IRValue.Temp(
                    "%this$0",
                    new IRType.Ptr(IRType.I8,
                            TypeMirrorToIRType.toJvmDescriptor(outerElement.asType())));
            params.add(1, outerParam);
            outerCtorParam = outerParam;
        } else {
            outerCtorParam = null;
        }

        // Functienaam: klasse + methode (JVM-stijl intern)
        String fnName = currentClassName + "_" + function.getSimpleName();
        if (methodSymbol.getKind() == ElementKind.CONSTRUCTOR) {
            fnName = currentClassName + "_init";
        }

        final var flags = methodSymbol.getFlags();

        // Begin functie
        scope.reset();
        final var irFunction = builder.beginFunction(fnName, returnType, params, flags, methodSymbol.getKind() ==  ElementKind.CONSTRUCTOR);

        // Annotations overnemen van symbol naar IRFunction
        final var annotationMirrors = methodSymbol.getAnnotationMirrors();
        irFunction.setAnnotations(annotationMirrors.stream()
                .filter(a -> a instanceof CompoundAttribute)
                .map(a -> (CompoundAttribute) a)
                .toList());

        // Parameter-annotations overnemen
        final var paramAnnotations = new ArrayList<List<CompoundAttribute>>();
        if (!isStatic) {
            paramAnnotations.add(List.of()); // 'this' heeft geen annotations
        }
        for (VariableElement p : methodSymbol.getParameters()) {
            final var pa = p.getAnnotationMirrors().stream()
                    .filter(a -> a instanceof CompoundAttribute)
                    .map(a -> (CompoundAttribute) a)
                    .toList();
            paramAnnotations.add(pa);
        }
        irFunction.setParameterAnnotations(paramAnnotations);

        // Method-level generic signature
        final var typeParamElements = methodSymbol.getTypeParameters();
        final var paramTypeMirrors = methodSymbol.getParameters().stream()
                .map(VariableElement::asType)
                .toList();
        final var methodSig = JvmSignatureBuilder.buildMethodSignature(
                typeParamElements.toArray(new TypeParameterElement[0]),
                methodSymbol.getReturnType(),
                paramTypeMirrors
        );
        if (methodSig != null) {
            irFunction.setGenericSignature(methodSig);
        }

        // Parameters registreren in scope
        scope.pushScope();
        for (IRValue p : params) {
            final var name = IRValue.nameOf(p);
            String pname = name.startsWith("%")
                    ? name.substring(1) : name;
            // 'this' en de synthetic 'this$N' blijven kale waarden: veel code
            // haalt ze rechtstreeks op (emitFieldLoad, call-args) en een slot
            // zou daar een pointer i.p.v. de receiver opleveren. Declarations
            // w├®l: die zijn toewijsbaar en hebben dus een slot nodig.
            if ("this".equals(pname) || pname.startsWith("this$")) {
                scope.define(pname, p);
            } else {
                scope.define(pname, builder.emitParameterSlot(pname, p));
            }
        }

        // Body. Constructors: field-initializers na de super-invoke,
        // v├âãÆ├åÔÇÖ├âÔÇÜ├é┬│├âãÆ├åÔÇÖ├âÔÇÜ├é┬│r de rest van de body (JLS ├âãÆ├óÔé¼┼í├âÔÇÜ├é┬º8.3 / ├âãÆ├óÔé¼┼í├âÔÇÜ├é┬º8.8.7). <clinit> krijgt
        // de static initializers.
        final boolean isCtorFunction = methodSymbol.getKind() == ElementKind.CONSTRUCTOR;
        final boolean isClinitFunction = fnName.contains("<clinit>");
        final boolean emitFieldInits = isCtorFunction || isClinitFunction;

        if (emitFieldInits && function.getBody() instanceof BlockStatementTree bodyBlock) {
            final var statements = bodyBlock.getStatements();
            int statementIndex = 0;
            // Eerste statement: expliciete of implicite super()/this()-aanroep
            if (!statements.isEmpty() && isConstructorInvocation(statements.getFirst())) {
                acceptTree(statements.getFirst(), builder);
                statementIndex = 1;
            }

            // JLS ├âÔÇÜ├é┬º8.8.7: sla de enclosing instance op in het synthetic
            // `this$0`-veld (na de super/this-invocation, v├âãÆ├é┬│├âãÆ├é┬│r de rest van
            // de body). Een this()-delegatie geeft dezelfde outer waarde
            // mee, dus de store is idempotent.
            if (outerCtorParam != null) {
                final var thisTemp = builder.currentFunction() != null
                        && !builder.currentFunction().params.isEmpty()
                        ? builder.currentFunction().params.getFirst() : null;
                if (thisTemp != null) {
                    final var fieldNamed = syntheticOuterFieldNamed();
                    builder.emitStore(new IRValue.Values(thisTemp, fieldNamed), outerCtorParam);
                }
            }

            emitPendingFieldInitializers(isCtorFunction);

            for (var i = statementIndex; i < statements.size(); i++) {
                acceptTree(statements.get(i), builder);
            }
        } else if (function.getBody() != null) {
            acceptTree(function.getBody(), builder);
        }

        // Impliciete void-return als het blok niet be├½indigd is
        if (!(methodSymbol.isAbstract()
                 || methodSymbol.hasFlag(Flags.NATIVE))
                        && !builder.currentBlockTerminated()) {
            if (returnType == IRType.VOID) {
                builder.emitReturn(null);
            } else {
                // Niet-void-methode waarvan het laatste blok leeg eindigt (bv.
                // het try.end-blok van een try die altijd returnt of gooit).
                // Java vereist dan geen return; het pad is onbereikbaar, maar
                // LLVM en de bytecode-verifier eisen w├®l een terminator.
                builder.emitUnreachable();
            }
        }

        scope.popScope();
        builder.endFunction();
        return null;
    }

    /**
     * Detecteert een expliciete of impliciete super()/this()-constructor-
     * aanroep als eerste statement (zelfde detectie als TypeEnter, die
     * de impliciete super-invoke als eerste statement toevoegt).
     */
    private boolean isConstructorInvocation(final StatementTree statement) {
        if (!(statement instanceof ExpressionStatementTree expressionStatement)) {
            return false;
        }
        if (!(expressionStatement.getExpression() instanceof MethodInvocationTree methodInvocationTree)) {
            return false;
        }
        final var selector = methodInvocationTree.getMethodSelector();
        if (selector instanceof IdentifierTree identifierTree) {
            return "super".equals(identifierTree.getName())
                    || "this".equals(identifierTree.getName());
        }
        if (selector instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            // TypeEnter's impliciete super-aanroep is `this.super()`.
            final var fieldName = fieldAccessExpressionTree.getField().getName();
            return "this".equals(fieldName) || "super".equals(fieldName);
        }
        return false;
    }

    /**
     * Emitteert de veld-initializers van de verwerkte klasse: in een
     * constructor de instantie-velden, in <clinit> de statische velden.
     * Ge├âãÆ├åÔÇÖ├âÔÇÜ├é┬½mitteerde initializers worden verwijderd zodat een andere
     * constructor (via this()-delegatie) ze niet herhaalt.
     */
    private void emitPendingFieldInitializers(final boolean forInstanceFields) {
        emitPendingFieldInitializers(forInstanceFields, false);
    }

    /**
     * @param allInstanceInitializers ook niet-literal instantie-initializers.
     *        Normaal plaatst TypeEnter die al als {@code this.f = ...} na de
     *        super-aanroep in elke constructor; alleen de synthetische
     *        constructor van een anonieme klasse heeft ze hier nodig.
     */
    private void emitPendingFieldInitializers(final boolean forInstanceFields,
                                              final boolean allInstanceInitializers) {
        if (pendingFieldInitializers.isEmpty()) {
            return;
        }

        final var thisTemp = builder.currentFunction() != null && !builder.currentFunction().params.isEmpty()
                ? builder.currentFunction().params.getFirst()
                : null;

        final var emittedKeys = new ArrayList<Element>();
        for (final var entry : pendingFieldInitializers.entrySet()) {
            final var fieldSymbol = entry.getKey();
            final boolean fieldIsStatic = fieldSymbol.isStatic();
            // static-initializers -> <clinit>, instance-initializers -> <init>
            if (fieldIsStatic == forInstanceFields) {
                continue;
            }
            if (!fieldIsStatic && !allInstanceInitializers
                    && !(entry.getValue() instanceof LiteralExpressionTree)) {
                continue;
            }
            // Alleen velden van de verwerkte klasse (op element; de korte naam
            // van een anonieme klasse is niet haar currentClassName)
            if (fieldSymbol.getEnclosingElement() instanceof TypeElement owner && currentTypeElement != null) {
                if (!qualifiedNameOf(owner).equals(qualifiedNameOf(currentTypeElement))) {
                    continue;
                }
            } else if (fieldSymbol.getEnclosingElement() != null
                    && fieldSymbol.getEnclosingElement().getSimpleName() != null
                    && !fieldSymbol.getEnclosingElement().getSimpleName().contentEquals(currentClassName)) {
                continue;
            }

            builder.setLocation(
                    currentClassName + ".nabu",
                    0,
                    0
            );
            IRValue value = acceptTree(entry.getValue(), builder);
            if (value == null) {
                continue;
            }

            // Een literal in een wrapper-veld (`Character c = '#'`) boxen;
            // anders stond de kale char in het veld (picocli's
            // ParserSpec.atFileCommentChar crashte in String.format).
            if (fieldIsStatic) {
                final var staticField = resolveField(fieldSymbol);
                builder.emitStore(staticField, boxIfNeeded(value, staticField.type()));
            } else if (thisTemp != null) {
                final var named = resolveField(fieldSymbol);
                final var ownerType = TypeMirrorToIRType.map(fieldSymbol.getEnclosingElement().asType());
                builder.emitStore(new IRValue.Values(
                        thisTemp,
                        new IRValue.Named(
                                named.name(),
                                named.type(),
                                ownerType,
                                false
                        )
                ), boxIfNeeded(value, named.type()));
            }            emittedKeys.add(fieldSymbol);
        }

        for (final var key : emittedKeys) {
            pendingFieldInitializers.remove(key);
        }
    }

    // -------------------------------------------------------
    // Statements
    // -------------------------------------------------------

    @Override
    public IRValue visitBlockStatement(BlockStatementTree blockStatement,
                                       IRBuilder param) {
        scope.pushScope();
        for (StatementTree stmt : blockStatement.getStatements()) {
            acceptTree(stmt, builder);
        }
        scope.popScope();
        return null;
    }

    @Override
    public IRValue visitReturnStatement(ReturnStatementTree returnStatement,
                                        IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                returnStatement.getLineNumber(),
                returnStatement.getColumnNumber()
        );

        ExpressionTree expr = returnStatement.getExpression();
        if (expr == null) {
            if (builder.currentBlockTerminated()) {
                // Fallback: een laat-statement (bv. lege-body lus) heeft het
                // blok al be├âãÆ├åÔÇÖ├âÔÇÜ├é┬½indigd; de return aan├âãÆ├é┬º├âÔÇÜ├é┬╗├âÔÇ╣├óÔé¼┬á├âãÆ├é┬º├â┬ó├óÔÇÜ┬¼├à┬í├âÔÇÜ├é┬╣ in een volgblok.
                final var downstream = builder.beginBlock("after.terminated");
                builder.setCurrentBlock(downstream);
            }
            builder.emitReturn(null);
        } else {
            IRValue value = acceptTree(expr, builder);
            if (builder.currentBlockTerminated()) {
                // De waarde-evaluatie rond de return be├âãÆ├é┬ª├âÔÇÜ├é┬»├â┬ó├óÔÇÜ┬¼├é┬║indigde het blok
                // (bv. een gedede -conditie); verplaats de return naar een
                // nieuw blok zodat de return zelf niet crasht.
                final var downstream = builder.beginBlock("after.return");
                builder.setCurrentBlock(downstream);
            }
            value = boxIfNeeded(value, builder.currentFunction().returnType);
            // Auto-unboxing bij return aan een primitieve functie
            // (bv. `int execute(...)` met `return helpExitCode;`)
            if (builder.currentFunction() != null) {
                value = unboxIfNeeded(value, builder.currentFunction().returnType);
            }
            builder.emitReturn(value);
        }
        return null;
    }

    /**
     * Bokst een primitieve waarde in wanneer de functie een wrapper
     * (Integer/Long/Double/Float/Boolean/Character/Short/Byte) teruggeeft.
     * Het box-type wordt afgeleid uit de wrapper-descriptor van het
     * doeltype: in het IR zijn byte/short/char allemaal I32, dus alleen de
     * target-descriptor (bv. CLI Character i.p.v. Integer) bepaalt welke
     * valueOf-methode moet worden aangeroepen.
     */
    private IRValue boxIfNeeded(final IRValue value, final IRType returnType) {
        if (value == null
                || !(returnType instanceof IRType.Ptr ptr)
                || ptr.jvmDescriptor() == null) {
            return value;
        }

        final var desc = ptr.jvmDescriptor();
        final String boxClass;
        final List<IRType> expectedPrimitives;
        switch (desc) {
            case "Ljava/lang/Byte;" -> {
                boxClass = "java.lang.Byte";
                expectedPrimitives = List.of(IRType.I8, IRType.I16, IRType.I32);
            }
            case "Ljava/lang/Short;" -> {
                boxClass = "java.lang.Short";
                expectedPrimitives = List.of(IRType.I16, IRType.I32);
            }
            case "Ljava/lang/Integer;" -> {
                boxClass = "java.lang.Integer";
                expectedPrimitives = List.of(IRType.I32, IRType.I16);
            }
            case "Ljava/lang/Character;" -> {
                boxClass = "java.lang.Character";
                // char is I16 in het IR; accepteer ook I32 (bv. na promotie)
                expectedPrimitives = List.of(IRType.I16, IRType.I32);
            }
            case "Ljava/lang/Long;" -> {
                boxClass = "java.lang.Long";
                expectedPrimitives = List.of(IRType.I64, IRType.I32);
            }
            case "Ljava/lang/Float;" -> {
                boxClass = "java.lang.Float";
                expectedPrimitives = List.of(IRType.F32, IRType.I32, IRType.I16, IRType.I8);
            }
            case "Ljava/lang/Double;" -> {
                boxClass = "java.lang.Double";
                expectedPrimitives = List.of(IRType.F64, IRType.F32, IRType.I64, IRType.I32);
            }
            case "Ljava/lang/Boolean;" -> {
                boxClass = "java.lang.Boolean";
                expectedPrimitives = List.of(IRType.BOOL, IRType.I32, IRType.I16, IRType.I8);
            }
            default -> {
                return value;
            }
        }

        if (!expectedPrimitives.contains(value.type())) {
            return value;
        }

        return builder.emitCall(
                CallKind.STATIC,
                boxClass.replace('.', '_') + "_valueOf",
                returnType,
                List.of(value.type()),
                List.of(value)
        );
    }

    /**
     * Past het argument aan op het gedeclareerde parametertype van de
     * aangeroepen methode: passeert het onveranderd bij een primitieve
     * parameter, en bokst een primitief argument (JLS 5.1.7) wanneer de
     * parameter een referentietype is ("java.lang.Object" bijv. via
     * Map.put(Object,Object)).
     */
    private IRValue boxArgIfNeeded(final IRValue value,
                                   final TypeMirror argTypeMirror,
                                   final TypeMirror paramType) {
        if (value == null
                || paramType == null
                || paramType.getKind() == TypeKind.VOID
                || paramType.getKind() == TypeKind.NULL) {
            return value;
        }

        // JLS 5.1.8 (unboxing conversion): verwacht de parameter een
        // PRIMITIEF type terwijl de waarde een wrapper is (bv.
        // `tok.commentChar(Character)` -> `commentChar(int)`), dan moet de
        // wrapper worden geunboxt; anders blijft de Ptr op de stack staan en
        // mist de verifier het int-argument ("Cannot pop operand off an
        // empty stack" bij StreamTokenizer.commentChar).
        if (paramType.getKind().isPrimitive()) {
            final IRType mapped = TypeMirrorToIRType.map(paramType);
            IRValue widened = unboxIfNeeded(value, mapped);
            // JLS 5.3 (method invocation conversion): primitieve verbreding.
            // `Thread.sleep(25)` levert een int-constante maar de parameter
            // is long; zonder I2L breekt de verifier ("expected J, but
            // found I"). Idem float->double.
            if (isWideningNeeded(widened.type(), mapped)) {
                return builder.emitCast(widened, mapped);
            }
            return widened;
        }

        // Alleen boksen wanneer het argument momenteel een primitieve
        // waarde is en de parameter een referentie verwacht.
        if (value.type() instanceof IRType.Int(int bits)) {
            final String boxClass = argTypeMirror != null
                    && argTypeMirror.getKind() == TypeKind.CHAR
                    ? "java.lang.Character"
                    : boxClassForBits(bits);
            final var boxDesc = "L" + boxClass.replace('.', '/') + ";";
            return builder.emitCall(
                    CallKind.STATIC,
                    boxClass.replace('.', '_') + "_valueOf",
                    new IRType.Ptr(IRType.I8, boxDesc),
                    List.of(value.type()),
                    List.of(value)
            );
        }

        if (value.type() instanceof IRType.Float(int bits)) {
            final String boxClass = bits == 32
                    ? "java.lang.Float"
                    : "java.lang.Double";
            final var boxDesc = "L" + boxClass.replace('.', '/') + ";";
            return builder.emitCall(
                    CallKind.STATIC,
                    boxClass.replace('.', '_') + "_valueOf",
                    new IRType.Ptr(IRType.I8, boxDesc),
                    List.of(value.type()),
                    List.of(value)
            );
        }

        if (value.type() instanceof IRType.Bool) {
            final var boxDesc = "Ljava/lang/Boolean;";
            return builder.emitCall(
                    CallKind.STATIC,
                    "java.lang.Boolean_valueOf",
                    new IRType.Ptr(IRType.I8, boxDesc),
                    List.of(IRType.BOOL),
                    List.of(value)
            );
        }

        return value;
    }

    /**
     * Boxing op basis van het IR-type: de erasure-fallback wanneer de
     * gesubstitueerde parameter onterecht primitief rapporteert maar de
     * ge├âãÆ├é┬®mitteerde descriptor een referentie (Object) is.
     */
    private IRValue boxFromIRType(final IRValue value) {
        final String boxClass;
        final String boxMethodName;
        final IRType argType;
        if (value.type() instanceof IRType.Int(int bits)) {
            boxClass = boxClassForBits(bits);
            boxMethodName = boxClass.replace('.', '_') + "_valueOf";
            argType = value.type();
        } else if (value.type() instanceof IRType.Float(int bits)) {
            boxClass = bits == 64 ? "java.lang.Double" : "java.lang.Float";
            boxMethodName = boxClass.replace('.', '_') + "_valueOf";
            argType = value.type();
        } else if (value.type() instanceof IRType.Bool) {
            boxClass = "java.lang.Boolean";
            boxMethodName = "java.lang.Boolean_valueOf";
            argType = IRType.BOOL;
        } else {
            return value;
        }
        return builder.emitCall(
                CallKind.STATIC,
                boxMethodName,
                new IRType.Ptr(IRType.I8, "L" + boxClass.replace('.', '/') + ";"),
                List.of(argType),
                List.of(value)
        );
    }

    /**
     * Haalt het componenttype uit een array-parameter, of null als de parameter
     * geen array is. Het componenttype is het type waartegen elk varargs-
     * element losgelaten wordt (JLS 15.12.4.4).
     */
    private static TypeMirror componentTypeMirror(final TypeMirror paramType) {
        return paramType instanceof ArrayType arrayType
                ? arrayType.getComponentType()
                : null;
    }

/**
     * Of het enige trailing argument van een varargs-aanroep zelf de array is
     * (JLS §15.12.4.2): zijn type is toewijsbaar aan de array-parameter.
     * {@code String.join(sep, cmd)} met {@code String[] cmd} geeft de array
     * door; {@code f(Object...)} met een {@code int[]} pakt hem wél in.
     */
    private static boolean isArrayAssignableToVarArgs(final ExpressionTree arg,
                                                      final IRValue argVal,
                                                      final IRType arrayParam) {
        String argDescriptor = argVal.type() instanceof IRType.Ptr argPtr ? argPtr.jvmDescriptor() : null;
        if ((argDescriptor == null || !argDescriptor.startsWith("["))
                && arg.getType() != null && arg.getType().getKind() == TypeKind.ARRAY) {
            argDescriptor = TypeMirrorToIRType.toJvmDescriptor(arg.getType());
        }
        if (argDescriptor == null || !argDescriptor.startsWith("[")) {
            return false;
        }
        final String paramDescriptor = arrayParam instanceof IRType.Ptr paramPtr ? paramPtr.jvmDescriptor() : null;
        if (paramDescriptor == null || !paramDescriptor.startsWith("[")) {
            return true;
        }
        final int argDims = argDescriptor.lastIndexOf('[') + 1;
        final int paramDims = paramDescriptor.lastIndexOf('[') + 1;
        final String argElement = argDescriptor.substring(argDims);
        final String paramElement = paramDescriptor.substring(paramDims);
        if (argDims == paramDims) {
            // Gelijke dimensie: primitief moet exact gelijk zijn, referentie
            // past (de verifier/runtime checkt de echte subtypering).
            return argElement.equals(paramElement)
                    || (argElement.startsWith("L") && paramElement.startsWith("L"));
        }
        // Een diepere array is een Object[]-element van een ondiepere Object-array.
        return argDims > paramDims && "Ljava/lang/Object;".equals(paramElement);
    }

/** Haalt het componenttype uit de array-parameter van een varargs-callee. */
    private static IRType varArgComponentType(final IRType arrayType) {
        return switch (arrayType) {
            case IRType.Ptr p -> p.pointee() instanceof IRType.Array arr
                    ? arr.elem() : p.pointee();
            case IRType.Array a -> a.elem();
            case IRType t -> t;
        };
    }

    private String boxClassForBits(final int bits) {
        return switch (bits) {
            case 8 -> "java.lang.Byte";
            case 16 -> "java.lang.Short";
            case 64 -> "java.lang.Long";
            default -> "java.lang.Integer";
        };
    }

    private boolean isWideningNeeded(final IRType valueType,
                                     final IRType paramType) {
        // JLS 5.3: primitieve verbreding. byteÔåÆshortÔåÆintÔåÆlongÔåÆfloatÔåÆdouble,
        // charÔåÆintÔåÆlongÔåÆfloatÔåÆdouble. De IR bewaart de rang alleen als
        // bitbreedte, dus elke smallere int-breedte verbredt; boolean (1 bit)
        // is de enige uitzondering en verbredt NIET impliciet.
        if (valueType instanceof IRType.Int valueInt
                && paramType instanceof IRType.Int paramInt) {
            if (valueInt.bits() <= 1) {
                return false;
            }
            return valueInt.bits() < paramInt.bits();
        }
        if (valueType instanceof IRType.Float valueFloat
                && paramType instanceof IRType.Float paramFloat) {
            return valueFloat.bits() == 32 && paramFloat.bits() == 64;
        }
        // int ÔåÆ float/double (int-constante 0 naar double-parameter).
        if (valueType instanceof IRType.Int
                && paramType instanceof IRType.Float) {
            return true;
        }
        return false;
    }

    private boolean isPrimitiveWidening(final IRType valueType,
                                        final IRType targetType) {
        if (!(valueType instanceof IRType.Int)
                && !(valueType instanceof IRType.Float)) {
            return false;
        }
        if (!(targetType instanceof IRType.Int)
                && !(targetType instanceof IRType.Float)) {
            return false;
        }
        return isWideningNeeded(valueType, targetType);
    }

    @Override
    public IRValue visitVariableDeclaratorStatement(
            VariableDeclaratorTree varDecl, IRBuilder param) {

        builder.setLocation(
                currentClassName + ".nabu",
                varDecl.getLineNumber(),
                varDecl.getColumnNumber()
        );

        // Type bepalen via TypeMirror op de type-expressie

        TypeMirror typeMirror = varDecl.getVariableType().getType();
        IRType irType = TypeMirrorToIRType.map(typeMirror);

        String name = varDecl.getName().getName();

        final var symbol = varDecl.getName().getSymbol();

        if (symbol.getKind() == ElementKind.LOCAL_VARIABLE) {
            // Alloca voor de variabele
            IRValue ptr = builder.emitAlloca(name, irType);
            scope.define(name, ptr);

            if (ptr instanceof IRValue.Temp allocaTemp) {
                builder.currentFunction().addLocalVariable(
                        new IRFunction.LocalVar(name, irType, allocaTemp)
                );
            }

            // Initialisator
            if (varDecl.getValue() != null) {
                IRValue initValue = acceptTree(varDecl.getValue(), builder);
                // Auto-unboxing bij toewijzing aan een primitieve lokale
                // (bv. `int exitCode = (Integer) obj;`)
                if (initValue != null) {
                    initValue = unboxIfNeeded(initValue, irType);
                }
                if (initValue != null
                        && isPrimitiveWidening(initValue.type(), irType)) {
                    // JLS 5.2 assignment-conversion: `double result = 0;`
                    // moet de int-constante naar double verbreden (anders
                    // wordt de phi-incoming een ISTORE op een double-slot en
                    // faalt de frame-merge bij de loop-back).
                    initValue = builder.emitCast(initValue, irType);
                }
                if (initValue != null) {
                    initValue = boxIfNeeded(initValue, irType);
                }
                if (initValue != null) {
                    builder.emitStore(ptr, initValue);
                }
            }
        } else if (symbol.getKind() == ElementKind.PARAMETER) {
            //Ignore
        } else {
            IRValue initValue = null;
            if (varDecl.getValue() != null) {
                if (builder.currentBlock() != null) {
                    initValue = acceptTree(varDecl.getValue(), builder);
                } else {
                    // Klasseniveau: registreer de initializer; de
                    // constructor(s) (<init>) en <clinit> emitteren hem.
                    if (varDecl.getValue() instanceof ExpressionTree initExpression) {
                        pendingFieldInitializers.put(symbol, initExpression);
                    }
                }
            }
            final var owner = symbol.getEnclosingElement().asType();
            final var ownerType = TypeMirrorToIRType.map(owner);

            final var globalName = symbol.getEnclosingElement().getSimpleName() + "_" + name;
            builder.declareGlobal(globalName, irType, initValue, ownerType, symbol.isStatic());

            final var fieldFlags = symbol.getFlags();
            final var irField = IRField.field(
                    fieldFlags,
                    name,
                    irType,
                    null
            );
            final var fieldAnnotations = symbol.getAnnotationMirrors().stream()
                    .filter(a -> a instanceof CompoundAttribute)
                    .map(a -> (CompoundAttribute) a)
                    .toList();
            irField.setAnnotations(fieldAnnotations);

            final var fieldSignature = JvmSignatureBuilder.buildFieldSignature(typeMirror);
            if (fieldSignature != null) {
                irField.setGenericSignature(fieldSignature);
            }

            module.emitField(irField);
        }

        return null;
    }

    @Override
    public IRValue visitExpressionStatement(
            ExpressionStatementTree expressionStatement, IRBuilder param) {
        // Voer de expressie uit, gooi het resultaat weg
        acceptTree(expressionStatement.getExpression(), builder);

        if (expressionStatement.getExpression() instanceof MethodInvocationTree methodInvocationTree) {
            if (methodInvocationTree.getMethodType().getReturnType().getKind() != TypeKind.VOID) {
                builder.emitPop();
            }
        }

        return null;
    }

    @Override
    public IRValue visitIfStatement(IfStatementTree ifStatement,
                                    IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                ifStatement.getLineNumber(),
                ifStatement.getColumnNumber()
        );

        // Sla het huidige blok op v├âãÆ├åÔÇÖ├âÔÇÜ├é┬│├âãÆ├åÔÇÖ├âÔÇÜ├é┬│r beginBlock de cursor verplaatst
        IRBasicBlock entryBlk = builder.currentBlock();

        IRBasicBlock thenBlk = builder.beginBlock("if.then");
        IRBasicBlock elseBlk = ifStatement.getElseStatement() != null
                ? builder.beginBlock("if.else") : null;
        IRBasicBlock mergeBlk = builder.beginBlock("if.merge");

        // Conditie in entry-blok
        builder.setCurrentBlock(entryBlk);
        final var condition = ifStatement.getExpression();
        final var instanceofPattern = asInstanceOfPattern(condition);
        final IRValue patternValue;

        if (instanceofPattern != null) {
            final var value = acceptTree(instanceofPattern.getExpression(), builder);
            patternValue = value;
            final IRValue cond = builder.emitInstanceOf(
                    value,
                    TypeMirrorToIRType.map(patternInstanceOfType(instanceofPattern))
            );
            builder.emitCondBranch(cond, thenBlk,
                    elseBlk != null ? elseBlk : mergeBlk);
        } else {
            patternValue = null;
            emitConditionBranch(condition, thenBlk, elseBlk != null ? elseBlk : mergeBlk);
        }

        // Pattern-binding in de then-tak (bv. `if (obj instanceof Type var)`):
        // definieer de variabele in scope zodat `var.veld` resolveert.
        final String patternName = instanceofPattern instanceof InstanceOfExpression instanceOf
                && instanceOf.getTypeExpression() instanceof TypePattern typePattern
                && typePattern.getVariableDeclarator() != null
                ? typePattern.getVariableDeclarator().getName().getName()
                : null;

        // Then
        builder.setCurrentBlock(thenBlk);
        scope.pushScope();
        if (patternName != null && patternValue != null) {
            scope.define(patternName, patternValue);
        }
        acceptTree(ifStatement.getThenStatement(), builder);
        scope.popScope();
        // Sluit het blok waar de then-tak eindigde: een geneste expressie of
        // statement (bv. een ternary of lus) verplaatst de cursor naar een
        // eigen blok en terminaliseert thenBlk al. Anders blijft dat laatste
        // blok zonder terminator achter.
        closeBlock(builder.currentBlock(), mergeBlk);

        // Else (optioneel)
        if (elseBlk != null) {
            builder.setCurrentBlock(elseBlk);
            acceptTree(ifStatement.getElseStatement(), builder);
            closeBlock(builder.currentBlock(), mergeBlk);
        }

        builder.setCurrentBlock(mergeBlk);
        return null;
    }

    /**
     * Zorgt dat {@code block} een terminator heeft die naar {@code target} gaat.
     *
     * Nodig omdat een geneste statement (bv. een for-loop of een inner if) de
     * cursor naar een ANDERE blok kan verplaatsen. Zonder deze controle mist het
     * LLVM-blok een terminator en weigert de verifier de module ("Basic Block
     * does not have terminator"). Alleen als het blok nog leeg is of nog niet
     * afgesloten wordt de branch toegevoegd: een al getermineerd blok (return,
     * throw of geneste branch) laten we met rust.
     */
    private void closeBlock(final IRBasicBlock block, final IRBasicBlock target) {
        if (block == null || block.isTerminated()) {
            return;
        }
        builder.setCurrentBlock(block);
        builder.emitBranch(target);
    }

    private InstanceOfExpression asInstanceOfPattern(final ExpressionTree condition) {
        ExpressionTree expr = condition;

        while (expr instanceof ParenthesizedExpression parenthesized) {
            expr = parenthesized.getExpression();
        }

        if (expr instanceof InstanceOfExpression instanceOf && instanceOf.getTypeExpression() instanceof TypePattern) {
            return instanceOf;
        }

        return null;
    }

    private TypeMirror patternInstanceOfType(final InstanceOfExpression instanceOf) {
        if (instanceOf.getTypeExpression() instanceof TypePattern typePattern
                && typePattern.getVariableDeclarator() != null
                && typePattern.getVariableDeclarator().getType() != null) {
            return typePattern.getVariableDeclarator().getType();
        }
        return instanceOf.getTypeExpression() != null ? instanceOf.getTypeExpression().getType() : null;
    }

    @Override
    public IRValue visitWhileStatement(WhileStatementTree whileStatement,
                                       IRBuilder param) {
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock condBlk = builder.beginBlock("while.cond");
        IRBasicBlock bodyBlk = builder.beginBlock("while.body");
        IRBasicBlock exitBlk = builder.beginBlock("while.exit");

        breakTargets.push(exitBlk);
        continueTargets.push(condBlk);
        registerLabeledContinueTargets(condBlk);

        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        emitConditionBranch(whileStatement.getCondition(), bodyBlk, exitBlk);

        // Body
        builder.setCurrentBlock(bodyBlk);
        acceptTree(whileStatement.getBody(), builder);
        // Sluit het blok waar de body eindigde (een geneste if/ternarylus kan de
        // cursor verplaatsen en bodyBlk al getermineerd hebben).
        closeBlock(builder.currentBlock(), condBlk);

        breakTargets.pop();
        continueTargets.pop();

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    @Override
    public IRValue visitDoWhileStatement(DoWhileStatementTree doWhileStatement,
                                         IRBuilder param) {
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock bodyBlk = builder.beginBlock("dowhile.body");
        IRBasicBlock condBlk = builder.beginBlock("dowhile.cond");
        IRBasicBlock exitBlk = builder.beginBlock("dowhile.exit");

        breakTargets.push(exitBlk);
        continueTargets.push(condBlk);
        registerLabeledContinueTargets(condBlk);

        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(bodyBlk);

        // Body
        builder.setCurrentBlock(bodyBlk);
        acceptTree(doWhileStatement.getBody(), builder);
        closeBlock(builder.currentBlock(), condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        emitConditionBranch(doWhileStatement.getCondition(), bodyBlk, exitBlk);

        breakTargets.pop();
        continueTargets.pop();

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    @Override
    public IRValue visitForStatement(ForStatementTree forStatement,
                                     IRBuilder param) {
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock condBlk = builder.beginBlock("for.cond");
        IRBasicBlock bodyBlk = builder.beginBlock("for.body");
        IRBasicBlock updateBlk = builder.beginBlock("for.update");
        IRBasicBlock exitBlk = builder.beginBlock("for.exit");

        breakTargets.push(exitBlk);
        continueTargets.push(updateBlk);
        registerLabeledContinueTargets(updateBlk);

        // Init in entry-blok
        builder.setCurrentBlock(entryBlk);
        scope.pushScope();
        for (StatementTree init : forStatement.getForInit()) {
            acceptTree(init, builder);
        }
        builder.emitBranch(condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        if (forStatement.getCondition() != null) {
            emitConditionBranch(forStatement.getCondition(), bodyBlk, exitBlk);
        } else {
            // for (;;) ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø oneindige lus
            builder.emitBranch(bodyBlk);
        }

        // Body
        builder.setCurrentBlock(bodyBlk);
        if (forStatement.getStatement() != null) {
            acceptTree(forStatement.getStatement(), builder);
        }
        // Sluit het blok waar de body eindigde, niet bodyBlk zelf: een geneste
        // statement (bv. een if of lus) kan de cursor naar if.merge of een
        // eigen lusblok verplaatsen en bodyBlk al getermineerd hebben. Dan
        // zou het laatste blok zonder terminator achterblijven.
        if (!builder.currentBlockTerminated()) {
            builder.emitBranch(updateBlk);
        }

        // Update
        builder.setCurrentBlock(updateBlk);
        for (StatementTree update : forStatement.getForUpdate()) {
            acceptTree(update, builder);
        }
        builder.emitBranch(condBlk);

        scope.popScope();

        breakTargets.pop();
        continueTargets.pop();

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    // -------------------------------------------------------
    // Expressies
    // -------------------------------------------------------

    @Override
    public IRValue visitLiteralExpression(LiteralExpressionTree literal,
                                          IRBuilder param) {
        Object value = literal.getLiteral();

        return switch (literal.getLiteralKind()) {
            case INTEGER -> builder.constInt(((Number) value).longValue());
            case LONG -> builder.constInt(((Long) value), 64);
            case BOOLEAN -> builder.constBool((Boolean) value);
            case FLOAT -> IRValue.ofF32(((Float) value).doubleValue());
            case DOUBLE -> builder.constFloat((Double) value);
            case BYTE -> builder.constInt(((Byte) value).longValue(), 8);
            case SHORT -> builder.constInt(((Short) value).longValue(), 16);
            case CHAR -> builder.constInt(((Character) value), 16);
            case STRING -> builder.constString((String) value, TypeMirrorToIRType.toJvmDescriptor(literal.getType()));
            case NULL -> IRValue.nullPtr(IRType.I8);
            case CLASS -> IRValue.nullPtr(IRType.I8); // class-literal als opaque ptr
        };
    }

    @Override
    public IRValue visitBinaryExpression(BinaryExpressionTree binaryExpr,
                                         IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                binaryExpr.getLineNumber(),
                binaryExpr.getColumnNumber()
        );

        Tag tag = binaryExpr.getTag();

        if (tag == Tag.ASSIGN) {
            IRValue value = acceptTree(binaryExpr.getRight(), builder);

            // Sla de waarde op via de linker kant
            emitStoreBack(binaryExpr.getLeft(), value);
            return value;
        }

        // Compound assignments: uitvouwen naar load + op + store
        if (TagToIROp.isCompoundAssignment(tag)) {
            return emitCompoundAssignment(binaryExpr);
        }

        if (tag == Tag.AND || tag == Tag.OR) {
            return emitShortCircuit(binaryExpr, tag == Tag.AND);
        }

        IRValue left = acceptTree(binaryExpr.getLeft(), builder);
        IRValue right = acceptTree(binaryExpr.getRight(), builder);
        Op op = TagToIROp.map(tag);

        if (left == null || right == null) {
            return left != null ? left : right;
        }

        // String-concatenatie met een char-operand: char en short zijn allebei
        // i16 in het IR, dus de concat zou het getal toevoegen ("65" i.p.v.
        // "A"). Het brontype zegt dat het een char is.
        if (tag == Tag.ADD) {
            final boolean stringConcat = isStringType(left.type()) || isStringType(right.type());
            if (stringConcat) {
                left = charToString(binaryExpr.getLeft(), left);
                right = charToString(binaryExpr.getRight(), right);
            }
        }

        // Auto-unboxing: een wrapper-referentie (Integer/Long/...) tegen
        // een primitieve operand (int/float/bool) wordt tooth's
        // primitieve vorm gezet ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø anders volgt de emitter een
        // String-concat of een verifieerder-fout bij compare-opcodes.
        right = unboxIfNeeded(right, left.type());
        left = unboxIfNeeded(left, right.type());

        return builder.emitBinaryOp(op, left, right);
    }

    /**
     * {@code a && b} / {@code a || b} met kortsluiting (JLS §15.23/§15.24): de
     * rechterkant wordt alleen geëvalueerd als de linkerkant de uitkomst nog
     * niet vastlegt. Als gewone AND/OR werd {@code b} altijd uitgerekend:
     * {@code s != null && s.contains("+")} riep contains op null aan.
     *
     * <pre>
     *   left-blok:  cel = left; condbr left, (and: rhs | merge), (and: merge | rhs)
     *   rhs:        cel = right; br merge
     *   merge:      load cel
     * </pre>
     */
    /**
     * Springt vanuit het huidige blok naar {@code trueBlk} of {@code falseBlk}
     * op basis van een conditie (if/while/for/do/ternary), zoals javac: een
     * {@code &&}/{@code ||}/{@code !} wordt een keten van sprongen in plaats van
     * een boolean-waarde. Zo is het true-doel alleen bereikbaar via de
     * operanden die het waar maakten, en is een variabele die in de
     * rechterkant wordt toegekend daar definitief toegekend (JLS 16.1.2):
     * {@code if (s != null && (pos = s.indexOf(x)) >= 0) use(pos)} — via een
     * boolean-cel kwam `pos` op het merge-punt ongeïnitialiseerd binnen en
     * weigerde de JVM-verifier de klasse.
     */
    private void emitConditionBranch(final ExpressionTree condition,
                                     final IRBasicBlock trueBlk,
                                     final IRBasicBlock falseBlk) {
        ExpressionTree expr = condition;
        while (expr instanceof ParenthesizedExpression parenthesized) {
            expr = parenthesized.getExpression();
        }

        if (expr instanceof BinaryExpressionTree binary
                && (binary.getTag() == Tag.AND || binary.getTag() == Tag.OR)) {
            final boolean isAnd = binary.getTag() == Tag.AND;
            final var leftBlk = builder.currentBlock();
            final var rhsBlk = builder.beginBlock(isAnd ? "and.rhs" : "or.rhs");
            builder.setCurrentBlock(leftBlk);
            emitConditionBranch(binary.getLeft(),
                    isAnd ? rhsBlk : trueBlk,
                    isAnd ? falseBlk : rhsBlk);
            builder.setCurrentBlock(rhsBlk);
            emitConditionBranch(binary.getRight(), trueBlk, falseBlk);
            return;
        }

        if (expr instanceof UnaryExpressionTree unary && unary.getTag() == Tag.NOT) {
            emitConditionBranch(unary.getExpression(), falseBlk, trueBlk);
            return;
        }

        final IRValue value = unboxIfNeeded(acceptTree(expr, builder), IRType.BOOL);
        builder.emitCondBranch(value, trueBlk, falseBlk);
    }

    private IRValue emitShortCircuit(final BinaryExpressionTree binaryExpr, final boolean isAnd) {
        IRValue left = acceptTree(binaryExpr.getLeft(), builder);
        if (left == null) {
            return acceptTree(binaryExpr.getRight(), builder);
        }
        left = unboxIfNeeded(left, IRType.BOOL);
        final var leftTailBlk = builder.currentBlock();

        final var rhsBlk = builder.beginBlock(isAnd ? "and.rhs" : "or.rhs");
        IRValue right = acceptTree(binaryExpr.getRight(), builder);
        final var rhsTailBlk = builder.currentBlock();
        final var mergeBlk = builder.beginBlock(isAnd ? "and.merge" : "or.merge");

        builder.setCurrentBlock(leftTailBlk);
        final var resultCell = builder.emitAlloca("sc." + ternaryCounter++, IRType.BOOL);
        builder.emitStore(resultCell, left);
        if (isAnd) {
            builder.emitCondBranch(left, rhsBlk, mergeBlk);
        } else {
            builder.emitCondBranch(left, mergeBlk, rhsBlk);
        }

        builder.setCurrentBlock(rhsTailBlk);
        if (right != null) {
            builder.emitStore(resultCell, unboxIfNeeded(right, IRType.BOOL));
        }
        closeBlock(rhsTailBlk, mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        return builder.emitLoad(resultCell);
    }

    @Override
    public IRValue visitUnaryExpression(UnaryExpressionTree unary,
                                        IRBuilder param) {
        Tag tag = unary.getTag();
        IRValue operand = acceptTree(unary.getExpression(), builder);
        // Een Boolean-box-operand (bv. `!oppositeValue` met een
        // Boolean-referentie) wordt eerst naar een primitieve bool
        // gezet, anders volgt de emitter een verifieerder-fout.
        if (tag == Tag.NOT && operand != null) {
            operand = unboxIfNeeded(operand, IRType.BOOL);
        }

        return switch (tag) {
            case SUB -> {
                // Negatie: 0 - operand
                IRValue zero = builder.constInt(0);
                yield builder.emitBinaryOp(Op.SUB, zero, operand);
            }
            case NOT -> {
                // Logische negatie: operand == false (0)
                IRValue zero = builder.constBool(false);
                yield builder.emitBinaryOp(Op.EQ, operand, zero);
            }
            case POST_INC -> {
                // i++ : laad, verhoog, sla op, geef originele waarde terug
                IRValue one = builder.constInt(1);
                IRValue incremented = builder.emitBinaryOp(Op.ADD, operand, one);
                emitStoreBack(unary.getExpression(), incremented);
                yield operand; // post = geef originele waarde terug
            }
            case POST_DEC -> {
                IRValue one = builder.constInt(1);
                IRValue decremented = builder.emitBinaryOp(Op.SUB, operand, one);
                emitStoreBack(unary.getExpression(), decremented);
                yield operand;
            }
            // ++x / --x: ophogen, opslaan en de NIEUWE waarde teruggeven.
            // Ontbraken: de operand bleef ongewijzigd.
            case PRE_INC -> {
                IRValue incremented = builder.emitBinaryOp(Op.ADD, operand, builder.constInt(1));
                emitStoreBack(unary.getExpression(), incremented);
                yield incremented;
            }
            case PRE_DEC -> {
                IRValue decremented = builder.emitBinaryOp(Op.SUB, operand, builder.constInt(1));
                emitStoreBack(unary.getExpression(), decremented);
                yield decremented;
            }
            default -> operand;
        };
    }

    @Override
    public IRValue visitIdentifier(IdentifierTree identifier, IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                identifier.getLineNumber(),
                identifier.getColumnNumber()
        );

        String name = identifier.getName();

        // this / super ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø geef de this-parameter terug
        if ("this".equals(name) || "super".equals(name)) {
            var local = scope.lookup("this");
            if (local.isPresent()) {
                IRValue ptr = local.get();
                if (ptr.type() instanceof IRType.Ptr ptrType && ptrType.jvmDescriptor() == null) {
                    return builder.emitLoad(ptr);
                }
                return ptr;
            } else {
                return null;
            }
        }

        // Lokale variabele of parameter
        var local = scope.lookup(name);
        if (local.isPresent()) {
            IRValue ptr = local.get();
            if (ptr.type() instanceof IRType.Ptr ptrType) {
                if (ptrType.jvmDescriptor() == null) {
                    return builder.emitLoad(ptr);
                }
                return ptr;
            } else if (ptr instanceof IRValue.Temp temp) {
                return temp;
            }
            return ptr;
        }

        // Symbool opzoeken via het element
        Element symbol = (identifier instanceof CIdentifierTree ci)
                ? ci.getSymbol() : null;

        if (symbol != null) {
            ElementKind kind = symbol.getKind();

            // Een in de omsluitende methode gedeclareerde variabele, gebruikt
            // in de body van een anonieme klasse: haar gevangen kopie.
            if (kind.isVariable() && !kind.isField()) {
                final IRValue captured = loadCapturedVariable(symbol);
                if (captured != null) {
                    return captured;
                }
            }

            if (kind.isField()) {
                // Statisch veld ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø resolveField produceert een Named(isStatic):
                // de backend resolveert dit als GETSTATIC (werkt cross-module;
                // de oude global-lookup faalde voor velden in andere
                // batch-modules).
                if (symbol.isStatic()) {
                    return builder.emitLoad(resolveField(symbol));
                }
                // Instantieveld ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø laad via this
                IRValue receiver = implicitFieldReceiver(symbol);
                if (receiver != null) {
                    return builder.emitLoad(emitFieldLoad(receiver, symbol));
                }
            }

            // Klasse-naam of type-referentie: een identifier die exact een
            // enclosing class van de huidige class benoemt is een qualified
            // this (`Ansi.this`, JLS ├âÔÇÜ├é┬º15.8.4 ├â┬ó├óÔÇÜ┬¼├óÔé¼┬Ø de `.this`-suffix is bij het
            // parsen verstopt in de identifier) en moet de enclosing
            // instance als waarde opleveren. Andere class-referenties
            // leveren geen waarde op.
            if (kind.isDeclaredType()) {
                if (symbol instanceof TypeElement identifierClass
                        && currentTypeElement != null
                        && !qualifiedNameOf(identifierClass)
                                .equals(qualifiedNameOf(currentTypeElement))) {
                    final var outerValue = enclosingInstanceValue(identifierClass);
                    if (outerValue != null) {
                        return outerValue;
                    }
                }
                return null;
            }
        } else {
            // Zonder symbol: het class-beeld van een qualified this is via
            // het TYPE van de identifier te bepalen (Ansi.this ├â┬ó├óÔé¼┬á├óÔé¼Ôäó class Ansi).
            if (identifier.getType() instanceof io.github.potjerodekool.nabu.type.DeclaredType declaredType
                    && currentTypeElement != null
                    && !qualifiedNameOf(declaredType.asTypeElement())
                            .equals(qualifiedNameOf(currentTypeElement))) {
                final var outerValue = enclosingInstanceValue(declaredType.asTypeElement());
                if (outerValue != null) {
                    return outerValue;
                }
            }
        }

        return null;
    }

    @Override
    public IRValue visitFieldAccessExpression(
            FieldAccessExpressionTree fieldAccess, IRBuilder param) {

        builder.setLocation(
                currentClassName + ".nabu",
                fieldAccess.getLineNumber(),
                fieldAccess.getColumnNumber()
        );

        ExpressionTree selected = fieldAccess.getSelected();
        IdentifierTree field = fieldAccess.getField();

        Element fieldSymbol = (field instanceof CIdentifierTree ci)
                ? ci.getSymbol() : null;

        // Class literal (bijv. String.class, int[].class): laad de constante
        // van het geselecteerde type, niet van het pseudo-veld "class".
        if ("class".equals(field.getName()) && selected != null) {
            final var selectedType = selected.getType();

            if (selectedType != null) {
                final var descriptor = TypeMirrorToIRType.toJvmDescriptor(selectedType);

                if (descriptor != null) {
                    return new IRValue.ConstClass(
                            new IRType.Ptr(IRType.I8, descriptor)
                    );
                }
            }
        }

        // Statisch veld (bijv. System.out)
        if (fieldSymbol != null && fieldSymbol.isStatic()) {
            String globalName = ((TypeElement) fieldSymbol.getEnclosingElement()).getQualifiedName()
                    + "_" + field.getName();
            // Declareer als extern als nog niet aanwezig
            IRType fieldType = TypeMirrorToIRType.map(
                    fieldSymbol.asType());
            builder.declareExternalGlobal(globalName, fieldType, fieldSymbol.isStatic());
            IRValue globalPtr = builder.lookup(globalName);

            return builder.emitLoad(TypeMirrorToIRType.map(field.getSymbol().asType()), globalPtr);
        }

        // Qualified-this (`Ansi.this`): het "veld" is een class-referentie,
        // geen VariableElement. De waarde is de enclosing instance van de
        // geselecteerde class (JLS ├âÔÇÜ├é┬º15.8.4) ├â┬ó├óÔÇÜ┬¼├óÔé¼┬Ø via de synthetic `this$0`-
        // keten van de huidige klasse.
        if ("this".equals(field.getName())
                && !(fieldSymbol instanceof VariableElement)
                && selected != null) {
            TypeElement targetClass = null;
            if (fieldSymbol instanceof TypeElement fieldClass) {
                targetClass = fieldClass;
            } else {
                final var selectedElement = (selected instanceof CIdentifierTree selectedId)
                        ? selectedId.getSymbol() : null;
                if (selectedElement instanceof TypeElement selectedTypeElement) {
                    targetClass = selectedTypeElement;
                } else if (fieldAccess.getType() instanceof io.github.potjerodekool.nabu.type.DeclaredType declaredType) {
                    final var typeElement = declaredType.asTypeElement();
                    if (typeElement != null) {
                        targetClass = typeElement;
                    }
                }
            }
            if (targetClass != null) {
                // `this` van de zelfde class: gewone this
                if (qualifiedNameOf(targetClass).equals(qualifiedNameOf(currentTypeElement))) {
                    return scope.lookup("this").orElse(null);
                }
                final var outerValue = enclosingInstanceValue(targetClass);
                if (outerValue != null) {
                    return outerValue;
                }
                return null;
            }
        }

        if (fieldSymbol == null) {
            final var selectedType = selected != null ? selected.getType() : null;

            if ("length".equals(field.getName())
                    && (isArrayType(selectedType)
                            || fieldAccess.getType() != null
                            && fieldAccess.getType().getKind() == io.github.potjerodekool.nabu.type.TypeKind.INT)) {
                // Bij een .length-notatie op een (heid) array-access kan het
                // geselecteerde type door de resolver de array-elementen
                // substitueren (cells[col] -> Text i.p.v. Text[]); het fasetype
                // (INT) is dan de enige betrouwbare hint. Het geselecteerde is
                // in elk geval een array-referentie.
                IRValue obj = acceptTree(selected, builder);
                return obj != null ? builder.emitArrayLength(obj) : null;
            }

            final var type = field.getType();
            final var descriptor = TypeMirrorToIRType.toJvmDescriptor(type);
            return new IRValue.ConstClass(
                    new IRType.Ptr(IRType.I8, descriptor)
            );
        }

        // Instantieveld
        if (selected != null) {
            IRValue obj = acceptTree(selected, builder);
            if (obj != null && fieldSymbol != null) {
                return builder.emitLoad(emitFieldLoad(obj, fieldSymbol));
            }
        }

        return null;
    }

    @Override
    public IRValue visitMethodInvocation(MethodInvocationTree invocation,
IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                invocation.getLineNumber(),
                invocation.getColumnNumber()
        );

        ExecutableType methodType = invocation.getMethodType();
        if (methodType == null) {
            return null;
        }

        // De descriptor van een aanroep moet gebaseerd zijn op de
        // verwijderde (erased) declaratietypes van de methode ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø niet op de
        // gesubstitueerde types van deze aanroep (JLS ├âãÆ├óÔé¼┼í├âÔÇÜ├é┬º4.6 / JVMS ├âãÆ├óÔé¼┼í├âÔÇÜ├é┬º4.3).
        final IRType returnType = TypeMirrorToIRType.mapReturnType(
                methodType.getMethodSymbol().getReturnType());


        List<IRType> paramTypes = methodType.getMethodSymbol().getParameters().stream()
                .map(p -> TypeMirrorToIRType.map(p.asType()))
                .toList();

        CallKind callKind = CallKindResolver.resolve(invocation);
        String methodName = CallKindResolver.resolveName(invocation);
        ExpressionTree target = CallKindResolver.resolveTarget(invocation);

        final var owner = invocation.getMethodType().getMethodSymbol()
                .getEnclosingElement();

        // Constructor-delegatie (`this(...)`): de callee is de eigen
        // <init>; de IR-functienaam is `..._init` (de <init>-emaar).
        if ("this".equals(methodName)
                && methodType.getMethodSymbol() != null
                && methodType.getMethodSymbol().getKind() == ElementKind.CONSTRUCTOR) {
            methodName = "init";
        }

        // Argumenten verwerken
        List<IRValue> args = new ArrayList<>();

        // Voor instantie-aanroepen: voeg 'this' toe als eerste argument
        if (callKind != CallKind.STATIC && target != null) {
            if (CallKindResolver.isSuper(invocation)) {
                // Super-aanroep: de receiver is 'this' (het super-identifi-
                // fier heeft zelf geen waarde).
                scope.lookup("this").ifPresent(args::add);
            } else {
                IRValue receiver = acceptTree(target, builder);
                if (receiver != null) args.add(receiver);
            }
        } else if (callKind != CallKind.STATIC) {
            // Impliciete this — of de enclosing instance als de methode bij
            // een omsluitende klasse hoort (JLS §15.12.4.1): picocli's
            // Interpreter riep `getCommandSpec()` (van CommandLine) aan met de
            // Interpreter zelf als ontvanger.
            final IRValue receiver = implicitMethodReceiver(methodType.getMethodSymbol());
            if (receiver != null) args.add(receiver);
        }

        // JLS 15.12.4.4: bij een varargs-callee horen de argumenten na de
        // vaste parameters in een array-parameter. De IR-declaratie
        // (paramTypes) bevat die array al als laatste parameter, dus de
        // trailing argumenten moeten hier tot een array worden gebundeld.
        // Zonder dit bundelen vertaalt een call-site zich naar een array-
        // argument plus de losse elementen, en krijgt de callee meer
        // argumenten dan parameters (LLVM: "Incorrect number of arguments").
        final boolean varArgsCallee = methodType.getMethodSymbol() != null
                && methodType.getMethodSymbol().isVarArgs();
        final int fixedCount = varArgsCallee && !paramTypes.isEmpty()
                ? paramTypes.size() - 1
                : paramTypes.size();
        final List<IRValue> varArgValues = new ArrayList<>();
        int providedArgCount = 0;

        // Elementtype van de varargs-array; nodig om elk element op de
        // componentType te brengen voordat het in de array terechtkomt.
        final IRType varArgComponentType = varArgsCallee
                ? varArgComponentType(paramTypes.get(paramTypes.size() - 1))
                : null;

        for (int i = 0; i < invocation.getArguments().size(); i++) {
            ExpressionTree arg = invocation.getArguments().get(i);
            IRValue argVal = acceptTree(arg, builder);

if (argVal instanceof IRValue.FunctionRef fnRef
                        && i < methodType.getParameterTypes().size()) {
                    final var paramType = methodType.getParameterTypes().get(i);
                    if (paramType instanceof io.github.potjerodekool.nabu.type.DeclaredType dt) {
                        final var classElement = (TypeElement) dt.asElement();
                        if (classElement.isFunctionalInterface()) {
                            argVal = wrapWithSamConversion(fnRef, classElement, dt);
                        }
                    }
                }

// Voor een varargs-element is de parameter op positie i niet de
                // array-parameter maar diens componenttype: `int...` heeft
                // componenttype `int`. Zonder die aanpassing zou een int-element
                // tegen de array-parameter (een referentie) gehouden worden en
                // onterecht geboxed, waarna de store in de int[] breekt.
                if (argVal != null && i < methodType.getParameterTypes().size()) {
                    final TypeMirror paramTypeMirror =
                            varArgsCallee && i >= fixedCount
                                    ? componentTypeMirror(
                                        methodType.getParameterTypes().get(i))
                                    : methodType.getParameterTypes().get(i);
                    if (paramTypeMirror != null) {
                        argVal = boxArgIfNeeded(argVal, arg.getType(), paramTypeMirror);
                    }
                }

                // Erasure-fallback: de daadwerkelijk ge├âãÆ├é┬®mitteerde descriptor
                // gebruikt de erased declaratietypes (paramTypes). Wanneer een
                // generische methode (bv. ISetter.set(T)) een gesubstitueerde
                // PRIMITIEVE parameter type aangeleverd krijgt terwijl de
                // erasure een referentie is (Object), moet het argument alsnog
                // geboxed worden, anders breekt de verifier.
                if (argVal != null
                        && i < paramTypes.size()
                        && i < fixedCount
                        && paramTypes.get(i) instanceof IRType.Ptr
                        && !(argVal.type() instanceof IRType.Ptr)
                        && !(argVal.type() instanceof IRType.Void)) {
                    argVal = boxFromIRType(argVal);
                }

if (argVal == null) continue;
            if (varArgsCallee && i >= fixedCount) {
                // JLS 15.12.4.4: is de trailing waarde al toewijsbaar aan de
                // array-parameter, dan is dit g├®├®n varargs-element maar de
                // array zelf. `f(int[] xs)` aangeroepen als `f(xs)` mag dus
                // niet als `new int[]{xs}` worden gebundeld; anders krijgt de
                // array-store een pointer waar een int verwacht wordt
                // ("Third argument: expected I, but found R").
                if (i == fixedCount
                        && invocation.getArguments().size() == paramTypes.size()
                        && isArrayAssignableToVarArgs(arg, argVal, paramTypes.get(paramTypes.size() - 1))) {
                    args.add(argVal);
                    providedArgCount++;
                    continue;
                }

                // Varargs-element: niet los toevoegen, maar onderaan
                // in de array-parameter van de callee gebundeld.
                //
                // JLS 5.5: een varargs-element wordt losgelaten in de context
                // van het componenttype, dus een primitief element voor een
                // Object[]-parameter wordt geboxed. Zonder deze stap blijft de
                // int op de stack en breekt de verifier bij de array-store
                // ("Third argument: expected R, but found I", bv.
                // Tracer.debug(String, Object...) met een size()-argument).
                if (argVal != null
                        && varArgComponentType instanceof IRType.Ptr
                        && !(argVal.type() instanceof IRType.Ptr)
                        && !(argVal.type() instanceof IRType.Void)) {
                    argVal = boxFromIRType(argVal);
                }
                varArgValues.add(argVal);
            } else {
                args.add(argVal);
                providedArgCount++;
            }
        }

        // De verzamelde varargs-elementen inpakken in de array-parameter.
        // Een call-site die al een expliciete array meegeeft heeft
        // varArgValues leeg en laat de parameter ongemoeid.
        if (varArgsCallee) {
            final IRType arrayType = paramTypes.get(paramTypes.size() - 1);
            final IRType componentType = varArgComponentType;
            if (!varArgValues.isEmpty()) {
                final var arrayPtr = builder.emitAllocaArray(varArgValues.size(), arrayType);
                for (int i = 0; i < varArgValues.size(); i++) {
                    builder.emitArrayStore(arrayPtr, IRValue.ofI32(i),
                            varArgValues.get(i), componentType);
                }
                args.add(arrayPtr);
            } else if (providedArgCount < paramTypes.size()) {
                // JLS 15.12.4.4: een varargs-call zonder elementen
                // (bv. f(1) voor f(int, int...)) draagt alsnog een lege
                // array mee. Zonder dit heeft de call ├®├®n argument minder dan
                // de declaratie en wordt de callee verkeerd gebonden.
                args.add(builder.emitAllocaArray(0, arrayType));
            }
        }

        // JLS ├âÔÇÜ├é┬º8.8.7: een constructor-aanroep van een niet-statische member
        // class levert de enclosing instance als eerste callee-argument
        // (na de receiver). Super/this in Object-superclasses valt buiten
        // deze regel (die classes zijn static/toplevel).
        if (callKind == CallKind.SPECIAL
                && isOuterInstanceCtor(invocation.getMethodType().getMethodSymbol())) {
            final var calleeClass = (TypeElement) owner;
            final var outerElement = outerOf(calleeClass);
            final var outerType = TypeMirrorToIRType.map(outerElement.asType());
            // De callee-outer-slot verlangt een instantie van de class die
            // de callee omgeeft (= outerOf(callee)).
            final var outerValue = enclosingInstanceValue(outerElement);
            final IRValue outerVal = outerValue != null
                    ? outerValue
                    : new IRValue.ConstNull(new IRType.Ptr(IRType.I8,
                            TypeMirrorToIRType.toJvmDescriptor(outerElement.asType())));
            // Receiver zit op args[0] (impliciete this); daarna outer.
            final int insertIndex = Math.min(1, args.size());
            args.add(insertIndex, outerVal);
            paramTypes = new ArrayList<>(paramTypes);
            paramTypes.add(0, outerType);
        }

        // Lower-Boxer-fix: een resolution-level `.doubleValue()`/etc.-unbox
        // op een receiver-tree die (na phi/minerale promotie) een
        // PRIMITIEF IR-type wijst is geen wrapper-unbox maar een
        // numerieke conversie ├â┬ó├óÔÇÜ┬¼├óÔé¼┬Ø emitteer de cast, niet een
        // INVOKEVIRTUAL op een primitieve stackwaarde (verifier-breach).
        if (callKind == CallKind.VIRTUAL
                && args.size() == 1
                && isNumericUnboxMethod(methodName)
                && owner != null
                && String.valueOf(owner.getSimpleName()).contentEquals(wrapperForUnbox(methodName))) {
            final var receiver = args.get(0);
            final IRType desired = unboxPrimitiveType(methodName);
            if (receiver != null
                    && (receiver.type() instanceof IRType.Int
                    || receiver.type() instanceof IRType.Float
                    || receiver.type() instanceof IRType.Bool)) {
                return builder.emitCast(receiver, desired);
            }
        }

        // Volledig gekwalificeerde naam
        String ownerName = methodType.getOwner() != null
                ? methodType.getOwner().getQualifiedName() + "_"
                : "";
        String fullName = ownerName + methodName;

        final IRValue call = switch (callKind) {
            case STATIC, VIRTUAL, INTERFACE ->
                    builder.emitCall(callKind, fullName, returnType, paramTypes, args);
            case SPECIAL ->
                // Constructor of super ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø gebruik ook emitCall
                    builder.emitCall(callKind, fullName, returnType, paramTypes, args);
        };

        return castCallResultIfNeeded(methodType, call);
    }

    // Bij generieke methodes kan de gesubstitueerde (resolved) return
    // afwijken van de erased return (bijv. Map<String, byte[]>.get() ->
    // erased Object, resolved byte[]). JVMS vereist dan een expliciete
    // CHECKCAST zodat de stack (erased) matcht met de declared return.
    private IRValue castCallResultIfNeeded(final ExecutableType methodType,
                                           final IRValue call) {
        final var erasedReturnType = methodType.getMethodSymbol().getReturnType();
        final var resolvedReturnType = methodType.getReturnType();

        if (call == null || erasedReturnType == null || resolvedReturnType == null) {
            return call;
        }

        if (erasedReturnType == resolvedReturnType) {
            return call;
        }

        final var kind = resolvedReturnType.getKind();
        if (kind != TypeKind.DECLARED && kind != TypeKind.ARRAY) {
            return call;
        }

        return builder.emitCast(call, TypeMirrorToIRType.mapReturnType(resolvedReturnType));
    }

    @Override
    public IRValue visitNewClass(NewClassExpression newClass, IRBuilder param) {
        if (!builder.insideFunction()) {
            return null;
        }


        builder.setLocation(
                currentClassName + ".nabu",
                newClass.getLineNumber(),
                newClass.getColumnNumber()
        );

        if (newClass.getClassDeclaration() != null
                && newClass.getClassDeclaration().getClassSymbol() instanceof TypeElement anonymousType
                && anonymousType.getNestingKind()
                        == io.github.potjerodekool.nabu.lang.model.element.NestingKind.ANONYMOUS) {
            return emitAnonymousInstance(newClass, anonymousType);
        }

        // Klassetype: liever het resolver-type van de hele expressie (dan)
        // dan het naam-symbool ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø de naam-tree kan zonder type blijven
        // (descriptor-loze Ptr), wat door de backends als String/omlauf
        // geresolved raakt.
        TypeMirror classMirror = newClass.getType();
        if (classMirror == null || classMirror.getKind() == io.github.potjerodekool.nabu.type.TypeKind.VOID) {
            classMirror = newClass.getName() != null ? newClass.getName().getType() : null;
        }

        IRType objectType = TypeMirrorToIRType.map(classMirror);

        // Alloceer het object op de heap
        IRValue obj = builder.emitHeapAlloc("new_obj", objectType);

        // Roep de constructor aan
        String className = classMirror != null && classMirror.getClassName() != null
                ? classMirror.getClassName().replace('.', '_')
                : "unknown";
        String initName = className + "_init";

        List<IRValue> args = new ArrayList<>();

        /*
        if (newClass.getClassDeclaration() != null) {
            args.add(obj); // this
        }
        */

        // JLS ├âÔÇÜ├é┬º15.9: qualified instance creation (`ansi.new Text(...)`) ├â┬ó├óÔÇÜ┬¼├óÔé¼┬Ø
        // de geselecteerde expressie is de enclosing instance.
        IRValue qualifiedOuterValue = null;
        if (newClass.getName() instanceof FieldAccessExpressionTree fieldNameAccess) {
            qualifiedOuterValue = acceptTree(fieldNameAccess.getSelected(), builder);
        }

        for (ExpressionTree arg : newClass.getArguments()) {
            IRValue argVal = acceptTree(arg, builder);
if (argVal != null) args.add(argVal);
        }

        // JLS ├âÔÇÜ├é┬º8.8.7: instantie-creatie van een niet-statische member class
        // levert de enclosing instance als eerste callee-argument. Bij een
        // qualified new is dat de geselecteerde expressie; anders wordt de
        // enclosing instance uit de huidige klasse-chain geladen.
        TypeElement targetClassElement = null;
        if (classMirror instanceof io.github.potjerodekool.nabu.type.DeclaredType declaredType) {
            targetClassElement = declaredType.asTypeElement();
        }
        if (targetClassElement != null
                && needsOuterInstance(targetClassElement)) {
            final var outerElement = outerOf(targetClassElement);
            final var outerType = TypeMirrorToIRType.map(outerElement.asType());
            // De callee-outer-slot verlangt een instantie van de class die
            // de target omgeeft; een qualified new geeft die expliciet.
            final IRValue outerValue = qualifiedOuterValue != null
                    ? qualifiedOuterValue
                    : enclosingInstanceValue(outerElement);
            args.add(0, outerValue != null
                    ? outerValue
                    : new IRValue.ConstNull(outerType));
        }

        final var paramTypes = args.stream()
                .map(IRValue::type)
                .toList();

        // JLS ├âãÆ├óÔé¼┼í├âÔÇÜ├é┬º4.6: de MethodRef-descriptor van een constructor-aanroep
        // volgt de DECLARATIE-parameters (na type-erasure), niet de
        // concreetere aanroepwaarden. Anders verifieert de JVM de aanroep
        // niet (NoSuchMethodError op run-time). De DECLARATIE bevat de
        // synthetic outer-parameters niet, dus de match lopen met de
        // arguments zonder die outer-slot.
        final boolean outerPrepended = targetClassElement != null
                && needsOuterInstance(targetClassElement);
        final var argMirrors = newClass.getArguments().size() == (outerPrepended ? args.size() - 1 : args.size())
                ? newClass.getArguments().stream().map(ExpressionTree::getType).toList()
                : null;
        final var declaredParamTypes = resolveDeclaredCtorParamTypes(
                classMirror,
                outerPrepended ? args.size() - 1 : args.size(),
                outerPrepended ? paramTypes.subList(1, paramTypes.size()) : paramTypes,
                argMirrors);
        final IRType[] effectiveParamTypes;
        if (declaredParamTypes != null) {
            if (outerPrepended) {
                final var merged = new ArrayList<IRType>();
                merged.add(paramTypes.getFirst());
                merged.addAll(declaredParamTypes);
                effectiveParamTypes = merged.toArray(IRType[]::new);
            } else {
                effectiveParamTypes = declaredParamTypes.toArray(IRType[]::new);
            }
        } else {
            effectiveParamTypes = paramTypes.toArray(IRType[]::new);
        }

        builder.emitCall(CallKind.SPECIAL, initName, IRType.VOID, Arrays.asList(effectiveParamTypes), args);
        return obj;
    }

    /**
     * Zoekt in de (resolver-type) klasse van een constructor-aanroep de
     * DECLARATIE-parameters (na erasure) uit. Kiest de constructor met exact
     * evenveel parameters als de aanroep; anders null (valt terug op de
     * aanroep-argumenttypes).
     */
    private List<IRType> resolveDeclaredCtorParamTypes(final TypeMirror classMirror,
                                                       final int argsCount) {
        return resolveDeclaredCtorParamTypes(classMirror, argsCount, null);
    }

    private List<IRType> resolveDeclaredCtorParamTypes(final TypeMirror classMirror,
                                                       final List<IRType> argTypes) {
        if (argTypes == null) {
            return resolveDeclaredCtorParamTypes(classMirror, argTypes.size(), null);
        }
        return resolveDeclaredCtorParamTypes(classMirror, argTypes.size(), argTypes);
    }

    private List<IRType> resolveDeclaredCtorParamTypes(final TypeMirror classMirror,
                                                       final int argsCount,
                                                       final List<IRType> argTypes) {
        return resolveDeclaredCtorParamTypes(classMirror, argsCount, argTypes, null);
    }

    /**
     * Als {@code argMirrors} bekend is, wint onder de best scorende
     * constructors de meest specifieke waarop de argumenten passen (JLS
     * §15.12.2.5). De descriptor-score alleen ziet elk referentiepaar als
     * passend, waardoor de eerst gedeclareerde overload won: picocli's
     * {@code new FieldBinding(iscope, field)} bond aan
     * {@code FieldBinding(Object, Field)} i.p.v. {@code (IScope, Field)}.
     */
    private List<IRType> resolveDeclaredCtorParamTypes(final TypeMirror classMirror,
                                                       final int argsCount,
                                                       final List<IRType> argTypes,
                                                       final List<TypeMirror> argMirrors) {
        if (classMirror == null
                || !(classMirror instanceof io.github.potjerodekool.nabu.type.DeclaredType declaredType)) {
            return null;
        }
        final var typeElement = declaredType.asTypeElement();
        if (typeElement == null) {
            return null;
        }
        final var ctors = io.github.potjerodekool.nabu.lang.model.element.ElementFilter.constructorsIn(
                typeElement.getEnclosedElements());

        final var bestCtors = new ArrayList<ExecutableElement>();
        int bestScore = Integer.MAX_VALUE;
        for (final var ctor : ctors) {
            if (ctor.getParameters().size() != argsCount) {
                continue;
            }
            final var paramTypes = ctor.getParameters().stream()
                    .map(p -> TypeMirrorToIRType.map(p.asType()))
                    .toList();
            final var score = scoreCtorMatch(paramTypes, argTypes);
            if (score < bestScore) {
                bestScore = score;
                bestCtors.clear();
            }
            if (score == bestScore) {
                bestCtors.add(ctor);
            }
        }
        if (bestCtors.isEmpty()) {
            return null;
        }
        var chosen = bestCtors.getFirst();
        if (argMirrors != null && bestCtors.size() > 1) {
            final var applicable = bestCtors.stream()
                    .filter(ctor -> ctorAcceptsArguments(ctor, argMirrors))
                    .toList();
            final var mostSpecific = mostSpecificCtor(applicable);
            if (mostSpecific != null) {
                chosen = mostSpecific;
            } else if (!applicable.isEmpty()) {
                chosen = applicable.getFirst();
            }
        }
        return chosen.getParameters().stream()
                .map(p -> TypeMirrorToIRType.map(p.asType()))
                .toList();
    }

    /** Of elk bekend referentie-argument een subtype is van zijn parametertype. */
    private static boolean ctorAcceptsArguments(final ExecutableElement ctor,
                                                final List<TypeMirror> argMirrors) {
        final var params = ctor.getParameters();
        for (int i = 0; i < params.size(); i++) {
            final var arg = argMirrors.get(i);
            final var param = params.get(i).asType();
            if (arg == null || param == null || arg.isPrimitiveType() || param.isPrimitiveType()) {
                continue;
            }
            if (isReferenceSubtype(arg, param) == Boolean.FALSE) {
                return false;
            }
        }
        return true;
    }

    /** De constructor waarvan elk parametertype een subtype is van dat van alle andere, of null. */
    private static ExecutableElement mostSpecificCtor(final List<ExecutableElement> ctors) {
        ExecutableElement winner = null;
        for (final var candidate : ctors) {
            boolean beatsAll = true;
            for (final var other : ctors) {
                if (candidate != other
                        && (!paramsAreSubtypes(candidate, other) || paramsAreSubtypes(other, candidate))) {
                    beatsAll = false;
                    break;
                }
            }
            if (beatsAll) {
                if (winner != null) {
                    return null;
                }
                winner = candidate;
            }
        }
        return winner;
    }

    private static boolean paramsAreSubtypes(final ExecutableElement sub, final ExecutableElement sup) {
        final var subParams = sub.getParameters();
        final var supParams = sup.getParameters();
        for (int i = 0; i < subParams.size(); i++) {
            final var subType = subParams.get(i).asType();
            final var supType = supParams.get(i).asType();
            if (subType == null || supType == null) {
                return false;
            }
            if (subType.isPrimitiveType() || supType.isPrimitiveType()) {
                if (subType.getKind() != supType.getKind()) {
                    return false;
                }
                continue;
            }
            if (isReferenceSubtype(subType, supType) != Boolean.TRUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Subtype-relatie tussen referentietypes over de klassehiërarchie; null
     * als dat niet vast te stellen is (type-variabelen, onbekende types).
     */
    private static Boolean isReferenceSubtype(final TypeMirror sub, final TypeMirror sup) {
        if (sup instanceof io.github.potjerodekool.nabu.type.DeclaredType supDeclared
                && supDeclared.asTypeElement() != null
                && "java.lang.Object".equals(supDeclared.asTypeElement().getQualifiedName())) {
            return Boolean.TRUE;
        }
        if (sub instanceof io.github.potjerodekool.nabu.type.ArrayType subArray
                && sup instanceof io.github.potjerodekool.nabu.type.ArrayType supArray) {
            final var subComponent = subArray.getComponentType();
            final var supComponent = supArray.getComponentType();
            if (subComponent.isPrimitiveType() || supComponent.isPrimitiveType()) {
                return subComponent.getKind() == supComponent.getKind();
            }
            return isReferenceSubtype(subComponent, supComponent);
        }
        if (sub instanceof io.github.potjerodekool.nabu.type.DeclaredType subDeclared
                && sup instanceof io.github.potjerodekool.nabu.type.DeclaredType supDeclared) {
            final var subElement = subDeclared.asTypeElement();
            final var supElement = supDeclared.asTypeElement();
            if (subElement == null || supElement == null) {
                return null;
            }
            return isSubclassOrImplementor(subElement, supElement.getQualifiedName(), new HashSet<>());
        }
        if (sub instanceof io.github.potjerodekool.nabu.type.ArrayType
                && sup instanceof io.github.potjerodekool.nabu.type.DeclaredType) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isSubclassOrImplementor(final TypeElement element,
                                                   final String targetName,
                                                   final Set<String> visited) {
        final var name = element.getQualifiedName();
        if (targetName.equals(name)) {
            return true;
        }
        if (!visited.add(name)) {
            return false;
        }
        if (element.getSuperclass() instanceof io.github.potjerodekool.nabu.type.DeclaredType superType
                && superType.asTypeElement() != null
                && isSubclassOrImplementor(superType.asTypeElement(), targetName, visited)) {
            return true;
        }
        for (final var itf : element.getInterfaces()) {
            if (itf instanceof io.github.potjerodekool.nabu.type.DeclaredType itfType
                    && itfType.asTypeElement() != null
                    && isSubclassOrImplementor(itfType.asTypeElement(), targetName, visited)) {
                return true;
            }
        }
        return false;
    }

    /** 0 = perfect passend; hoger = mismatch tussen param- en argkind. */
    private static int scoreCtorMatch(final List<IRType> paramTypes,
                                      final List<IRType> argTypes) {
        int score = 0;
        if (argTypes != null) {
            for (var i = 0; i < paramTypes.size(); i++) {
                score += isCompatibleDescriptor(
                        descOf(paramTypes.get(i)),
                        descOf(argTypes.get(i))) ? 0 : 1;
            }
        }
        return score;
    }

    private static String descOf(final IRType type) {
        return switch (type) {
            case IRType.Void ignored -> "V";
            case IRType.Bool ignored -> "Z";
            case IRType.Int(int bits) -> switch (bits) {
                case 8 -> "B";
                case 16 -> "S";
                case 64 -> "J";
                default -> "I";
            };
            case IRType.Float(int bits) -> bits == 32 ? "F" : "D";
            case IRType.Ptr ptr -> {
                final var desc = ptr.jvmDescriptor();
                if (desc != null) {
                    yield desc;
                }
                if (ptr.pointee() instanceof IRType.Ptr inner
                        && inner.jvmDescriptor() != null) {
                    yield inner.jvmDescriptor();
                }
                yield "Ljava/lang/Object;";
            }
            case IRType.Array arr -> "[" + descOf(arr.elem());
            default -> "Ljava/lang/Object;";
        };
    }

    private static boolean isCompatibleDescriptor(final String paramDesc,
                                                  final String argDesc) {
        if (paramDesc.equals(argDesc)) {
            return true;
        }
        final boolean paramPrimitive = paramDesc.length() == 1 && "[VZBSSSFIJD]".indexOf(paramDesc.charAt(0)) >= 0;
        final boolean argPrimitive = argDesc.length() == 1;
        if (paramPrimitive != argPrimitive) {
            return false;
        }
        return !paramPrimitive;
    }

    @Override
    public IRValue visitAssignment(AssignmentExpressionTree assignment,
                                   IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                assignment.getLineNumber(),
                assignment.getColumnNumber()
        );

        IRValue value = acceptTree(assignment.getRight(), builder);

        // Sla de waarde op via de linker kant
        emitStoreBack(assignment.getLeft(), value);
        return value;
    }

    @Override
    public IRValue visitCastExpression(CastExpressionTree cast, IRBuilder param) {
        IRValue source = acceptTree(cast.getExpression(), builder);
        if (source == null) {
            return null;
        }
        IRType targetType = TypeMirrorToIRType.map(cast.getType());
        if (targetType == null) {
            return source;
        }

        // Een cast van een primitief naar een referentietype is een
        // boxing-cast (JLS 5.1.7), geen bitcast. Zonder boxing wordt
        // `(Object) booleanDefault` een Cast(Bool -> Ptr), en de
        // LLVM-backend emitteert daar `bitcast i1 to ptr` voor ÔÇö de
        // validator weigert dat met "Invalid bitcast".
        if (isPrimitiveIRType(source.type()) && targetType instanceof IRType.Ptr ptrTarget) {
            final var boxed = boxForReferenceCast(source, ptrTarget);
            if (boxed != source) {
                return boxed;
            }
        }
        return builder.emitCast(source, targetType);
    }

    /**
     * Boxing voor een cast van een primitief naar een referentietype
     * (JLS 5.1.7): {@code (Object) someInt} wordt {@code Integer.valueOf(...)}.
     *
     * <p>{@link #boxIfNeeded} dekt alleen de concrete wrapper-descriptors; een
     * cast heeft juist vaak {@code Object} (of een interface) als doel, dus
     * deze variant leidt de wrapper uit de primitieve waarde af als het
     * doeltype geen concrete wrapper is.
     *
     * @return de geboxte waarde, of {@code value} als er geen wrapper bestaat
     */
    private IRValue boxForReferenceCast(final IRValue value,
                                        final IRType.Ptr target) {
        final var descriptor = target.jvmDescriptor();
        if (descriptor == null) {
            return value;
        }
        final var boxClass = switch (descriptor) {
            case "Ljava/lang/Byte;" -> "java.lang.Byte";
            case "Ljava/lang/Short;" -> "java.lang.Short";
            case "Ljava/lang/Integer;" -> "java.lang.Integer";
            case "Ljava/lang/Character;" -> "java.lang.Character";
            case "Ljava/lang/Long;" -> "java.lang.Long";
            case "Ljava/lang/Float;" -> "java.lang.Float";
            case "Ljava/lang/Double;" -> "java.lang.Double";
            case "Ljava/lang/Boolean;" -> "java.lang.Boolean";
            // Object en de box-implementerende interfaces: de primitieve
            // waarde bepaalt de wrapper ((Object) boolean -> Boolean).
            case "Ljava/lang/Object;",
                 "Ljava/lang/Serializable;",
                 "Ljava/lang/Comparable;" -> wrapperForPrimitive(value.type());
            default -> null;
        };
        if (boxClass == null) {
            return value;
        }
        return builder.emitCall(
                CallKind.STATIC,
                boxClass.replace('.', '_') + "_valueOf",
                target,
                java.util.List.of(value.type()),
                java.util.List.of(value)
        );
    }

    /** De wrapper-klasse voor een primitief IR-type (boxing via valueOf). */
    private static String wrapperForPrimitive(final IRType type) {
        if (type instanceof IRType.Bool) return "java.lang.Boolean";
        if (type instanceof IRType.Int i) {
            return switch (i.bits()) {
                case 8 -> "java.lang.Byte";
                case 16 -> "java.lang.Short";
                case 64 -> "java.lang.Long";
                default -> "java.lang.Integer";
            };
        }
        if (type instanceof IRType.Float f) {
            return f.bits() == 64 ? "java.lang.Double" : "java.lang.Float";
        }
        return null;
    }

    @Override
    public IRValue visitArrayAccess(ArrayAccessExpressionTree arrayAccess,
                                    IRBuilder param) {
        IRValue array = acceptTree(arrayAccess.getExpression(), builder);
        IRValue index = acceptTree(arrayAccess.getIndex(), builder);

        if (array != null) {
            // Het elementtype komt bij voorkeur uit het statische type van de
            // array-access-expressie. Voor geneste arrays (Text[][]) is de
            // pointee van het IR-type namelijk een generieke I8 in plaats van
            // het werkelijke elementtype (Text). In dat laatste geval wordt
            // het componenttype van de array-expressie gebruikt, en als
            // fallback een opaque Ptr (de JVM ziet elk referentie-array-
            // element als een objectreferentie, nooit als kale I8).
            IRType elemType = arrayAccess.getType() != null
                    ? TypeMirrorToIRType.map(arrayAccess.getType())
                    : null;
            if (elemType == null && arrayAccess.getExpression().getType() instanceof io.github.potjerodekool.nabu.type.ArrayType arrType) {
                elemType = TypeMirrorToIRType.map(arrType.getComponentType());
            }
            if (elemType == null && array.type() instanceof IRType.Ptr ptrType
                    && ptrType.pointee() instanceof IRType.Ptr) {
                elemType = ptrType.pointee();
            }
            if (elemType == null) {
                elemType = new IRType.Ptr(IRType.I8);
            }
            if (elemType != null) {
                return builder.emitArrayLoad(array, index, elemType);
            }
        }
        return null;
    }

    // -------------------------------------------------------
    // Hulpmethoden
    // -------------------------------------------------------

    /**
     * Slaat een waarde terug op in de variabele die door een expressie
     * wordt aangeduid (voor assignments en post-increment/decrement).
     */
    private void emitStoreBack(ExpressionTree target, IRValue value) {
        if (value == null) {
            // De waarde kon niet geemitteerd worden (bv. een methode-
            // aanroep met een onopgeloste methodType); dan blijft de
            // variabele op zijn default staan ├â┬ó├óÔÇÜ┬¼├óÔé¼┬Ø in plaats van een
            // Store[ptr,null]-instructie die de verifier/SSA breekt.
            return;
        }
        if (target instanceof IdentifierTree id) {
            String name = id.getName();
            var ptr = scope.lookup(name);
            if (ptr.isPresent()) {
                // Een lokale slot is een pointer naar de variabele: box tegen
                // het variabeletype (`Boolean b; b = false;`), niet tegen de slot.
                final IRType slotType = ptr.get().type();
                final IRType variableType = slotType instanceof IRType.Ptr slotPtr
                        && slotPtr.jvmDescriptor() == null && slotPtr.pointee() != null
                        ? slotPtr.pointee() : slotType;
                builder.emitStore(ptr.get(), boxIfNeeded(value, variableType));
            } else if (id.getSymbol() instanceof VariableElement variableSymbol
                    && variableSymbol.isStatic()) {
                // Statisch veld buiten de lokale scopes (bv. $VALUES uit de
                // enum-lowering): store via het veld-symbol. Een primitieve
                // waarde voor een wrapper-veld boxen (picocli's
                // `jansiInstalled = calcIsJansiConsoleInstalled()`); zonder dat
                // viel de store weg en gaf de unboxing erna een NPE.
                final IRValue.Named staticField = resolveField(variableSymbol);
                builder.emitStore(staticField, boxIfNeeded(value, staticField.type()));
            } else if (id.getSymbol() instanceof VariableElement fieldSymbol
                    && fieldSymbol.getKind().isField()) {
                // Instantieveld zonder expliciete `this.` (`type = ...`): store
                // via this, het spiegelbeeld van de load in visitIdentifier.
                // Zonder deze tak verdween de toewijzing geruisloos en bleef
                // het veld op zijn default staan.
                final IRValue receiver = implicitFieldReceiver(fieldSymbol);
                if (receiver != null) {
                    emitFieldStore(receiver, fieldSymbol, id, value);
                }
            }
        } else if (target instanceof FieldAccessExpressionTree fieldAccess) {
            // Veld-toewijzing
            ExpressionTree selected = fieldAccess.getSelected();
            IdentifierTree field = fieldAccess.getField();

            Element fieldSymbol = (field instanceof CIdentifierTree ci)
                    ? ci.getSymbol() : null;

            if (fieldSymbol != null && fieldSymbol.isStatic()) {
                String globalName = fieldSymbol.getEnclosingElement().getSimpleName()
                        + "_" + field.getName();

                IRValue globalPtr = resolveField(fieldSymbol);

                //IRValue globalPtr = builder.lookup(globalName);
                if (globalPtr != null) {
                    // Boxing bij toewijzing aan een statisch veld van een
                    // wrapper-type (`static final Boolean X = true`).
                    builder.emitStore(globalPtr, boxIfNeeded(value, globalPtr.type()));
                }
            } else if (selected != null) {
                IRValue obj = acceptTree(selected, builder);
                if (obj != null && fieldSymbol != null) {
                    emitFieldStore(obj, fieldSymbol, field, value);
                }
            }
        } else if (target instanceof ArrayAccessExpressionTree arrayAccess) {
            IRValue array = acceptTree(arrayAccess.getExpression(), builder);
            IRValue index = acceptTree(arrayAccess.getIndex(), builder);

            if (array != null && array.type() instanceof IRType.Ptr ptrType) {
                IRType elemType = ptrType.pointee();
                builder.emitArrayStore(array, index, boxIfNeeded(value, elemType), elemType);
            }
        }
    }

    private IRValue.Named resolveField(final Element fieldSymbol) {
        return new IRValue.Named(
                fieldSymbol.getSimpleName(),
                TypeMirrorToIRType.map(fieldSymbol.asType()),
                TypeMirrorToIRType.map(fieldSymbol.getEnclosingElement().asType()),
                fieldSymbol.isStatic()
        );
    }

    /**
     * Emitteert compound assignments (+=, -=, etc.) als load + op + store.
     */
    private IRValue emitCompoundAssignment(BinaryExpressionTree binaryExpr) {
        IRValue current = acceptTree(binaryExpr.getLeft(), builder);
        IRValue right = acceptTree(binaryExpr.getRight(), builder);
        right = unboxIfNeeded(right, current.type());
        Op op = TagToIROp.compoundAssignmentOp(binaryExpr.getTag());
        IRValue result = builder.emitBinaryOp(op, current, right);
        emitStoreBack(binaryExpr.getLeft(), result);
        return result;
    }

    /**
     * Het breedte-scorend achterhaald unboxing: een wrapper-waarde
     * (Integer/Long/Double/...) wordt omgezet naar de primitieve
     * operatie-toevoeging via intValue() etc. ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø anders volgt de
     * BinOp-emitter een String-concat door de Ptr-operand.
     */
    private IRValue unboxIfNeeded(final IRValue value, final IRType targetType) {
        if (value == null
                || !(value.type() instanceof IRType.Ptr ptr)
                || ptr.jvmDescriptor() == null
                || !(targetType instanceof IRType.Int || targetType instanceof IRType.Float
                || targetType instanceof IRType.Bool)) {
            return value;
        }

        final var desc = ptr.jvmDescriptor();
        final String unboxMethod = switch (desc) {
            case "Ljava/lang/Integer;" -> "intValue";
            case "Ljava/lang/Long;" -> "longValue";
            case "Ljava/lang/Short;" -> "shortValue";
            case "Ljava/lang/Byte;" -> "byteValue";
            case "Ljava/lang/Character;" -> "charValue";
            case "Ljava/lang/Float;" -> "floatValue";
            case "Ljava/lang/Double;" -> "doubleValue";
            case "Ljava/lang/Boolean;" -> "booleanValue";
            default -> null;
        };
        if (unboxMethod == null) {
            return value;
        }

        final IRType unboxedType;
        if (desc.equals("Ljava/lang/Long;")) unboxedType = IRType.I64;
        else if (desc.equals("Ljava/lang/Double;")) unboxedType = IRType.F64;
        else if (desc.equals("Ljava/lang/Float;")) unboxedType = IRType.F32;
        else if (desc.equals("Ljava/lang/Boolean;")) unboxedType = IRType.BOOL;
        else if (desc.equals("Ljava/lang/Character;")) unboxedType = IRType.I32;
        else if (desc.equals("Ljava/lang/Integer;")
                || desc.equals("Ljava/lang/Short;")
                || desc.equals("Ljava/lang/Byte;")) unboxedType = IRType.I32;
        else return value;

        return builder.emitCall(
                CallKind.VIRTUAL,
                "java.lang." + desc.substring(11, desc.length() - 1) + "_" + unboxMethod,
                unboxedType,
                List.of(),
                List.of(value)
        );
    }

    /**
     * Laadt een veld van een object.
     * Vereist GEP ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø veldindex wordt opgezocht via het Element.
     * Voorlopig: laad als opaque pointer (veldoffsets worden bepaald bij codegen).
     */
    private IRValue emitFieldLoad(IRValue obj, Element fieldSymbol) {
        final var ownerType = TypeMirrorToIRType.map(fieldSymbol.getEnclosingElement().asType());
        final var fieldIndex = computeFieldIndex(fieldSymbol);

        return new IRValue.Values(
                obj,
                new IRValue.Named(
                        fieldSymbol.getSimpleName(),
                        TypeMirrorToIRType.map(fieldSymbol.asType()),
                        ownerType,
                        fieldSymbol.isStatic(),
                        fieldIndex
                )
        );
    }

    /**
     * Slaat een waarde op in een veld van een object.
     */
    private void emitFieldStore(final IRValue obj,
                                final Element fieldSymbol,
                                final IdentifierTree field,
                                final IRValue value) {
        final var ownerType = TypeMirrorToIRType.map(fieldSymbol.getEnclosingElement().asType());
        final var fieldIndex = computeFieldIndex(fieldSymbol);

        final var named = new IRValue.Named(
                fieldSymbol.getSimpleName(),
                TypeMirrorToIRType.map(fieldSymbol.asType()),
                ownerType,
                fieldSymbol.isStatic(),
                fieldIndex
        );
        // Primitief in een wrapper-veld (`Integer f; f = intValue;`) boxen;
        // anders kwam de kale int in het veld en crashte de unboxing erna.
        builder.emitStore(new IRValue.Values(obj, named), boxIfNeeded(value, named.type()));
    }

    private int computeFieldIndex(final Element fieldSymbol) {
        final var ownerElement = fieldSymbol.getEnclosingElement();
        if (ownerElement instanceof TypeElement typeElement) {
            // Een niet-statische member class krijgt het synthetische veld
            // `this$0` als EERSTE moduleveld (zie visitClass); de veldindex telt
            // de moduleveld-volgorde. Zonder deze verschuiving lag elke index
            // één te laag: picocli's Interpreter.clear() schreef
            // isHelpRequested over converterRegistry heen.
            int index = needsOuterInstance(typeElement) ? 1 : 0;
            for (final var enclosed : typeElement.getEnclosedElements()) {
                if (enclosed.getKind().isField()) {
                    if (enclosed.getSimpleName().equals(fieldSymbol.getSimpleName())) {
                        return index;
                    }
                    index++;
                }
            }
        }
        return -1;
    }

    @Override
    public IRValue visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final IRBuilder param) {
        final var value = acceptTree(instanceOfExpression.getExpression(), param);
        return builder.emitInstanceOf(value, TypeMirrorToIRType.map(patternInstanceOfType(instanceOfExpression)));
    }

    @Override
    public IRValue visitConditionalExpression(final ConditionalExpressionTree conditionalExpression,
                                              final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                conditionalExpression.getLineNumber(),
                conditionalExpression.getColumnNumber()
        );

        // De conditie wordt pas onderaan als sprongketen geëmitteerd
        // (emitConditionBranch), vanuit dit blok.
        final var condBlk = builder.currentBlock();
        final var trueBlk = builder.beginBlock("ternary.then");
        final IRValue trueValue = acceptTree(conditionalExpression.getTrueExpression(), builder);
        final var trueTailBlk = builder.currentBlock();
        final var falseBlk = builder.beginBlock("ternary.else");
        final IRValue falseValue = acceptTree(conditionalExpression.getFalseExpression(), builder);
        final var falseTailBlk = builder.currentBlock();
        final var mergeBlk = builder.beginBlock("ternary.merge");

        // Resultaattype: de statische types van de resolver hebben voorrang,
        // daarna het resolver-type van de hele expressie, en tenslotte het
        // gemeenschappelijke type van de daadwerkelijk gegenereerde tak-waarden
        // (nodig voor null-/nieuw-expressies waarvan het statische type
        // ontbreekt: anders wordt de cel I32 en smeert de phi-merge een
        // Ptr/Int-combinatie die de bytecode-verifier afwijst).
        final var staticTrueType = staticIRTypeOf(conditionalExpression.getTrueExpression());
        final var staticFalseType = staticIRTypeOf(conditionalExpression.getFalseExpression());
        var resultType = unionTernaryTypes(staticTrueType, staticFalseType);
        if (resultType == null) {
            resultType = conditionalExpression.getType() != null
                    && !isNullLiteralType(conditionalExpression.getType())
                    ? TypeMirrorToIRType.map(conditionalExpression.getType())
                    : null;
        }
        if (resultType == null) {
            resultType = commonValueType(trueValue, falseValue);
        }
        if (resultType == null) {
            resultType = IRType.I32;
        }

        // beginBlock heeft de cursor op merge gezet; de cella en condBranch
        // horen in het conditieblok.
        builder.setCurrentBlock(condBlk);
        final var resultCell = builder.emitAlloca("ternary." + ternaryCounter++, resultType);
        emitConditionBranch(conditionalExpression.getCondition(), trueBlk, falseBlk);

        // True-tak
        builder.setCurrentBlock(trueTailBlk);
        if (trueValue != null) {
            builder.emitStore(resultCell, coerceToTernaryCell(trueValue, pointeeOf(resultCell.type())));
        }
        closeBlock(trueTailBlk, mergeBlk);

        // False-tak
        builder.setCurrentBlock(falseTailBlk);
        if (falseValue != null) {
            builder.emitStore(resultCell, coerceToTernaryCell(falseValue, pointeeOf(resultCell.type())));
        }
        closeBlock(falseTailBlk, mergeBlk);

        // Merge: laad het resultaat
        builder.setCurrentBlock(mergeBlk);
        return builder.emitLoad(resultCell);
    }

    /**
     * Gemeenschappelijk type van twee gegenereerde tak-waarden; gebruikt de
     *zelfde commonType-logica als de IR-type-inferentie. Valt terug op de
     * type van de niet-null-waarde als ├®├®n van beide null is. Twee Ptr-waarden
     * met ongelijke pointees (bv. een Ptr(I8) null-literal en een genest
     * Ptr(Ptr)-heapalloc) worden genormaliseerd naar een opaque referentie-cel,
     * want op bytecode-niveau is elk Ptr een referentie-slot.
     */
    private static IRType commonValueType(final IRValue a, final IRValue b) {
        if (a == null) {
            return b != null ? b.type() : null;
        }
        if (b == null) {
            return a.type();
        }
        final var common = TypeInference.commonType(a.type(), b.type());
        if (common != null) {
            return common;
        }
        if (a.type() instanceof IRType.Ptr || b.type() instanceof IRType.Ptr) {
            return new IRType.Ptr(IRType.I8);
        }
        return null;
    }

    /**
     * Bepaalt het IR-type van een tak-expressie statisch (alleen via het
     * resolver-type), zonder instructies te emitten.
     */
    private IRType staticIRTypeOf(final ExpressionTree expression) {
        if (expression == null) {
            return null;
        }
        final var type = expression.getType();
        if (type == null || isNullLiteralType(type) || isUnresolvedType(type)) {
            return null;
        }
        if (type.asElement() != null && type.isError()) {
            return null;
        }
        return TypeMirrorToIRType.map(type);
    }

    /**
     * Herkent het type van een null-literal: alleen typekind NULL.
     * Een primitief type (bv. int) heeft geen element, maar is g├âãÆ├åÔÇÖ├âÔÇÜ├é┬®├âãÆ├åÔÇÖ├âÔÇÜ├é┬®n
     * null-literal en wordt dus niet hierdoor herkend.
     */
    private boolean isNullLiteralType(final TypeMirror type) {
        return type != null && type.getKind() == TypeKind.NULL;
    }

    private boolean isUnresolvedType(final TypeMirror type) {
        return type instanceof io.github.potjerodekool.nabu.type.VariableType
                && ((io.github.potjerodekool.nabu.type.VariableType) type).getInterferedType() == null;
    }

    /**
     * Bepaalt een gemeenschappelijk IR-type voor de twee takken van een
     * conditionele expressie. Bij een String- en een primitieve tak wordt de
     * String gekozen (de primitieve tak wordt later via String.valueOf
     * omgezet, conform string-concat-semantiek). Bij andere mismatches wordt
     * null gegeven zodat de resolver-type kan worden gebruikt.
     */
    private IRType unionTernaryTypes(final IRType trueType,
                                     final IRType falseType) {
        if (trueType == null) {
            return falseType;
        }
        if (falseType == null) {
            return trueType;
        }
        if (trueType.equals(falseType)) {
            return trueType;
        }
        if (isStringType(trueType) && !isStringType(falseType)
                && isPrimitiveIRType(falseType)) {
            return trueType;
        }
        if (isStringType(falseType) && !isStringType(trueType)
                && isPrimitiveIRType(trueType)) {
            return falseType;
        }
        // JLS 5.6.2 numeric conditional: primitive + wrapper get integrated
        // in een unboxende primitieve (de wrapper-arm wordt geunboxt in de
        // cel ├â┬ó├óÔÇÜ┬¼├óÔé¼┬Ø coerceToTernaryCell).
        if (isPrimitiveIRType(trueType)
                && isWrapperDescriptorOf(falseType, trueType)) {
            return trueType;
        }
        if (isPrimitiveIRType(falseType)
                && isWrapperDescriptorOf(trueType, falseType)) {
            return falseType;
        }
        return null;
    }

    private static boolean isWrapperDescriptorOf(final IRType primitiveType,
                                                 final IRType referenceType) {
        if (referenceType == null
                || !(referenceType instanceof IRType.Ptr ptr)
                || ptr.jvmDescriptor() == null) {
            return false;
        }
        final var suffix = ptr.jvmDescriptor();
        if (primitiveType instanceof IRType.Int(int bits)) {
            final var compatible = switch (bits) {
                case 8 -> Set.of("Ljava/lang/Byte;", "Ljava/lang/Character;",
                        "Ljava/lang/Short;", "Ljava/lang/Integer;");
                case 16 -> Set.of("Ljava/lang/Short;", "Ljava/lang/Character;",
                        "Ljava/lang/Integer;");
                case 64 -> Set.of("Ljava/lang/Long;", "Ljava/lang/Integer;");
                default -> Set.of("Ljava/lang/Integer;");
            };
            return compatible.contains(suffix);
        }
        if (primitiveType instanceof IRType.Float(int bits)) {
            return suffix.equals(bits == 32 ? "Ljava/lang/Float;" : "Ljava/lang/Double;");
        }
        return suffix.equals("Ljava/lang/Boolean;");
    }

    private static boolean isNumericUnboxMethod(final String methodName) {
        return switch (methodName) {
            case "intValue", "longValue", "shortValue", "byteValue",
                    "charValue", "floatValue", "doubleValue", "booleanValue" -> true;
            default -> false;
        };
    }

    private static String wrapperForUnbox(final String methodName) {
        return switch (methodName) {
            case "intValue", "doubleValue", "floatValue", "longValue",
                    "shortValue", "byteValue" -> "Integer";
            case "charValue" -> "Character";
            case "booleanValue" -> "Boolean";
            default -> "";
        };
    }

    private static IRType unboxPrimitiveType(final String methodName) {
        return switch (methodName) {
            case "longValue" -> IRType.I64;
            case "doubleValue" -> IRType.F64;
            case "floatValue" -> IRType.F32;
            case "booleanValue" -> IRType.BOOL;
            default -> IRType.I32;
        };
    }

    private IRValue charToString(final ExpressionTree expression, final IRValue value) {
        final TypeMirror type = expression.getType();
        if (type == null || type.getKind() != TypeKind.CHAR || value.type() instanceof IRType.Ptr) {
            return value;
        }
        return builder.emitCall(CallKind.STATIC, "java.lang.String_valueOf",
                new IRType.Ptr(IRType.I8, "Ljava/lang/String;"), List.of(value.type()), List.of(value));
    }

    private static boolean isStringType(final IRType type) {
        return type instanceof IRType.Ptr ptr
                && ptr.pointee() == IRType.I8
                && "Ljava/lang/String;".equals(ptr.jvmDescriptor());
    }

    private static boolean isPrimitiveIRType(final IRType type) {
        return type instanceof IRType.Int
                || type instanceof IRType.Float
                || type instanceof IRType.Bool;
    }

    /**
     * Zet een tak-waarde om naar het type van de ternary-cel. Een primitieve
     * tak naar een String-cel wordt via String.valueOf gestringificeerd;
     * anders wordt een box/unbox-conversie geprobeerd.
     */
    private IRValue coerceToTernaryCell(final IRValue value,
                                        final IRType cellType) {
        if (value == null || cellType == null) {
            return value;
        }
        final IRType elemType = cellType;
        final boolean cellIsString = isStringType(elemType);
        final boolean valIsPrim = isPrimitiveIRType(value.type());
        if (cellIsString && valIsPrim) {
            return builder.emitCall(
                    CallKind.STATIC,
                    "java.lang.String_valueOf",
                    elemType,
                    java.util.List.of(value.type()),
                    java.util.List.of(value)
            );
        }
        final var boxed = boxIfNeeded(value, elemType);
        if (boxed != value) {
            return boxed;
        }
        final var unboxed = unboxIfNeeded(boxed, elemType);
        // Int-breedte uitlijnen (bv. Character├â┬ó├óÔé¼┬á├óÔé¼Ôäóchar i32 vs een i16-cel):
        // de stack(aqwa)-representatie moet exact het cel-type matchen,
        // anders smeert de phi-merge een Ptr/Int-combinatie.
        if (unboxed.type() instanceof IRType.Int srcInt
                && elemType instanceof IRType.Int cellInt
                && srcInt.bits() != cellInt.bits()) {
            return builder.emitCast(unboxed, elemType);
        }
        return unboxed;
    }

    /** Haalt het elementtype uit een cel-adres (een Ptr naar het elementtype). */
    private static IRType pointeeOf(final IRType type) {
        return type instanceof IRType.Ptr p ? p.pointee() : type;
    }

    @Override
    public IRValue visitNewArray(final NewArrayExpression newArrayExpression, final IRBuilder param) {
        var arrayType = newArrayExpression.getType();

        if (arrayType == null || arrayType.getKind() == io.github.potjerodekool.nabu.type.TypeKind.VOID) {
            final var elemTypeTree = newArrayExpression.getElementType();
            if (elemTypeTree != null) {
                final var elemType = elemTypeTree.getType();
                if (elemType != null) {
                    final var componentIRType = TypeMirrorToIRType.map(elemType);
                    final var descriptor = TypeMirrorToIRType.toJvmDescriptor(elemType);
                    arrayType = null;
                    final IRType objectType = new IRType.Ptr(componentIRType, descriptor);
                    return emitNewArrayBody(newArrayExpression, objectType);
                }
            }
        }

        final IRType objectType = TypeMirrorToIRType.map(arrayType);
        return emitNewArrayBody(newArrayExpression, objectType);
    }

    private IRValue emitNewArrayBody(final NewArrayExpression newArrayExpression,
                                     final IRType objectType) {
        final var dimensions = newArrayExpression.getDimensions();

        if (dimensions.isEmpty()) {
            final var elements = newArrayExpression.getElements();
            final int elemCount = (elements != null) ? elements.size() : 0;
            final var arrayPtr = builder.emitAllocaArray(elemCount, objectType);

            if (elements != null && !elements.isEmpty()) {
                final var componentType = switch (objectType) {
                    case IRType.Ptr p -> p.pointee();
                    case IRType.Array a -> a.elem();
                    case IRType t -> t;
                };

                for (int i = 0; i < elements.size(); i++) {
                    final IRValue elemValue = acceptTree(elements.get(i), builder);
                    if (elemValue != null) {
                        builder.emitArrayStore(arrayPtr, IRValue.ofI32(i), elemValue, componentType);
                    }
                }
            }

            return arrayPtr;
        }

        final var firstDim = dimensions.getFirst();
        IRValue size = acceptTree(firstDim, builder);

        if (size == null && firstDim instanceof LiteralExpressionTree lit) {
            size = IRValue.ofI32(((Number) lit.getLiteral()).intValue());
        }

        // Een niet-constante grootte (`new String[names.length + 1]`) is een
        // runtime-waarde; die werd hier eerder als 0 genomen, waardoor de
        // array leeg was en elke store erna buiten de allocatie schreef
        // (picocli's OptionSpec.builder(name, names...) gaf "Invalid names: []").
        final IRValue arrayPtr = size instanceof IRValue.ConstInt ci
                ? builder.emitAllocaArray((int) ci.value(), objectType)
                : size != null
                        ? builder.emitAllocaArray(size, objectType)
                        : builder.emitAllocaArray(0, objectType);

        // Expliciete elementen (bv. new Status[]{ ON, OFF }): per element wegschrijven.
        final var elements = newArrayExpression.getElements();
        if (elements != null && !elements.isEmpty()) {
            final var componentType = switch (objectType) {
                case IRType.Ptr p -> p.pointee();
                case IRType.Array a -> a.elem();
                case IRType t -> t;
            };

            for (int i = 0; i < elements.size(); i++) {
                final IRValue elemValue = acceptTree(elements.get(i), builder);
                if (elemValue != null) {
                    builder.emitArrayStore(arrayPtr, IRValue.ofI32(i), elemValue, componentType);
                }
            }
        }

        return arrayPtr;
    }

    @Override
    public IRValue visitThrowStatement(final ThrowStatement throwStatement, final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                throwStatement.getLineNumber(),
                throwStatement.getColumnNumber()
        );

        final var expression = throwStatement.getExpression();
        final TypeMirror throwType;

        if (expression instanceof NewClassExpression newClassExpression) {
            throwType = newClassExpression.getName().getType();
        } else {
            throwType = expression.getType();
        }

        final var type = TypeMirrorToIRType.map(throwType);

        // Evalueer de expressie. Voor een `new X()` wordt het exception-object
        // gealloceerd en de constructor aangeroepen (net als gewone objecten),
        // zodat de backend een echt object kan gooien in plaats van een lege
        // placeholder.
        final IRValue exValue = acceptTree(expression, builder);

        if (exValue != null) {
            builder.emitThrow(exValue, type);
        } else {
            // Fallback: geen beschikbare waarde -> de backend alloceert een
            // placeholder van het gegooid type (result==null).
            builder.emitThrow(type);
        }
        return null;
    }

    @Override
    public IRValue visitParenthesizedExpression(final ParenthesizedExpression parenthesizedExpression, final IRBuilder param) {
        return acceptTree(parenthesizedExpression.getExpression(), param);
    }

    // -------------------------------------------------------
    // Break / Continue
    // -------------------------------------------------------

    @Override
    public IRValue visitBreakStatement(final BreakStatement breakStatement, final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                breakStatement.getLineNumber(),
                breakStatement.getColumnNumber()
        );

        final var target = breakStatement.getTarget();
        IRBasicBlock targetBlock;

        if (target == null) {
            if (breakTargets.isEmpty()) {
                throw new RuntimeException(new CompileException("'break' buiten een lus of switch"));
            }
            targetBlock = breakTargets.peek();
        } else {
            String labelName = resolveLabelTarget(target);
            targetBlock = labeledBreakTargets.get(labelName);
            if (targetBlock == null) {
                throw new RuntimeException(new CompileException("Onbekend label voor break: " + labelName));
            }
        }

        builder.emitBranch(targetBlock);
        return null;
    }

    @Override
    public IRValue visitContinueStatement(final ContinueStatement continueStatement, final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                continueStatement.getLineNumber(),
                continueStatement.getColumnNumber()
        );

        final var target = continueStatement.getTarget();
        IRBasicBlock targetBlock;

        if (target == null) {
            if (continueTargets.isEmpty()) {
                throw new RuntimeException(new CompileException("'continue' buiten een lus"));
            }
            targetBlock = continueTargets.peek();
        } else {
            String labelName = resolveLabelTarget(target);
            targetBlock = labeledContinueTargets.get(labelName);
            if (targetBlock == null) {
                throw new RuntimeException(new CompileException("Onbekend label voor continue: " + labelName));
            }
        }

        builder.emitBranch(targetBlock);
        return null;
    }

    // -------------------------------------------------------
    // Enhanced for
    // -------------------------------------------------------

    @Override
    public IRValue visitEnhancedForStatement(final EnhancedForStatementTree enhancedFor,
                                              final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                enhancedFor.getLineNumber(),
                enhancedFor.getColumnNumber()
        );

        // Uitvouwen naar een gewone for-lus:
        //   var __arr = <collection>;
        //   for (int __i = 0; __i < __arr.length; __i++) {
        //       var <var> = __arr[__i];
        //       <body>
        //   }

        IRValue collection = acceptTree(enhancedFor.getExpression(), builder);
        if (collection == null) return null;

        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock condBlk = builder.beginBlock("efor.cond");
        IRBasicBlock bodyBlk = builder.beginBlock("efor.body");
        IRBasicBlock updateBlk = builder.beginBlock("efor.update");
        IRBasicBlock exitBlk = builder.beginBlock("efor.exit");

        breakTargets.push(exitBlk);
        continueTargets.push(updateBlk);
        registerLabeledContinueTargets(updateBlk);

        // beginBlock heeft de cursor op efor.exit gezet; emissions moeten in
        // het entry-blok blijven (zelfde patroon als de klassieke for-lus).
        builder.setCurrentBlock(entryBlk);

        // Array-variabele alloceren
        IRType collectionType = collection.type();
        IRValue arrayPtr;
        if (collectionType instanceof IRType.Ptr ptrType) {
            arrayPtr = builder.emitAlloca("__arr", ptrType);
            builder.emitStore(arrayPtr, collection);
        } else {
            arrayPtr = builder.emitAlloca("__arr", collectionType);
            builder.emitStore(arrayPtr, collection);
        }

        // Index-variabele
        IRValue indexPtr = builder.emitAlloca("__i", IRType.I32);
        builder.emitStore(indexPtr, IRValue.ofI32(0));

        builder.emitBranch(condBlk);

        // Conditie: __i < __arr.length
        builder.setCurrentBlock(condBlk);
        IRValue indexVal = builder.emitLoad(indexPtr);
        IRValue arrayVal = builder.emitLoad(arrayPtr);
        IRValue lengthVal = builder.emitArrayLength(arrayVal);

        IRValue cond = builder.emitBinaryOp(Op.LT, indexVal, lengthVal);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        // Body
        builder.setCurrentBlock(bodyBlk);
        scope.pushScope();

        // Variabele laden
        VariableDeclaratorTree localVar = enhancedFor.getLocalVariable();
        TypeMirror varType = localVar.getVariableType().getType();
        IRType irVarType = TypeMirrorToIRType.map(varType);
        String varName = localVar.getName().getName();

        IRValue elemPtr = builder.emitAlloca(varName, irVarType);

        if (elemPtr instanceof IRValue.Temp allocaTemp) {
            builder.currentFunction().addLocalVariable(
                    new IRFunction.LocalVar(varName, irVarType, allocaTemp)
            );
        }

        IRValue bodyArrayVal = builder.emitLoad(arrayPtr);
        IRValue elemVal = builder.emitArrayLoad(bodyArrayVal, indexVal, irVarType);
        builder.emitStore(elemPtr, elemVal);
        scope.define(varName, elemPtr);

        acceptTree(enhancedFor.getStatement(), builder);
        scope.popScope();

        if (!builder.currentBlockTerminated())
            builder.emitBranch(updateBlk);

        // Update: __i++
        builder.setCurrentBlock(updateBlk);
        IRValue curIdx = builder.emitLoad(indexPtr);
        IRValue one = IRValue.ofI32(1);
        IRValue newIdx = builder.emitBinaryOp(Op.ADD, curIdx, one);
        builder.emitStore(indexPtr, newIdx);
        builder.emitBranch(condBlk);

        breakTargets.pop();
        continueTargets.pop();

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    // -------------------------------------------------------
    // Switch
    // -------------------------------------------------------

    @Override
    public IRValue visitSwitchStatement(final SwitchStatement switchStatement,
                                         final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                switchStatement.getLineNumber(),
                switchStatement.getColumnNumber()
        );

        IRValue selector = acceptTree(switchStatement.getSelector(), builder);
        if (selector == null) return null;

        List<CaseStatement> cases = switchStatement.getCases();

        // Stap 1: Maak alle blokken aan
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock exitBlk = builder.beginBlock("switch.exit");

        breakTargets.push(exitBlk);

        List<IRBasicBlock> cmpBlocks = new ArrayList<>();
        List<IRBasicBlock> caseBodyBlocks = new ArrayList<>();
        IRBasicBlock defaultBlock = null;

        for (CaseStatement caseStmt : cases) {
            IRBasicBlock cmpBlk = builder.beginBlock("switch.cmp");
            cmpBlocks.add(cmpBlk);
            IRBasicBlock caseBlk = builder.beginBlock("switch.case");
            caseBodyBlocks.add(caseBlk);

            if (caseStmt.getLabels().stream().anyMatch(l -> l instanceof DefaultCaseLabel)) {
                defaultBlock = caseBlk;
            }
        }

        // Stap 2: Entry blok ├âãÆ├é┬ó├â┬ó├óÔÇÜ┬¼├é┬á├â┬ó├óÔÇÜ┬¼├óÔÇ×┬ó eerste vergelijkingsblok
        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(cmpBlocks.get(0));

        // Stap 3: Vergelijkingsblokken invullen
        for (int i = 0; i < cases.size(); i++) {
            CaseStatement caseStmt = cases.get(i);
            IRBasicBlock cmpBlk = cmpBlocks.get(i);
            IRBasicBlock caseBlk = caseBodyBlocks.get(i);
            IRBasicBlock nextCmpBlk = (i + 1 < cases.size()) ? cmpBlocks.get(i + 1) : null;

            builder.setCurrentBlock(cmpBlk);

            boolean isDefault = caseStmt.getLabels().stream()
                    .anyMatch(l -> l instanceof DefaultCaseLabel);

            if (isDefault) {
                // Default: spring altijd naar het case-blok
                builder.emitBranch(caseBlk);
            } else {
                // Vergelijkingen voor elke constante label
                IRValue match = null;
                for (CaseLabel label : caseStmt.getLabels()) {
                    if (label instanceof ConstantCaseLabel constLabel) {
                        IRValue caseValue = acceptTree(constLabel.getExpression(), builder);
                        if (caseValue != null) {
                            IRValue eq = builder.emitBinaryOp(Op.EQ, selector, caseValue);
                            match = (match == null)
                                    ? eq
                                    : builder.emitBinaryOp(Op.OR, match, eq);
                        }
                    }
                }

                if (match != null) {
                    builder.emitCondBranch(match, caseBlk,
                            nextCmpBlk != null ? nextCmpBlk : (defaultBlock != null ? defaultBlock : exitBlk));
                } else {
                    builder.emitBranch(nextCmpBlk != null ? nextCmpBlk :
                            (defaultBlock != null ? defaultBlock : exitBlk));
                }
            }
        }

        // Stap 4: Case-body blokken invullen
        for (int i = 0; i < cases.size(); i++) {
            CaseStatement caseStmt = cases.get(i);
            IRBasicBlock caseBlk = caseBodyBlocks.get(i);
            builder.setCurrentBlock(caseBlk);

            Tree body = caseStmt.getBody();
            if (body instanceof BlockStatementTree block) {
                acceptTree(block, builder);
            } else if (body != null) {
                acceptTree(body, builder);
            }

            // Fall-through: als het blok niet be├âãÆ├åÔÇÖ├âÔÇÜ├é┬½indigd is
            if (!builder.currentBlockTerminated()) {
                if (caseStmt.getCaseKind() == CaseStatement.CaseKind.RULE) {
                    // Rule-cases vallen niet door
                    builder.emitBranch(exitBlk);
                } else if (i + 1 < cases.size()) {
                    // Statement-case: val door naar het volgende case-blok
                    builder.emitBranch(caseBodyBlocks.get(i + 1));
                } else {
                    builder.emitBranch(exitBlk);
                }
            }
        }

        breakTargets.pop();
        builder.setCurrentBlock(exitBlk);
        return null;
    }

    // -------------------------------------------------------
    // Assert
    // -------------------------------------------------------

    @Override
    public IRValue visitAssertStatement(final AssertStatement assertStatement,
                                         final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                assertStatement.getLineNumber(),
                assertStatement.getColumnNumber()
        );

        // Uitvouwen naar: if (!condition) throw new AssertionError(detail)

        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock passBlk = builder.beginBlock("assert.pass");
        IRBasicBlock failBlk = builder.beginBlock("assert.fail");
        IRBasicBlock mergeBlk = builder.beginBlock("assert.merge");

        IRValue cond = acceptTree(assertStatement.getCondition(), builder);
        builder.emitCondBranch(cond, passBlk, failBlk);

        // Fail: gooi AssertionError
        builder.setCurrentBlock(failBlk);
        builder.emitThrow(new IRType.Ptr(IRType.I8, "Ljava/lang/AssertionError;"));
        if (!builder.currentBlockTerminated()) {
            builder.emitBranch(mergeBlk);
        }

        // Pass
        builder.setCurrentBlock(passBlk);
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        return null;
    }

    // -------------------------------------------------------
    // Yield
    // -------------------------------------------------------

    @Override
    public IRValue visitYieldStatement(final YieldStatement yieldStatement,
                                        final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                yieldStatement.getLineNumber(),
                yieldStatement.getColumnNumber()
        );

        ExpressionTree expr = yieldStatement.getExpression();
        if (expr == null) {
            builder.emitReturn(null);
        } else {
            IRValue value = acceptTree(expr, builder);
            builder.emitReturn(value);
        }
        return null;
    }

    // -------------------------------------------------------
    // Labeled statement
    // -------------------------------------------------------

    @Override
    public IRValue visitLabeledStatement(final LabeledStatement labeledStatement,
                                          final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                labeledStatement.getLineNumber(),
                labeledStatement.getColumnNumber()
        );

        String label = labeledStatement.getLabel();

        // We kunnen geen forward-reference maken naar blokken die nog niet bestaan.
        // Los dit op door een placeholder te registreren die later wordt opgelost.
        // beginBlock zet de cursor op het nieuwe blok: terug naar het huidige,
        // de body hoort hier en het break-doel ná de body.
        final IRBasicBlock bodyEntry = builder.currentBlock();
        IRBasicBlock breakPlaceholder = builder.beginBlock("label." + label + ".break");
        builder.setCurrentBlock(bodyEntry);
        labeledBreakTargets.put(label, breakPlaceholder);

        // Markeer als pending label zodat de volgende lus de continue target kan instellen
        pendingLabels.push(label);

        StatementTree body = labeledStatement.getStatement();
        acceptTree(body, builder);

        // `break label` springt naar het blok ná de gelabelde statement.
        if (!builder.currentBlockTerminated()) {
            builder.emitBranch(breakPlaceholder);
        }
        builder.setCurrentBlock(breakPlaceholder);

        // Verwijder het pending label als de lus het niet heeft opgepakt
        pendingLabels.remove(label);
        labeledBreakTargets.remove(label);
        labeledContinueTargets.remove(label);

        return null;
    }

    // -------------------------------------------------------
    // Synchronized
    // -------------------------------------------------------

    @Override
    public IRValue visitSynchronizedStatement(final SynchronizedStatement synchronizedStatement,
                                               final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                synchronizedStatement.getLineNumber(),
                synchronizedStatement.getColumnNumber()
        );

        // Uitvouwen naar: monitorenter(__lock); <body>; monitorexit(__lock);

        IRValue lockObj = acceptTree(synchronizedStatement.getExpression(), builder);
        if (lockObj == null) return null;

        // monitorenter
        builder.emitMonitorEnter(lockObj);

        // Body
        acceptTree(synchronizedStatement.getBody(), builder);

        // monitorexit
        builder.emitMonitorExit(lockObj);

        return null;
    }

    // -------------------------------------------------------
    // Try-catch-finally
    // -------------------------------------------------------

    @Override
    public IRValue visitTryStatement(final TryStatementTree tryStatement,
                                      final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                tryStatement.getLineNumber(),
                tryStatement.getColumnNumber()
        );

        boolean hasFinally = tryStatement.getFinalizer() != null;

        IRBasicBlock prevBlk = builder.currentBlock();
        IRBasicBlock tryStartBlk = builder.beginBlock("try.body");

        // Branch vanuit het huidige blok naar de try-body
        if (prevBlk != null && !prevBlk.isTerminated()) {
            builder.setCurrentBlock(prevBlk);
            builder.emitBranch(tryStartBlk);
        }

        // Try body
        builder.setCurrentBlock(tryStartBlk);
        acceptTree(tryStatement.getBody(), builder);

        // try.end pas ná de body aanmaken: de backend beschermt de blokken
        // [try.body, try.end) in blokvolgorde. Blokken die de body zelf
        // aanmaakt (ternary, if, lus) kwamen anders ná try.end te staan, en
        // calls daarin liepen buiten het landingspad (picocli's
        // Range.parseInt: Integer.parseInt in een ternary binnen try ving zijn
        // NumberFormatException niet).
        IRBasicBlock bodyEndBlk = builder.currentBlock();
        IRBasicBlock tryEndBlk = builder.beginBlock("try.end");
        builder.setCurrentBlock(bodyEndBlk);
        if (!builder.currentBlockTerminated()) {
            builder.emitBranch(tryEndBlk);
        }

        // Catch handlers
        List<CatchTree> catchers = tryStatement.getCatchers();
        for (int i = 0; i < catchers.size(); i++) {
            CatchTree catcher = catchers.get(i);
            IRBasicBlock handlerBlk = builder.beginBlock("try.catch." + i);

            // TryCatchRegion metadata
            String exType = null;
            VariableDeclaratorTree catchVar = catcher.getVariable();
            if (catchVar != null && catchVar.getVariableType() != null) {
                TypeMirror exTypeMirror = catchVar.getVariableType().getType();
                if (exTypeMirror != null) {
                    exType = exTypeMirror.getClassName();
                }
            }

            tryCatchRanges.add(new TryCatchRange(
                    tryStartBlk.label(),
                    tryEndBlk.label(),
                    handlerBlk.label(),
                    exType
            ));

            // Emit TryCatchRegion instruction at start of handler block
            builder.setCurrentBlock(handlerBlk);
            handlerBlk.add(0, new IRInstruction.TryCatchRegion(
                    tryStartBlk.label(),
                    tryEndBlk.label(),
                    handlerBlk.label(),
                    exType,
                    builder.currentLocation()
            ));

            // Catch body
            scope.pushScope();
            acceptTree(catcher.getVariable(), builder);

            // Bind het gevangen exception-object aan de catch-variabele.
            // De backend plaatst het door nabu_catch() verkregen object in het
            // register "%exn.<handlerLabel>"; we storen dat register naar de
            // alloca van de catch-variabele.
            VariableDeclaratorTree catchVariable = catcher.getVariable();
            if (catchVariable != null && catchVariable.getName() != null) {
                String catchVarName = catchVariable.getName().getName();
                scope.lookup(catchVarName).ifPresent(catchPtr -> {
                    IRType catchDeclType = catchVariable.getVariableType() != null
                            ? TypeMirrorToIRType.map(catchVariable.getVariableType().getType())
                            : new IRType.Ptr(IRType.I8);
                    IRValue caught = new IRValue.Temp(
                            "%exn." + handlerBlk.label(),
                            catchDeclType);
                    builder.emitStore(catchPtr, caught);
                });
            }

            acceptTree(catcher.getBody(), builder);
            scope.popScope();

            if (!builder.currentBlockTerminated()) {
                builder.emitBranch(tryEndBlk);
            }
        }

        builder.setCurrentBlock(tryEndBlk);
        return null;
    }

    // -------------------------------------------------------
    // Lambda
    // -------------------------------------------------------

    @Override
    public IRValue visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                         final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                lambdaExpression.getLineNumber(),
                lambdaExpression.getColumnNumber()
        );

        ExecutableType methodType = lambdaExpression.getLambdaMethodType();
        if (methodType == null) return null;

        // LambdaToMethod heeft al een static method + Function aangemaakt
        // voor deze lambda. We verwijzen daar naar ipv een duplicaat te maken.
        final var methodSymbol = methodType.getMethodSymbol();
        final String lambdaMethodName = methodSymbol.getSimpleName();
        final var ownerElement = methodSymbol.getEnclosingElement();
        final String qualifiedClassName = ownerElement instanceof TypeElement typeElement
                ? typeElement.getQualifiedName()
                : currentClassName;
        final String fnName = qualifiedClassName + "." + lambdaMethodName;

        IRType returnType = TypeMirrorToIRType.mapReturnType(methodType.getReturnType());
        List<IRType> paramTypes = methodType.getParameterTypes().stream()
                .map(TypeMirrorToIRType::map)
                .toList();
        IRType.Function fnType = new IRType.Function(returnType, paramTypes);

        final var allParamNames = methodSymbol.getParameters().stream()
                .map(p -> p.getSimpleName())
                .toList();

        return new IRValue.FunctionRef(fnName, fnType, null, null, null, null, allParamNames);
    }

    private IRValue.FunctionRef wrapWithSamConversion(final IRValue.FunctionRef fnRef,
                                                      final TypeElement functionalInterface,
                                                      final io.github.potjerodekool.nabu.type.DeclaredType paramType) {
        final var samMethod = functionalInterface.findFunctionalMethod();
        if (samMethod == null) return fnRef;

        final var samDescriptor = TypeMirrorToIRType.toJvmDescriptor(samMethod.asType());
        final var samParamCount = samMethod.getParameters().size();

        final var instantiatedDescriptor = createInstantiatedSamDescriptor(samMethod, paramType);
        final var samMethodName = samMethod.getSimpleName();

        final var capturedVarCount = fnRef.fnType().paramTypes().size() - samParamCount;
        final var capturedVarNames = new ArrayList<String>();
        final var sourceNames = fnRef.capturedVarNames();
        for (int i = 0; i < capturedVarCount; i++) {
            if (sourceNames != null && i < sourceNames.size()) {
                capturedVarNames.add(sourceNames.get(i));
            } else {
                capturedVarNames.add("%" + i);
            }
        }

        final var interfaceInternalName = "L" + functionalInterface.getQualifiedName().replace('.', '/') + ";";

        return new IRValue.FunctionRef(
                fnRef.name(),
                fnRef.fnType(),
                new IRType.Ptr(IRType.I8, interfaceInternalName),
                samMethodName,
                samDescriptor,
                instantiatedDescriptor,
                capturedVarNames
        );
    }

    private String createInstantiatedSamDescriptor(final ExecutableElement samMethod,
                                                   final io.github.potjerodekool.nabu.type.DeclaredType paramType) {
        final var sb = new StringBuilder("(");
        final var typeArgs = paramType.getTypeArguments();

        for (int i = 0; i < samMethod.getParameters().size(); i++) {
            final var paramType2 = samMethod.getParameters().get(i).asType();
            if (paramType2 instanceof io.github.potjerodekool.nabu.type.TypeVariable tv) {
                final var bounds = tv.getUpperBound();
                sb.append(TypeMirrorToIRType.toJvmDescriptor(bounds));
            } else {
                sb.append(TypeMirrorToIRType.toJvmDescriptor(paramType2));
            }
        }
        sb.append(")");
        sb.append(TypeMirrorToIRType.toJvmDescriptor(samMethod.getReturnType()));
        return sb.toString();
    }

    // -------------------------------------------------------
    // Member reference
    // -------------------------------------------------------

    @Override
    public IRValue visitMemberReference(final MemberReference memberReference,
                                        final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                memberReference.getLineNumber(),
                memberReference.getColumnNumber()
        );

        // Member references worden vertaald naar een functiereferentie
        // Bijv. Foo::bar ├âãÆ├é┬ó├â┬ó├óÔÇÜ┬¼├é┬á├â┬ó├óÔÇÜ┬¼├óÔÇ×┬ó een verwijzing naar de methode Foo_bar

        ExpressionTree expr = memberReference.getExpression();
        String name = memberReference.getName();

        // Bepaal de owner-klasse
        String ownerName = "";
        if (expr != null) {
            TypeMirror exprType = expr.getType();
            if (exprType != null) {
                ownerName = exprType.getClassName();
            }
        }

        String qualifiedName = ownerName.replace('.', '_') + "_" + name;

        // Zoek het methodetype op
        TypeMirror refType = memberReference.getType();
        if (refType instanceof ExecutableType execType) {
            IRType returnType = TypeMirrorToIRType.mapReturnType(execType.getReturnType());
            List<IRType> paramTypes = execType.getParameterTypes().stream()
                    .map(TypeMirrorToIRType::map)
                    .toList();
            IRType.Function fnType = new IRType.Function(returnType, paramTypes);
            return builder.functionRef(qualifiedName, fnType);
        }

        return null;
    }

    // -------------------------------------------------------
    // Annotation (runtime ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø geen IR-waarde)
    // -------------------------------------------------------

    @Override
    public IRValue visitAnnotation(final AnnotationTree annotationTree,
                                   final IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // Wildcard (type-level, geen runtime-waarde)
    // -------------------------------------------------------

    @Override
    public IRValue visitWildCardExpression(final WildcardExpressionTree wildCardExpression,
                                           final IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // Type pattern (instanceof + binding)
    // -------------------------------------------------------

    @Override
    public IRValue visitTypePattern(final TypePattern typePattern,
                                    final IRBuilder param) {
        // Type patterns worden al behandeld door visitInstanceOfExpression
        // en visitSwitchStatement. Hier is de pattern zelf ├âãÆ├é┬ó├â┬ó├óÔé¼┼í├é┬¼├â┬ó├óÔÇÜ┬¼├é┬Ø geen runtime-waarde.
        return null;
    }

    // -------------------------------------------------------
    // Pattern case label (switch met patronen)
    // -------------------------------------------------------

    @Override
    public IRValue visitPatternCaseLabel(final PatternCaseLabel patternCaseLabel,
                                          final IRBuilder param) {
        // For now just return null, pattern handling will be implemented properly in the switch generator
        // Pattern case labels are processed as part of the switch statement generation
        return null;
    }

    // -------------------------------------------------------
    // Module directives (geen runtime-IR)
    // -------------------------------------------------------

    @Override
    public IRValue visitModuleDeclaration(final ModuleDeclaration moduleDeclaration,
                                          final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitRequires(final RequiresTree requiresTree,
                                 final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitExports(final ExportsTree exportsTree,
                                final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitOpens(final OpensTree opensTree,
                              final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitUses(final UsesTree usesTree,
                             final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitProvides(final ProvidesTree providesTree,
                                 final IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // Hulpmethoden
    // -------------------------------------------------------

    private String resolveLabelTarget(final Tree target) {
        if (target instanceof IdentifierTree id) {
            return id.getName();
        }
        return target.toString();
    }

    /**
     * Registreert het huidige blok als continue target voor een pending label.
     * Wordt aangeroepen door lus-bezoekers (while, do-while, for, enhanced-for).
     */
    private void registerLabeledContinueTargets(final IRBasicBlock continueBlock) {
        for (String label : pendingLabels) {
            labeledContinueTargets.put(label, continueBlock);
        }
        // Alleen de direct gelabelde lus: een geneste lus mag het doel van
        // `continue outer` niet overschrijven (dat gaf een eindeloze lus).
        pendingLabels.clear();
    }

    private boolean isJavaLangObject(TypeMirror type) {
        if (type instanceof io.github.potjerodekool.nabu.type.DeclaredType declared) {
            if (declared.asElement() instanceof TypeElement te) {
                return "java.lang.Object".equals(te.getQualifiedName());
            }
        }
        return false;
    }

    private static boolean isArrayType(final TypeMirror type) {
        if (type instanceof io.github.potjerodekool.nabu.type.ArrayType) {
            return true;
        }
        if (type instanceof io.github.potjerodekool.nabu.type.VariableType vt) {
            return vt.getInterferedType() instanceof io.github.potjerodekool.nabu.type.ArrayType;
        }
        return false;
    }
}
