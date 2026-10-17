package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Constructor-overloads die alleen in een referentie-parametertype verschillen
 * ({@code (Object, String)} vs {@code (IScope, String)}) moeten bij de aanroep
 * de meest specifieke overload kiezen.
 *
 * <p>picocli's {@code FieldBinding(Object, Field)} delegeert met
 * {@code this(ObjectScope.asScope(scope), field)} naar
 * {@code FieldBinding(IScope, Field)}. Koos de aanroep de {@code Object}-variant,
 * dan riep die constructor zichzelf aan; LLVM maakte van die staartrecursie een
 * lus die eindeloos nieuwe {@code ObjectScope}-objecten alloceerde.
 */
class ConstructorOverloadSelectionTest {

    private static final String SOURCE = """
            package mini;
            public class Outer {
                public interface IScope { Object get(); }
                public static class ObjectScope implements IScope {
                    private final Object value;
                    public ObjectScope(Object value) { this.value = value; }
                    public Object get() { return value; }
                    static IScope asScope(Object scope) {
                        return scope instanceof IScope ? ((IScope) scope) : new ObjectScope(scope);
                    }
                }
                public static class Binding {
                    private final IScope scope;
                    private final String name;
                    Binding(Object scope, String name) { this(ObjectScope.asScope(scope), name); }
                    Binding(IScope scope, String name) { this.scope = scope; this.name = name; }
                }
                static Binding viaScope(IScope scope) { return new Binding(scope, "s"); }
                static Binding viaObject(Object o) { return new Binding(o, "o"); }
            }
            """;

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

    private List<IRModule> lower() throws Exception {
        Path tmp = Files.createTempFile("CtorOverload", ".java");
        Files.writeString(tmp, SOURCE, StandardCharsets.UTF_8);
        FileObject fo = new PathFileObject(new FileObject.Kind("java", true), tmp);
        CompilationUnit cu;
        try (var in = fo.openInputStream()) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var parser = new Java20Parser(new CommonTokenStream(new Java20Lexer(CharStreams.fromString(text))));
            cu = (CompilationUnit) parser.compilationUnit().accept(new JavaCompilerVisitor(fo, false));
            var ctx = context();
            EnterPhase.enterPhase(cu, ctx);
            ResolverPhase.resolvePhase(cu, ctx);
        }
        IrGeneratingVisitor visitor = new IrGeneratingVisitor();
        visitor.acceptTree(cu, null);
        return visitor.getModules();
    }

    /** De jvm-descriptors van de expliciete parameters van elke Binding-ctor-call in {@code fnSuffix}. */
    private static List<List<String>> bindingCtorCalls(List<IRModule> modules, String fnSuffix) {
        var out = new ArrayList<List<String>>();
        for (IRModule m : modules) {
            for (var fn : m.functions()) {
                if (!fn.name.endsWith(fnSuffix)) continue;
                for (var blk : fn.blocks()) {
                    for (IRInstruction ins : blk.instructions()) {
                        if (ins instanceof IRInstruction.Call call
                                && call.function().endsWith("Binding_init")) {
                            out.add(call.paramTypes().stream()
                                    .map(t -> t instanceof IRType.Ptr p ? String.valueOf(p.jvmDescriptor()) : String.valueOf(t))
                                    .toList());
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<String> describeAll(List<IRModule> modules) {
        var out = new ArrayList<String>();
        for (IRModule m : modules) {
            for (var fn : m.functions()) {
                for (var blk : fn.blocks()) {
                    for (IRInstruction ins : blk.instructions()) {
                        if (ins instanceof IRInstruction.Call call && call.function().endsWith("Binding_init")) {
                            out.add(fn.name + " -> " + call.function() + " " + call.paramTypes().stream()
                                    .map(t -> t instanceof IRType.Ptr p ? String.valueOf(p.jvmDescriptor()) : String.valueOf(t))
                                    .toList());
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void newWithInterfaceArgumentSelectsInterfaceOverload() throws Exception {
        var modules = lower();
        assertEquals(List.of(List.of("Lmini/Outer$IScope;", "Ljava/lang/String;")),
                bindingCtorCalls(modules, "viaScope"), String.join("\n", describeAll(modules)));
    }

    @Test
    void thisDelegationSelectsMoreSpecificOverload() throws Exception {
        var modules = lower();
        // Binding(Object, String) delegeert naar Binding(IScope, String).
        var calls = new ArrayList<List<String>>();
        for (IRModule m : modules) {
            for (var fn : m.functions()) {
                if (!fn.name.endsWith("Binding_init") || fn.params.size() < 2) continue;
                var first = fn.params.get(1).type();
                if (!(first instanceof IRType.Ptr p) || !"Ljava/lang/Object;".equals(p.jvmDescriptor())) continue;
                for (var blk : fn.blocks()) {
                    for (IRInstruction ins : blk.instructions()) {
                        if (ins instanceof IRInstruction.Call call && call.function().endsWith("Binding_init")) {
                            calls.add(call.paramTypes().stream()
                                    .map(t -> t instanceof IRType.Ptr q ? String.valueOf(q.jvmDescriptor()) : String.valueOf(t))
                                    .toList());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(List.of("Lmini/Outer$IScope;", "Ljava/lang/String;")), calls,
                String.join("\n", describeAll(modules)));
    }
}
