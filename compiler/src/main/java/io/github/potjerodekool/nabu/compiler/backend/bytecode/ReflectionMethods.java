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
        for (final ReflectEntry entry : entries) {
            if (!entry.allDeclaredMethods() && !entry.allPublicMethods() && !entry.allDeclaredConstructors()
                    && !entry.allPublicConstructors() && entry.methods().isEmpty()) {
                continue;
            }
            final TypeElement type = ProxySpecs.load(compilerContext, entry.name());
            if (type == null) {
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
        return result;
    }

    private static ClassMethods merge(final ClassMethods existing, final ClassMethods added) {
        return new ClassMethods(union(existing.declared(), added.declared()),
                union(existing.publics(), added.publics()),
                union(existing.constructors(), added.constructors()));
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
