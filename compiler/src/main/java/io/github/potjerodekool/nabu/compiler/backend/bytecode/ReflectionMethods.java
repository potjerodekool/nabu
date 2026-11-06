package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.nativeimage.GraalVMNativeImageConfig.MethodEntry;
import io.github.potjerodekool.nabu.backend.nativeimage.GraalVMNativeImageConfig.ReflectEntry;
import io.github.potjerodekool.nabu.compiler.backend.bytecode.ReflectionDataGenerator.ClassMethods;
import io.github.potjerodekool.nabu.compiler.backend.bytecode.ReflectionDataGenerator.ReflectedMethod;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The methods reflection knows per class of the reflection configuration,
 * as GraalVM registers them:
 * <ul>
 *   <li>{@code getDeclaredMethods}: the declared methods the entry
 *       registers ({@code allDeclaredMethods}, the public ones with
 *       {@code allPublicMethods}, and those named in {@code methods});</li>
 *   <li>{@code getMethods}: with {@code allPublicMethods} the public methods
 *       including inherited ones (an override hides the method it overrides;
 *       static interface methods are not inherited), otherwise the public
 *       ones among the registered declared methods.</li>
 * </ul>
 * Synthetic methods (bridges) are left out.
 */
public final class ReflectionMethods {

    private ReflectionMethods() {
    }

    /** Binary class name → its methods, for the entries that register methods. */
    public static Map<String, ClassMethods> resolve(final List<ReflectEntry> entries,
                                                    final CompilerContextImpl compilerContext) {
        final Map<String, ClassMethods> result = new LinkedHashMap<>();
        for (final ReflectEntry entry : withAnnotationTypes(entries, compilerContext)) {
            final boolean bare = !entry.allDeclaredMethods() && !entry.allPublicMethods()
                    && !entry.allDeclaredConstructors() && !entry.allPublicConstructors()
                    && entry.methods().isEmpty();
            final TypeElement type = ProxySpecs.load(compilerContext, entry.name());
            if (type == null) {
                continue;
            }
            if (bare) {
                // A type the hints name without members: as in a GraalVM image its methods and constructors can be
                // enumerated (getDeclaredMethods, getMethods) but not called.
                final List<ReflectedMethod> queryDeclared = new ArrayList<>();
                for (final ExecutableElement method : methodsOf(type)) {
                    queryDeclared.add(reflected(method, type));
                }
                final List<ReflectedMethod> queryConstructors = new ArrayList<>();
                for (final ExecutableElement constructor : constructorsOf(type)) {
                    queryConstructors.add(reflectedConstructor(constructor, type));
                }
                final List<ReflectedMethod> queryPublics = publicMethods(type);
                final java.util.Set<String> keys = new HashSet<>();
                queryDeclared.forEach(m -> keys.add(m.key()));
                queryPublics.forEach(m -> keys.add(m.key()));
                queryConstructors.forEach(m -> keys.add(m.key()));
                final ClassMethods queryOnly = new ClassMethods(queryDeclared, queryPublics, queryConstructors, keys);
                final ClassMethods known = result.get(type.getFlatName());
                result.put(type.getFlatName(), known == null ? queryOnly : merge(known, queryOnly));
                continue;
            }
            final List<ReflectedMethod> declared = new ArrayList<>();
            for (final ExecutableElement method : methodsOf(type)) {
                final boolean registered = entry.allDeclaredMethods()
                        || (entry.allPublicMethods() && method.isPublic())
                        || named(entry.methods(), method);
                if (registered) {
                    declared.add(reflected(method, type));
                }
            }
            final List<ReflectedMethod> publics = entry.allPublicMethods()
                    ? publicMethods(type)
                    : declared.stream().filter(m -> (m.modifiers() & 0x0001) != 0).toList();
            final List<ReflectedMethod> constructors = new ArrayList<>();
            for (final ExecutableElement constructor : constructorsOf(type)) {
                final boolean registered = entry.allDeclaredConstructors()
                        || (entry.allPublicConstructors() && constructor.isPublic())
                        || named(entry.methods(), constructor);
                if (registered) {
                    constructors.add(reflectedConstructor(constructor, type));
                }
            }
            final String name = type.getFlatName();
            final ClassMethods existing = result.get(name);
            result.put(name, existing == null
                    ? new ClassMethods(declared, publics, constructors)
                    : merge(existing, new ClassMethods(declared, publics, constructors)));
        }
        addInheritedPublicMethods(result, compilerContext);
        return result;
    }

