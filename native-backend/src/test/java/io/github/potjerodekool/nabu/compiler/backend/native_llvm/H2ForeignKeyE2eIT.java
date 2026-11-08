package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Method.getDefaultValue} of the elements of an annotation type from a library (bytecode), asked by library
 * code (Spring's AnnotationTypeMapping checks that aliased attributes declare default values).
 */
class H2ForeignKeyE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void insertWithForeignKey() throws Exception {
        NativeToolchain.assumeGcc();
        final Path h2 = Path.of(System.getProperty("user.home"), ".m2", "repository", "com", "h2database", "h2", "2.4.240", "h2-2.4.240.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(h2), "h2 2.4.240 not in ~/.m2");
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import java.sql.*;

                import java.sql.*;

                public class Main {
                    static void attempt(String label, String[] ddl) {
                        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + label, "SA", "");
                             Statement s = c.createStatement()) {
                            for (String d : ddl) s.execute(d);
                            try (PreparedStatement p = c.prepareStatement("insert into pet (category_id,name,id) values (?,?,?)")) {
                                p.setLong(1, 1L);
                                p.setString(2, "rex");
                                p.setLong(3, 10L);
                                System.out.println(label + " " + p.executeUpdate());
                            }
                        } catch (Exception e) {
                            Throwable t = e;
                            while (t.getCause() != null) t = t.getCause();
                            System.out.println(label + " failed " + t.getClass().getName());
                            StackTraceElement[] st = t.getStackTrace();
                            for (int i = 0; i < Math.min(12, st.length); i++) System.out.println("  " + st[i]);
                        }
                    }
                    public static void main(String[] args) throws Exception {
                        String cat = "create table category (id bigint not null, name varchar(255), primary key (id))";
                        String ins = "insert into category (id, name) values (1, 'dogs')";
                        attempt("explicit", new String[] {cat, "create table pet (id bigint not null, category_id bigint, name varchar(255), primary key (id))",
                                "alter table pet add constraint fk1 foreign key (category_id) references category (id)", ins});
                        attempt("inline", new String[] {cat, "create table pet (id bigint not null, category_id bigint, name varchar(255), primary key (id), foreign key (category_id) references category (id))", ins});
                        attempt("implicit", new String[] {cat, "create table pet (id bigint not null, category_id bigint, name varchar(255), primary key (id))",
                                "alter table pet add constraint fk2 foreign key (category_id) references category", ins});
                        attempt("nofk", new String[] {cat, "create table pet (id bigint not null, category_id bigint, name varchar(255), primary key (id))", ins});
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [ ]
                """);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.CLASS_PATH, libClasses + File.pathSeparator + h2)
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();
        final var diagnostics = new java.util.ArrayList<String>();
        final var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        final Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput).findFirst().orElseThrow();
        }
        final String keep = System.getProperty("nabu.keepExe");
        if (keep != null) {
            Files.copy(exe, Path.of(keep), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (Stream<Path> walk = Files.walk(out)) {
                final Path ll = walk.filter(p -> p.getFileName().toString().endsWith(".ll"))
                        .max(java.util.Comparator.comparingLong(p -> p.toFile().length())).orElse(null);
                if (ll != null) {
                    Files.copy(ll, Path.of(keep.substring(0, keep.length() - ".exe".length()) + ".ll"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        final Path log = tempDir.resolve("run.log");
        final Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(240, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        final int exit = proc.exitValue();
        assertEquals("explicit 1\ninline 1\nimplicit 1\nnofk 1\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
