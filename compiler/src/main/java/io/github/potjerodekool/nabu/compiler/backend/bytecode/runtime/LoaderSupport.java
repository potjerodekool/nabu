package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * The class loader of a native program. There is one; it knows the classes of the program (as
 * {@code Class.forName} does) and, through {@link ResourceSupport}, its resources. Code of the application and of
 * libraries asks for a loader all the time ({@code ClassUtils.getDefaultClassLoader} of Spring: the context
 * loader, else the loader of a class, else the system loader) and fails when it gets {@code null}; code of the JDK
 * itself treats {@code null} as the boot loader, so the classes of the JDK keep that.
 *
 * <p>Calls to {@code Class.getClassLoader}, {@code Thread.getContextClassLoader} and
 * {@code ClassLoader.getSystemClassLoader} are redirected to these methods (see {@code RuntimeCalls}).
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +} (invokedynamic) and no lambdas.
 */
public final class LoaderSupport {

    private static NativeClassLoader loader;

    private LoaderSupport() {
    }

    private static boolean creating;

    /** The loader; null while it is being made (the constructor of ClassLoader may ask for a loader itself). */
    static synchronized ClassLoader loader() {
        if (loader == null) {
            if (creating) {
                return null;
            }
            creating = true;
            try {
                loader = new NativeClassLoader();
            } finally {
                creating = false;
            }
        }
        return loader;
    }

    /** {@code Class.getClassLoader}: null (the boot loader) for the JDK, the one loader for the rest. */
    public static ClassLoader classLoader(final Class<?> type) {
        if (type == null || isBoot(type.getName())) {
            return null;
        }
        return loader();
    }

    public static ClassLoader contextClassLoader(final Thread thread) {
        return loader();
    }

    public static ClassLoader systemClassLoader() {
        return loader();
    }

    private static boolean isBoot(final String name) {
        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("javax.") || name.startsWith("org.w3c.")
                || name.startsWith("org.xml.") || name.startsWith("[") || name.indexOf('.') < 0 && isPrimitive(name);
    }

    private static boolean isPrimitive(final String name) {
        return name.equals("int") || name.equals("long") || name.equals("boolean") || name.equals("byte")
                || name.equals("char") || name.equals("short") || name.equals("float") || name.equals("double")
                || name.equals("void");
    }

    /** The loader: classes by name from the program, resources from {@link ResourceSupport}. */
    static final class NativeClassLoader extends ClassLoader {

        NativeClassLoader() {
            super("app", null);
        }

        @Override
        public Class<?> loadClass(final String name) throws ClassNotFoundException {
            return Class.forName(name);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            return Class.forName(name);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            return Class.forName(name);
        }

        @Override
        public String toString() {
            return "nabu-app-loader";
        }
    }
}