    /**
     * {@code getMethods} of a class also returns the public methods its superclasses and interfaces have registered
     * (GraalVM collects them along the hierarchy): Spring's bean introspection finds {@code setOrder}, which the
     * hints register on {@code ProxyProcessorSupport}, through {@code getMethods} of the bean class. A method the
     * class (or a nearer class) overrides stays the nearer one.
     */
    private static void addInheritedPublicMethods(final Map<String, ClassMethods> result,
                                                  final CompilerContextImpl compilerContext) {
        final Map<String, ClassMethods> extended = new LinkedHashMap<>();
        for (final var entry : result.entrySet()) {
            final TypeElement type = ProxySpecs.load(compilerContext, entry.getKey());
            if (type == null) {
                continue;
            }
            final java.util.Set<String> queryOnly = new HashSet<>(entry.getValue().queryOnly());
            final Map<String, ReflectedMethod> publics = new LinkedHashMap<>();
            for (final ReflectedMethod m : entry.getValue().publics()) {
                publics.put(m.name() + m.desc().substring(0, m.desc().indexOf(')') + 1), m);
            }
            final Deque<TypeElement> pending = new ArrayDeque<>();
            final Set<String> seen = new HashSet<>();
            seen.add(type.getFlatName());
            enqueueSupertypes(type, pending);
            while (!pending.isEmpty()) {
                final TypeElement supertype = pending.poll();
                if (!seen.add(supertype.getFlatName())) {
                    continue;
                }
                final ClassMethods registered = result.get(supertype.getFlatName());
                if (registered != null) {
                    for (final ReflectedMethod m : registered.declared()) {
                        final boolean isPublic = (m.modifiers() & 0x0001) != 0;
                        final boolean isStatic = (m.modifiers() & 0x0008) != 0;
                        if (isPublic && !isStatic && !m.isConstructor()) {
                            if (publics.putIfAbsent(m.name() + m.desc().substring(0, m.desc().indexOf(')') + 1), m)
                                    == null && registered.queryOnly().contains(m.key())) {
                                queryOnly.add(m.key());
                            }
                        }
                    }
                }
                enqueueSupertypes(supertype, pending);
            }
            extended.put(entry.getKey(), new ClassMethods(entry.getValue().declared(),
                    List.copyOf(publics.values()), entry.getValue().constructors(), queryOnly));
        }
        result.putAll(extended);
    }

    private static void enqueueSupertypes(final TypeElement type, final Deque<TypeElement> pending) {
        final TypeElement superclass = superclass(type);
        if (superclass != null) {
            pending.add(superclass);
        }
        for (final TypeMirror itf : type.getInterfaces()) {
            if (itf.asTypeElement() != null) {
                pending.add(itf.asTypeElement());
            }
        }
    }

    /**
     * The entries plus one per annotation type that annotates a registered class or one of its members (and the
     * annotation types on those annotation types): as GraalVM does, the elements of such an annotation type are
     * reflectively visible, because frameworks (Spring's AttributeMethods) read an annotation through
     * {@code getDeclaredMethods} of its type.
     */
    static List<ReflectEntry> withAnnotationTypes(final List<ReflectEntry> entries,
                                                  final CompilerContextImpl compilerContext) {
        final Map<String, ReflectEntry> byName = new LinkedHashMap<>();
        entries.forEach(e -> byName.putIfAbsent(e.name(), e));
        final Set<String> seen = new HashSet<>();
        final Deque<TypeElement> pending = new ArrayDeque<>();
        for (final ReflectEntry entry : entries) {
            try {
                final TypeElement type = ProxySpecs.load(compilerContext, entry.name());
                if (type != null) {
                    type.complete();
                    if (type.getKind() == ElementKind.ANNOTATION_TYPE && seen.add(type.getFlatName())) {
                        pending.add(type);
                    }
                    collectAnnotationTypes(type, seen, pending);
                    for (final var member : type.getEnclosedElements()) {
                        collectAnnotationTypes(member, seen, pending);
                        if (member instanceof ExecutableElement executable) {
                            for (final var parameter : executable.getParameters()) {
                                collectAnnotationTypes(parameter, seen, pending);
                            }
                        }
                    }
                }
            } catch (final RuntimeException e) {
                System.err.println("[nabu] warning: annotation types of " + entry.name() + " skipped: " + e);
            }
        }
        final List<ReflectEntry> result = new ArrayList<>(entries);
        while (!pending.isEmpty()) {
            final TypeElement annotation = pending.poll();
            final String name = annotation.getFlatName();
            try {
                annotation.complete();
            } catch (final RuntimeException e) {
                System.err.println("[nabu] warning: annotation type " + name + " skipped: " + e);
                continue;
            }
            // An entry that names the annotation type without its methods (the agent's "type": Order) gets them too;
            // resolve merges the two.
            final ReflectEntry known = byName.get(name);
            if (known == null || !known.allDeclaredMethods()) {
                final ReflectEntry added = new ReflectEntry(name, false, false, true, false, List.of(), List.of());
                byName.put(name, added);
                result.add(added);
            }
            collectAnnotationTypes(annotation, seen, pending);
        }
        return result;
    }

