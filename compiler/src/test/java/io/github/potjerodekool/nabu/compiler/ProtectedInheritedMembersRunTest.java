package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spring AOT generates subclasses of library classes in another package that call a protected constructor
 * (<code>super(selector, context)</code>, context a nested class) and a protected generic method
 * (<code>convertOne(Object, boolean, Class&lt;T&gt;)</code>). Both were reported as "Failed to resolve method".
 */
class ProtectedInheritedMembersRunTest {

    @TempDir
    Path tempDir;

    private Class<?> compileAndLoad(final String main, final java.util.Map<String, String> sources) throws Exception {
        return compileAndLoad("run", main, sources, null);
    }

    /**
     * @param name      directory name below the temp dir, so that one test can compile twice
     * @param classPath class path of the compilation, or null
     */
    private Class<?> compileAndLoad(final String name,
                                    final String main,
                                    final java.util.Map<String, String> sources,
                                    final Path classPath) throws Exception {
        final var srcDir = tempDir.resolve(name + "-src");
        for (final var entry : sources.entrySet()) {
            final var file = srcDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        final var outDir = tempDir.resolve(name + "-out");
        final var rootPath = "C:\\projects\\nabu\\compiler";
        final var builder = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve(name + "-gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false");
        if (classPath != null) {
            builder.option(CompilerOption.CLASS_PATH, classPath.toString());
        }
        final var options = builder.build();

        final var compiler = new NabuCompiler();
        final var errors = new AtomicInteger();
        final var messages = new java.util.ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                errors.incrementAndGet();
                messages.add(d.getMessage(java.util.Locale.ROOT).toString());
            }
        });
        final var rc = compiler.compile(options);
        final var text = String.join(System.lineSeparator(), messages);
        assertEquals(0, errors.get(), text);
        assertEquals(0, rc, text);

        final var urls = classPath != null
                ? new java.net.URL[]{outDir.toUri().toURL(), classPath.toUri().toURL()}
                : new java.net.URL[]{outDir.toUri().toURL()};
        final var loader = new URLClassLoader(urls, getClass().getClassLoader());
        return loader.loadClass(main);
    }

    @Test
    void protectedConstructorAndGenericMethodOfSuperclassInOtherPackage() throws Exception {
        final var cls = compileAndLoad("mini.sub.Impl", java.util.Map.of(
                "mini/base/Support.java", """
                        package mini.base;
                        public class Support {
                            public static class Context { }
                            private final String selector;
                            protected Support(String selector, Context context) {
                                this.selector = selector;
                            }
                            protected <T> T convertOne(Object value, boolean flag, Class<T> type) {
                                return type.cast(value);
                            }
                        }
                        """,
                "mini/sub/Impl.java", """
                        package mini.sub;
                        import mini.base.Support;
                        public class Impl extends Support {
                            public Impl(Support.Context context) {
                                super("sel", context);
                            }
                            public static String run() {
                                Impl impl = new Impl(new Support.Context());
                                return impl.convertOne("value", false, String.class);
                            }
                        }
                        """));
        assertEquals("value", cls.getMethod("run").invoke(null));
    }

    @Test
    void javaSqlTypesAreVisible() throws Exception {
        final var cls = compileAndLoad("mini.Sql", java.util.Map.of(
                "mini/Sql.java", """
                        package mini;
                        import javax.sql.DataSource;
                        public class Sql {
                            public static String run(DataSource dataSource) {
                                return dataSource == null ? "null" : "set";
                            }
                        }
                        """));
        assertEquals("null", cls.getMethod("run", Class.forName("javax.sql.DataSource")).invoke(null, new Object[]{null}));
    }

    /** The same with a class path: the java.sql module must stay visible (it vanished once a class path was given). */
    @Test
    void javaSqlTypesAreVisibleWithClassPath() throws Exception {
        Files.createDirectories(tempDir.resolve("empty-cp"));
        final var cls = compileAndLoad("sqlcp", "mini.SqlCp", java.util.Map.of(
                "mini/SqlCp.java", """
                        package mini;
                        import javax.sql.DataSource;
                        public class SqlCp {
                            public static String run(DataSource dataSource) {
                                return dataSource == null ? "null" : "set";
                            }
                        }
                        """), tempDir.resolve("empty-cp"));
        assertEquals("null", cls.getMethod("run", Class.forName("javax.sql.DataSource")).invoke(null, new Object[]{null}));
    }

    /**
     * Spring AOT refers to a class it generated itself, `Config$$SpringCGLIB$$0`, by its simple name, as a
     * constructor reference and as a class literal. `$` in a name is a valid identifier character, but the
     * resolver took it for a nested-class separator.
     */
    @Test
    void classNamesWithDollarSignsResolve() throws Exception {
        final var cls = compileAndLoad("mini.Use", java.util.Map.of(
                "mini/Config$$Gen$$0.java", """
                        package mini;
                        public class Config$$Gen$$0 {
                            public String name() {
                                return "generated";
                            }
                        }
                        """,
                "mini/Use.java", """
                        package mini;
                        import java.util.function.Supplier;
                        public class Use {
                            public static String run() {
                                Supplier<Config$$Gen$$0> supplier = Config$$Gen$$0::new;
                                Class<?> type = Config$$Gen$$0.class;
                                return supplier.get().name() + " " + type.getSimpleName();
                            }
                        }
                        """));
        assertEquals("generated Config$$Gen$$0", cls.getMethod("run").invoke(null));
    }

    /**
     * IR function names are the owner with dots turned into underscores; decoding turned every underscore back
     * into a dot, so a class with a double underscore (Spring AOT's <code>Foo__BeanDefinitions</code>) became
     * "Foo//BeanDefinitions" and the generated bytecode was rejected.
     */
    @Test
    void classNamesWithUnderscoresAreCalledByTheirRealName() throws Exception {
        final var cls = compileAndLoad("mini.UseUnderscore", java.util.Map.of(
                "mini/Gen__Holder.java", """
                        package mini;
                        public class Gen__Holder {
                            public static class Inner_ {
                                public String name() {
                                    return "inner";
                                }
                            }
                        }
                        """,
                "mini/UseUnderscore.java", """
                        package mini;
                        import java.util.function.Supplier;
                        public class UseUnderscore {
                            public static String run() {
                                Supplier<Gen__Holder.Inner_> supplier = () -> new Gen__Holder.Inner_();
                                return supplier.get().name();
                            }
                        }
                        """));
        assertEquals("inner", cls.getMethod("run").invoke(null));
    }

    /**
     * Spring AOT: <code>new JpaEvaluationContextExtension(args.get(0))</code>, a constructor with a
     * <code>char</code> parameter and a generic <code>&lt;T&gt; T get(int)</code>. T is inferred as Character and
     * must be cast and unboxed; the argument was left as a reference ("expected I, but found R").
     */
    @Test
    void genericReturnValueIsUnboxedForPrimitiveConstructorParameter() throws Exception {
        final var cls = compileAndLoad("mini.UseGeneric", java.util.Map.of(
                "mini/Arguments.java", """
                        package mini;
                        public class Arguments {
                            private final Object first;
                            private final Object second;
                            private final Object third;
                            public Arguments(Object first, Object second, Object third) {
                                this.first = first;
                                this.second = second;
                                this.third = third;
                            }
                            @SuppressWarnings("unchecked")
                            public <T> T get(int index) {
                                return (T) (index == 0 ? first : index == 1 ? second : third);
                            }
                        }
                        """,
                "mini/Target.java", """
                        package mini;
                        public class Target {
                            private final char letter;
                            private final int number;
                            private final boolean flag;
                            public Target(char letter) {
                                this.letter = letter;
                                this.number = 0;
                                this.flag = false;
                            }
                            public Target(int number, boolean flag) {
                                this.letter = '-';
                                this.number = number;
                                this.flag = flag;
                            }
                            public static String make(int number, boolean flag) {
                                return new Target(number, flag).describe();
                            }
                            public String describe() {
                                return letter + ":" + number + ":" + flag;
                            }
                        }
                        """,
                "mini/UseGeneric.java", """
                        package mini;
                        import java.util.function.Function;
                        public class UseGeneric {
                            public static String run() {
                                Arguments args = new Arguments(Character.valueOf('x'), Integer.valueOf(7), Boolean.TRUE);
                                Function<Arguments, Target> viaLambda = a -> new Target(a.get(0));
                                Target second = new Target(args.get(1), args.get(2));
                                return viaLambda.apply(args).describe() + " " + second.describe()
                                        + " " + Target.make(args.get(1), args.get(2));
                            }
                        }
                        """));
        assertEquals("x:0:false -:7:true -:7:true", cls.getMethod("run").invoke(null));
    }

    /**
     * Spring AOT: <code>BeanInstanceSupplier.&lt;T&gt;forConstructor(String[].class)</code>, a static generic
     * varargs method called with one array class literal. The argument must be wrapped in a Class[]; it was passed
     * as it is (VerifyError: Class is not assignable to Class[]).
     */
    @Test
    void varargsCallWithOneArrayClassLiteral() throws Exception {
        final var cls = compileAndLoad("mini.UseVarargs", java.util.Map.of(
                "mini/Supplier2.java", """
                        package mini;
                        public class Supplier2<T> {
                            private final int count;
                            private Supplier2(int count) {
                                this.count = count;
                            }
                            public static <T> Supplier2<T> forConstructor(Class<?>... parameterTypes) {
                                return new Supplier2<T>(parameterTypes.length);
                            }
                            public int count() {
                                return count;
                            }
                        }
                        """,
                "mini/UseVarargs.java", """
                        package mini;
                        public class UseVarargs {
                            public static int run() {
                                Supplier2<String> supplier = Supplier2.<String>forConstructor(String[].class);
                                return supplier.count();
                            }
                        }
                        """));
        assertEquals(1, cls.getMethod("run").invoke(null));
    }

    /** The same, but the class only exists as a class file on the class path (what Spring AOT leaves behind). */
    @Test
    void classNamesWithDollarSignsResolveFromClassFiles() throws Exception {
        compileAndLoad("first", "mini.Config$$Gen$$0", java.util.Map.of(
                "mini/Config$$Gen$$0.java", """
                        package mini;
                        public class Config$$Gen$$0 {
                            public String name() {
                                return "generated";
                            }
                        }
                        """), null);
        final var cls = compileAndLoad("second", "mini.Use", java.util.Map.of(
                "mini/Use.java", """
                        package mini;
                        import java.util.function.Supplier;
                        public class Use {
                            public static String run() {
                                Supplier<Config$$Gen$$0> supplier = Config$$Gen$$0::new;
                                Class<?> type = Config$$Gen$$0.class;
                                return supplier.get().name() + " " + type.getSimpleName();
                            }
                        }
                        """), tempDir.resolve("first-out"));
        assertEquals("generated Config$$Gen$$0", cls.getMethod("run").invoke(null));
    }
}
