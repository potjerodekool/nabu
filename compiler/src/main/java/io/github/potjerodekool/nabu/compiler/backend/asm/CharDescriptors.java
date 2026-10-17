package io.github.potjerodekool.nabu.compiler.backend.asm;

import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Het IR kent geen apart char-type: char en short zijn allebei {@code i16},
 * en een call-descriptor krijgt daarvoor {@code S}. Voor een methode van een
 * klasse die de compiler zelf kan laden (de JDK) zoekt dit de echte
 * descriptor op, waarin op die plekken een {@code C} kan staan. Zonder dit
 * gaf bv. {@code s.charAt(0)} een NoSuchMethodError
 * ({@code 'short java.lang.String.charAt(int)'}).
 *
 * <p>Calls tussen klassen uit de eigen compilatie blijven ongewijzigd: hun
 * declaraties gebruiken dezelfde {@code S}-descriptors.
 */
final class CharDescriptors {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private CharDescriptors() {
    }

    /**
     * @return de echte descriptor als die alleen in {@code C} i.p.v. {@code S}
     * verschilt, anders {@code descriptor} zelf.
     */
    static String resolve(final String ownerInternalName, final String name, final String descriptor) {
        if (ownerInternalName == null || descriptor.indexOf('S') < 0) {
            return descriptor;
        }
        return CACHE.computeIfAbsent(ownerInternalName + "#" + name + "#" + descriptor,
                key -> lookup(ownerInternalName, name, descriptor));
    }

    private static String lookup(final String ownerInternalName, final String name, final String descriptor) {
        if (ownerInternalName.startsWith("[")) {
            return descriptor;
        }
        final Class<?> owner;
        try {
            owner = Class.forName(ownerInternalName.replace('/', '.'), false,
                    ClassLoader.getPlatformClassLoader());
        } catch (final ClassNotFoundException | LinkageError e) {
            return descriptor;
        }
        final String wanted = normalize(descriptor);
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            final String found = match(c, name, wanted);
            if (found != null) {
                return found;
            }
        }
        for (final Class<?> itf : owner.getInterfaces()) {
            final String found = match(itf, name, wanted);
            if (found != null) {
                return found;
            }
        }
        return descriptor;
    }

    private static String match(final Class<?> c, final String name, final String wanted) {
        final Executable[] candidates;
        try {
            candidates = "<init>".equals(name) ? c.getDeclaredConstructors() : c.getDeclaredMethods();
        } catch (final LinkageError | SecurityException e) {
            return null;
        }
        for (final Executable e : candidates) {
            if (!"<init>".equals(name) && !e.getName().equals(name)) {
                continue;
            }
            final String real = e instanceof Method m ? Type.getMethodDescriptor(m)
                    : Type.getConstructorDescriptor((Constructor<?>) e);
            if (!real.equals(wanted) && normalize(real).equals(wanted)) {
                return real;
            }
        }
        return null;
    }

    /** char ({@code C}) en short ({@code S}) gelijkstellen, buiten klassennamen. */
    private static String normalize(final String descriptor) {
        final StringBuilder sb = new StringBuilder(descriptor.length());
        boolean inClassName = false;
        for (int i = 0; i < descriptor.length(); i++) {
            final char ch = descriptor.charAt(i);
            if (inClassName) {
                sb.append(ch);
                if (ch == ';') {
                    inClassName = false;
                }
            } else if (ch == 'L') {
                inClassName = true;
                sb.append(ch);
            } else {
                sb.append(ch == 'C' ? 'S' : ch);
            }
        }
        return sb.toString();
    }
}