    private static void collectAnnotationTypes(final io.github.potjerodekool.nabu.lang.model.element.Element element,
                                               final Set<String> seen, final Deque<TypeElement> pending) {
        for (final var mirror : element.getAnnotationMirrors()) {
            final var annotationType = mirror.getAnnotationType() != null
                    ? mirror.getAnnotationType().asTypeElement() : null;
            if (annotationType != null && seen.add(annotationType.getFlatName())) {
                pending.add(annotationType);
            }
        }
    }

    private static ClassMethods merge(final ClassMethods existing, final ClassMethods added) {
        final List<ReflectedMethod> declared = union(existing.declared(), added.declared());
        final List<ReflectedMethod> publics = union(existing.publics(), added.publics());
        final List<ReflectedMethod> constructors = union(existing.constructors(), added.constructors());
        final java.util.Set<String> explicit = new HashSet<>();
        for (final ClassMethods side : List.of(existing, added)) {
            for (final var list : List.of(side.declared(), side.publics(), side.constructors())) {
                for (final ReflectedMethod m : list) {
                    if (!side.queryOnly().contains(m.key())) {
                        explicit.add(m.key());
                    }
                }
            }
        }
        final java.util.Set<String> queryOnly = new HashSet<>();
        for (final var list : List.of(declared, publics, constructors)) {
            for (final ReflectedMethod m : list) {
                if (!explicit.contains(m.key())) {
                    queryOnly.add(m.key());
                }
            }
        }
        return new ClassMethods(declared, publics, constructors, queryOnly);
    }

    private static List<ReflectedMethod> union(final List<ReflectedMethod> first, final List<ReflectedMethod> second) {
        final Map<String, ReflectedMethod> methods = new LinkedHashMap<>();
        first.forEach(m -> methods.put(m.key(), m));
        second.forEach(m -> methods.putIfAbsent(m.key(), m));
        return List.copyOf(methods.values());
    }

