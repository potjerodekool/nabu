package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.impl.EnterPhase;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase;
import io.github.potjerodekool.nabu.tools.*;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

/** Scratch-probe: interface-methode die letterlijk 'init' heet. */
class InterfaceInitNameProbeTest {

    private CompilerContext context() {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        String rootPath = new File(root, "compiler").getAbsolutePath();
        CompilerOptions options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                .build();
        Compiler compiler = ServiceLoader.load(Compiler.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("No compiler"));
        return compiler.configure(options);
    }

    private static String desc(io.github.potjerodekool.nabu.backend.ir.types.IRType t) {
        if (t instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr p) {
            return "ptr(" + p.pointee().getClass().getSimpleName()
                    + (p.jvmDescriptor() != null ? ", " + p.jvmDescriptor() : ", NO-DESC") + ")";
        }
        return String.valueOf(t);
    }

    @Test
    void probe() throws Exception {
        String source = """
                package mini;
                public class Outer {
                    public interface IHelp {
                        int value();
                    }
                    public static int run(IHelp h) {
                        return h.value();
                    }
                    public static class Impl implements IHelp {
                        int v;
                        Impl(int v) { this.v = v; }
                        public int value() { return this.v; }
                    }
                    public static class Other implements IHelp {
                        public int value() { return -1; }
                    }
                }
                """;

        Path tmp = Files.createTempFile("PROBE", ".java");
        Files.writeString(tmp, source, StandardCharsets.UTF_8);
        FileObject fo = new PathFileObject(new FileObject.Kind("java", true), tmp);
        CompilationUnit cu;
        try (var in = fo.openInputStream()) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var parser = new Java20Parser(new CommonTokenStream(new Java20Lexer(CharStreams.fromString(text))));
            cu = (CompilationUnit) parser.compilationUnit().accept(new JavaCompilerVisitor(fo, false));
            EnterPhase.enterPhase(cu, context());
            ResolverPhase.resolvePhase(cu, context());
        }

        IrGeneratingVisitor visitor = new IrGeneratingVisitor();
        visitor.acceptTree(cu, null);

        var sb = new StringBuilder();
        for (IRModule m : visitor.getModules()) {
            sb.append("MODULE name=").append(m.name)
              .append(" flags=").append(m.flags)
              .append(" superType=").append(m.superType())
              .append('\n');
            for (var itf : m.interfaces()) {
                sb.append("  IMPLEMENTS ").append(desc(itf)).append('\n');
            }
            for (var fn : m.functions()) {
                sb.append("  FN ").append(fn.name)
                  .append(" external=").append(fn.isExternal())
                  .append(" ctor=").append(fn.isConstructor())
                  .append(" ret=").append(fn.returnType)
                  .append('\n');
                sb.append("    PARAMS");
                for (var p : fn.params) {
                    sb.append(" : ").append(desc(p.type()));
                }
                sb.append('\n');
                for (var blk : fn.blocks()) {
                    for (IRInstruction ins : blk.instructions()) {
                        if (ins instanceof IRInstruction.Call call) {
                            sb.append("    CALL kind=").append(call.callKind())
                              .append(" fn=").append(call.function())
                              .append(" args=");
                            for (var a : call.args()) {
                                sb.append(" {").append(desc(a.type())).append('}');
                            }
                            sb.append('\n');
                        }
                    }
                }
            }
        }
        System.out.println("=== IR DUMP ===\n" + sb + "=== END ===");
        if (sb.toString().contains("kind=INTERFACE")) {
            System.out.println("=== INTERFACE CALLS GEVONDEN ===");
        } else {
            System.out.println("=== GEEN INTERFACE CALLS ===");
        }

        // Nu de echte backend erop loslaten.
        var modules = visitor.getModules();
        Path out = Path.of("target", "probe", "ifaceinit.o");
        Files.createDirectories(out.getParent());
        try {
            new NativeLLVMBackend().compileToObject(modules,
                    io.github.potjerodekool.nabu.backend.CompileOptions.debug(), out);
            System.out.println("=== BACKEND OK: " + out + " ===");
        } catch (Throwable t) {
            System.out.println("=== BACKEND FAALT: " + t + " ===");
            var cause = t.getCause();
            while (cause != null) {
                System.out.println("    caused by: " + cause);
                var st = cause.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) System.out.println("        at " + st[i]);
                cause = cause.getCause();
            }
        }
    }
}