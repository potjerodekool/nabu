package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.backend.ASMTestUtils;
import io.github.potjerodekool.nabu.compiler.backend.ir.BackendTest;
import io.github.potjerodekool.nabu.compiler.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.Optimizer;
import io.github.potjerodekool.nabu.compiler.impl.EnterPhase;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase;
import io.github.potjerodekool.nabu.testing.InMemoryFileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.util.CollectionUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InstructionEmitterTest extends BackendTest {

    @Test
    void visitForStatement() {
        Function function = parse(
                """
                        public void forLoop() {
                            for (int i = 0; i < 10; i++) {
                            }
                        }
                        """,
                Java20Parser::methodDeclaration
        );

        function = process(function);

        final var actual = compile(function);
        final var expected = loadResource("InstructionEmitterTest/forLoop.txt");
        assertEquals(expected, actual);
    }

    @Test
    void visitForEachStatement() {
        Function function = parse(
                """
                        public void forEachLoop(final java.util.List<String> list) {
                            for (String s : list) {
                                System.out.println(s);
                            }
                        }
                        """,
                Java20Parser::methodDeclaration
        );

        function = process(function);

        final var actual = compile(function);
        final var expected = loadResource("InstructionEmitterTest/forEachLoop.txt");
        assertEquals(expected, actual);
    }

    /*
     * TODO
     *  pos++ can be optimized with the IINC instruction.
     */
    @Test
    void whileLoop() {
        Function function = parse(
                """
                        public int whileLoop() {
                            final var list = new java.util.ArrayList<Integer>();
                            var result = 0;
                            var pos = 0;
                            while(pos < list.size()) {
                                result += ((Integer) list.get(pos)).intValue();
                                pos++;
                         }
                         return result;
                        }
                        """,
                Java20Parser::methodDeclaration
        );

        function = process(function);

        final var actual = compile(function);
        final var expected = loadResource("InstructionEmitterTest/whileLoop.txt");

        assertEquals(expected, actual);
    }

    @Test
    void doWhileLoop() {
        Function function = parse(
                """
                        int doWhileLoop(int number) {
                             var result = 0;
                             var steps = 5;
                        
                             do {
                                 result += number;
                                 steps--;
                             } while (steps > 0);
                        
                             return result;
                        }
                        """,
                Java20Parser::methodDeclaration
        );

        function = process(function);

        final var actual = compile(function);
        final var expected = loadResource("InstructionEmitterTest/dowhileLoop.txt");

        assertEquals(expected, actual);
    }

    @Test
    void ifStatement() {
        Function function = parse(
                """
                        int compareToFive(int number) {
                             if (number > 5) {
                                return 1;
                             } else if (number < 5) {
                                return -1;
                             } else {
                                return 0;
                             }
                        }
                        """,
                Java20Parser::methodDeclaration
        );

        function = process(function);

        final var actual = compile(function);
        final var expected = loadResource("InstructionEmitterTest/IfStatement.txt");

        assertEquals(expected, actual);
    }

    @Test
    void getValue() {
        final var fileObject = new InMemoryFileObject("", "Myclass.java");

        io.github.potjerodekool.nabu.tree.CompilationUnit compilationUnit = parse(
                fileObject,
                """
                        public class MyClass {
                            private int number;
                        
                            int getValue() {
                                return this.number;
                            }
                        }
                        """,
                Java20Parser::compilationUnit
        );

        compilationUnit = process(compilationUnit);

        final var actual = compile(compilationUnit);
        final var expected = loadResource("InstructionEmitterTest/getValue.txt");

        assertEquals(expected, actual);
    }

    @Test
    void setValue() {
        Function function = parse(
                """
                        void setValue(int number) {
                             this.number = number;
                             return;
                        }
                        """,
                Java20Parser::methodDeclaration
        );

        function = process(function);

        final var actual = compile(function);
        final var expected = loadResource("InstructionEmitterTest/setValue.txt");

        assertEquals(expected, actual);
    }

    @Test
    void visitBinaryExpression() {
        final var fileObject = new InMemoryFileObject("", "Myclass.java");

        io.github.potjerodekool.nabu.tree.CompilationUnit compilationUnit = parse(
                fileObject,
                """
                        import java.util.List;
                        
                        public class MyClass {
                            int whileLoop(List<Integer> list) {
                                var result = 0;
                                var pos = 0;
                                while(pos < list.size()) {
                                    result += list.get(pos);
                                    pos++;
                                }
                    
                                return result;
                            }
                        }
                        """,
                Java20Parser::compilationUnit
        );

        compilationUnit = process(compilationUnit);

        final var actual = compile(compilationUnit);
        final var expected = loadResource("InstructionEmitterTest/visitBinaryExpression.txt");

        assertEquals(expected, actual);
    }

    @Test
    void emptyMethod() {
        final var fileObject = new InMemoryFileObject("", "Myclass.java");

        io.github.potjerodekool.nabu.tree.CompilationUnit compilationUnit = parse(
                fileObject,
                """
                        public class MyClass {
                            void doIt() {
                            }
                        }
                        """,
                Java20Parser::compilationUnit
        );

        compilationUnit = process(compilationUnit);

        final var actual = compile(compilationUnit);
        final var expected = loadResource("InstructionEmitterTest/emptyMethod.txt");

        assertEquals(expected, actual);
    }

    @Test
    void ifStatementWithNull() {
        final var fileObject = new InMemoryFileObject("", "Myclass.java");

        io.github.potjerodekool.nabu.tree.CompilationUnit compilationUnit = parse(
                fileObject,
                """
                        public class MyClass {
                            boolean isNull(Integer i) {
                                if (i == null) {
                                    return true;
                                } else {
                                    return false;
                                }
                            }
                        }
                        """,
                Java20Parser::compilationUnit
        );

        compilationUnit = process(compilationUnit);

        final var actual = compile(compilationUnit);
        final var expected = loadResource("InstructionEmitterTest/ifStatementWithNull.txt");

        assertEquals(expected, actual);
    }

    @Test
    void visitStringConcat() {
        final var fileObject = new InMemoryFileObject("", "Myclass.java");

        io.github.potjerodekool.nabu.tree.CompilationUnit compilationUnit = parse(
                fileObject,
                """
                        public class MyClass {
                            String hello(final String name) {
                                return "Hello " + name;
                            }
                        }
                        """,
                Java20Parser::compilationUnit
        );

        compilationUnit = process(compilationUnit);

        final var actual = compile(compilationUnit);
        final var expected = loadResource("InstructionEmitterTest/StringConcat.txt");

        assertEquals(expected, actual);
    }

    private Function process(final Function function) {
        final var clazz = TreeMaker.classDeclaration(
                Kind.CLASS,
                NestingKind.TOP_LEVEL,
                new Modifiers(),
                "MyClass",
                List.of(function),
                List.of(),
                List.of(),
                null,
                List.of(),
                0,
                0
        );

        final var fileObject = new InMemoryFileObject("", "MyClass.java");

        final var cu = TreeMaker.compilationUnit(
                fileObject,
                List.of(),
                List.of(clazz),
                0,
                0
        );

        final var newCU = process(cu);

        final var newClazz = newCU.getClasses().getFirst();
        return newClazz.getEnclosedElements().stream()
                .flatMap(CollectionUtils.mapOnly(Function.class))
                .filter(f -> f.getSimpleName().equals(function.getSimpleName()))
                .findFirst()
                .orElse(null);
    }

    private CompilationUnit process(final CompilationUnit cu) {
        EnterPhase.enterPhase(
                cu,
                getCompilerContext()
        );
        ResolverPhase.resolvePhase(
                cu,
                getCompilerContext()
        );

        return lower(
                cu,
                getCompilerContext()
        );
    }

    private String compile(final Function function) {

        final var emitter = new ASMByteCodeEmitter();

        final var clazz = TreeMaker.classDeclaration(
                Kind.CLASS,
                NestingKind.TOP_LEVEL,
                new Modifiers(),
                "MyClass",
                List.of(function),
                List.of(),
                List.of(),
                null,
                List.of(),
                0,
                0
        );

        final var fileObject = new InMemoryFileObject("", "MyClass.java");

        final var cu = TreeMaker.compilationUnit(fileObject, List.of(), List.of(), 0, 0);

        final var visitor = new IrGeneratingVisitor();
        visitor.visitCompilationUnit(cu, null);

        visitor.visitClass(clazz, null);
        final var module = Optimizer.optimize(visitor.getModule());

        emitter.emit(module);

        return ASMTestUtils.byteCodeToText(emitter.getBytecode());
    }

    private String compile(final CompilationUnit compilationUnit) {

        final var emitter = new ASMByteCodeEmitter();
        final var fileObject = new InMemoryFileObject("", "MyClass.java");

        final var visitor = new IrGeneratingVisitor();
        visitor.visitCompilationUnit(compilationUnit, null);
/*
        final var clazz = compilationUnit.getClasses().getFirst();

        visitor.visitClass(clazz, null);
        */
        final var module = Optimizer.optimize(visitor.getModule());

        emitter.emit(module);

        return ASMTestUtils.byteCodeToText(emitter.getBytecode());
    }
}