    private static boolean named(final List<MethodEntry> entries, final ExecutableElement method) {
        final String methodName = method.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : method.getSimpleName();
        for (final MethodEntry entry : entries) {
            if (!entry.name().equals(methodName)) {
                continue;
            }
            final var parameters = method.getParameters();
            if (parameters.size() != entry.parameterTypes().size()) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < parameters.size() && same; i++) {
                same = ElementSignatures.descriptor(parameters.get(i).asType())
                        .equals(ElementSignatures.descriptorOfName(entry.parameterTypes().get(i)));
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    /** The non-synthetic methods (no constructors or initializers) a type declares. */
    private static List<ExecutableElement> methodsOf(final TypeElement type) {
        type.complete();
        final List<ExecutableElement> methods = new ArrayList<>();
        for (final var element : type.getEnclosedElements()) {
            if (element instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD
                    && !method.isSynthetic()) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static ReflectedMethod reflected(final ExecutableElement method, final TypeElement owner) {
        return new ReflectedMethod(ElementSignatures.internalName(owner), ElementSignatures.isInterface(owner),
                method.getSimpleName(), ElementSignatures.descriptor(method),
                ElementSignatures.modifiers(method, owner), ElementSignatures.exceptions(method),
                ElementSignatures.genericSignature(method));
    }

    /** The constructors a type declares. */
    private static List<ExecutableElement> constructorsOf(final TypeElement type) {
        type.complete();
        final List<ExecutableElement> constructors = new ArrayList<>();
        for (final var element : type.getEnclosedElements()) {
            if (element instanceof ExecutableElement constructor && constructor.getKind() == ElementKind.CONSTRUCTOR
                    && !constructor.isSynthetic()) {
                constructors.add(constructor);
            }
        }
        return constructors;
    }

    /**
     * A constructor: {@code <init>} returning void. The constructor of an
     * inner (non-static member) class takes the enclosing instance first, as
     * in the class file; its Constructor shows that parameter too, as the
     * JDK's does.
     */
    private static ReflectedMethod reflectedConstructor(final ExecutableElement constructor, final TypeElement owner) {
        final var descriptor = new StringBuilder("(");
        if (owner.getNestingKind() == io.github.potjerodekool.nabu.lang.model.element.NestingKind.MEMBER
                && !owner.isStatic() && !ElementSignatures.isInterface(owner)
                && owner.getEnclosingElement() instanceof TypeElement outer
                && !ElementSignatures.isInterface(outer)) {
            descriptor.append('L').append(ElementSignatures.internalName(outer)).append(';');
        }
        for (final var parameter : constructor.getParameters()) {
            descriptor.append(ElementSignatures.descriptor(parameter.asType()));
        }
        descriptor.append(")V");
        int modifiers = ElementSignatures.modifiers(constructor, owner) & ~0x0400;
        if (owner.isAbstract()) {
            modifiers |= ReflectionDataGenerator.ABSTRACT_OWNER;
        }
        return new ReflectedMethod(ElementSignatures.internalName(owner), false, "<init>", descriptor.toString(),
                modifiers, ElementSignatures.exceptions(constructor), ElementSignatures.genericSignature(constructor));
    }

    /** {@code Class.getMethods}: public methods of the class, its superclasses and all its interfaces. */
    private static List<ReflectedMethod> publicMethods(final TypeElement type) {
        final Map<String, ReflectedMethod> methods = new LinkedHashMap<>();
        final Deque<TypeElement> interfaces = new ArrayDeque<>();
        // An interface does not get Object's methods (Class.getMethods).
        for (TypeElement c = type; c != null; c = ElementSignatures.isInterface(c) ? null : superclass(c)) {
            for (final ExecutableElement method : methodsOf(c)) {
                // A source interface method is public without saying so.
                if (method.isPublic() || (ElementSignatures.isInterface(c) && !method.isPrivate())) {
                    if (!(ElementSignatures.isInterface(c) && method.isStatic() && c != type)) {
                        methods.putIfAbsent(signature(method), reflected(method, c));
                    }
                }
            }
            for (final TypeMirror itf : c.getInterfaces()) {
                if (itf.asTypeElement() != null) {
                    interfaces.add(itf.asTypeElement());
                }
            }
        }
        final Set<String> seen = new HashSet<>();
        while (!interfaces.isEmpty()) {
            final TypeElement itf = interfaces.poll();
            if (!seen.add(itf.getFlatName())) {
                continue;
            }
            for (final ExecutableElement method : methodsOf(itf)) {
                if (!method.isStatic() && !method.isPrivate()) {
                    methods.putIfAbsent(signature(method), reflected(method, itf));
                }
            }
            for (final TypeMirror superInterface : itf.getInterfaces()) {
                if (superInterface.asTypeElement() != null) {
                    interfaces.add(superInterface.asTypeElement());
                }
            }
        }
        return List.copyOf(methods.values());
    }

    private static TypeElement superclass(final TypeElement type) {
        final TypeMirror superclass = type.getSuperclass();
        return superclass != null && superclass.getKind() == io.github.potjerodekool.nabu.type.TypeKind.DECLARED
                ? superclass.asTypeElement()
                : null;
    }

    /** Name and parameters: what an override shares with the method it overrides. */
    private static String signature(final ExecutableElement method) {
        final String descriptor = ElementSignatures.descriptor(method);
        return method.getSimpleName() + descriptor.substring(0, descriptor.indexOf(')') + 1);
    }
}
