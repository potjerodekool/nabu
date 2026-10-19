package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The proxy classes to generate: per configured interface list, its
 * interfaces and their (erased) instance methods, read from the compiler's
 * elements so source interfaces count as well as class files.
 */
public final class ProxySpecs {

    private ProxySpecs() {
    }

    /**
     * @param interfaceLists binary names ({@code a.b.Outer$Inner}) per proxy.
     */
    public static List<ProxyClassGenerator.ProxySpec> resolve(final List<List<String>> interfaceLists,
                                                             final CompilerContextImpl compilerContext) {
        final Set<List<String>> distinct = new LinkedHashSet<>(interfaceLists);
        final List<ProxyClassGenerator.ProxySpec> specs = new ArrayList<>();
        for (final List<String> names : distinct) {
            final List<String> interfaces = new ArrayList<>();
            final Map<String, ProxyClassGenerator.ProxyMethod> methods = new LinkedHashMap<>();
            boolean complete = true;
            for (final String name : names) {
                final TypeElement type = load(compilerContext, name);
                if (type == null || type.getKind() != ElementKind.INTERFACE) {
                    System.err.println("[nabu] warning: proxy interface " + name
                            + " from the native-image configuration was not found or is no interface");
                    complete = false;
                    break;
                }
                interfaces.add(ElementSignatures.internalName(type));
                collect(type, methods);
            }
            if (complete && !interfaces.isEmpty()) {
                specs.add(new ProxyClassGenerator.ProxySpec(interfaces, List.copyOf(methods.values())));
            }
        }
        return specs;
    }

    /** A class or interface by binary name ({@code a.B$C}), from source or class files; null if unknown. */
    static TypeElement load(final CompilerContextImpl compilerContext, final String name) {
        final var loader = compilerContext.getClassElementLoader();
        var type = loader.loadClass(null, name);
        if (type == null && name.indexOf('$') > 0) {
            type = loader.loadClass(null, name.replace('$', '.'));
        }
        return type;
    }

    /** The interface's instance methods and those of its superinterfaces (first declaration wins). */
    private static void collect(final TypeElement type, final Map<String, ProxyClassGenerator.ProxyMethod> methods) {
        type.complete();
        for (final var element : type.getEnclosedElements()) {
            if (element instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD
                    && !method.isStatic() && !method.isPrivate()) {
                final String descriptor = ElementSignatures.descriptor(method);
                final List<String> exceptions = new ArrayList<>();
                for (final var thrown : method.getThrownTypes()) {
                    final String desc = ElementSignatures.descriptor(thrown);
                    if (desc.startsWith("L")) {
                        exceptions.add(desc.substring(1, desc.length() - 1));
                    }
                }
                final String name = method.getSimpleName();
                methods.putIfAbsent(name + descriptor, new ProxyClassGenerator.ProxyMethod(
                        ElementSignatures.internalName(type), true, name, descriptor, exceptions,
                        ElementSignatures.modifiers(method, type)));
            }
        }
        for (final TypeMirror superInterface : type.getInterfaces()) {
            final TypeElement element = superInterface.asTypeElement();
            if (element != null) {
                collect(element, methods);
            }
        }
    }
}
