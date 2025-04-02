package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmMethodByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.InvocationType;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.type.impl.CVoidType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsmMethodByteCodeGeneratorTest {

    private ClassWriter createClassWriter() {
        final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, 0, "SomeClass", null, "java/lang/Object", new String[0]);
        return writer;
    }

    private MethodSymbol createMethod(final List<IStatement> statements) {
        final var frag = new ProcFrag(
                Seq.seq(statements)
        );

        final var method = new MethodSymbolBuilderImpl()
                .name("someMethod")
                .returnType(new CVoidType())
                .build();

        method.setFrag(frag);
        return method;
    }

    private AsmMethodByteCodeGenerator createMethodWriter() {
        final var classWriter = createClassWriter();

        return new AsmMethodByteCodeGenerator(
                classWriter,
                "SomeClass"
        );
    }

    @Test
    void generate() {
        final var call = new IExpressionStatement(new Call(
                InvocationType.STATIC,
                new Name("OtherClass"),
                new Name("someMethod"),
                IPrimitiveType.VOID,
                List.of(
                        IPrimitiveType.BOOLEAN
                ),
                List.of(new Unop(
                        Tag.NOT,
                        new Const(true)
                ))
        ));

        final var method = createMethod(
                List.of(call)
        );

        final var methodGenerator = createMethodWriter();
        methodGenerator.generate(method);
        methodGenerator.getTextifier().getText().forEach(System.out::println);
    }

    @Test
    void generateIf() {
        final var frame = new Frame();
        final var varIndex = frame.allocateLocal(
                "b",
                IPrimitiveType.BOOLEAN,
                true
        );

        final var startLabel = new ILabelStatement();
        final var endLabel = new ILabelStatement();

        frame.getLocals().forEach(local -> {
            local.setStart(startLabel.getLabel());
            local.setEnd(endLabel.getLabel());
        });

        final var trueLabel = new ILabel();
        final var falseLabel = new ILabel();

        final var statements = new ArrayList<IStatement>();
        statements.add(startLabel);

        statements.add(
                new CJump(
                        Tag.EQ,
                        new TempExpr(varIndex, IPrimitiveType.BOOLEAN),
                        new Const(1),
                        trueLabel,
                        falseLabel
                )
        );
        statements.add(new ILabelStatement(trueLabel));
        statements.add(new Move(
                new Const(1),
                new TempExpr(frame.rv())
        ));
        statements.add(new ILabelStatement(falseLabel));
        statements.add(new Move(
                new Const(0),
                new TempExpr(frame.rv())
        ));
        statements.add(endLabel);

        final var method = createMethod(statements);
        final var methodGenerator = createMethodWriter();

        methodGenerator.generate(method);
        final var actual = asString(methodGenerator.getTextifier().getText());

        final var expected = """
                   L0
                    ILOAD 0
                    IFNE L1
                   L1
                    ICONST_1
                    IRETURN
                   L2
                    ICONST_0
                    IRETURN
                   L3
                    LOCALVARIABLE this LSomeClass; L0 L3 0
                    MAXSTACK = -1
                    MAXLOCALS = -1
                """;

        assertEquals(
                expected,
                actual
        );
    }

    @Test
    void classLiteral() {
        final var fieldAccess = new IFieldAccess(
                "java.lang.String",
                "class",
                IReferenceType.createClassType(
                        null,
                        "java.lang.Class",
                        List.of()
                ),
                true
        );

        final var frame = new Frame();
        frame.allocateLocal(
                "stringType",
                IReferenceType.createClassType(
                        null,
                        "java.lang.String",
                        List.of()
                ),
                false
        );

        final var binOp = new BinOp(
                new IFieldAccess(
                        "SomeClass",
                        "stringType",
                        IReferenceType.createClassType(null, "java.lang.class", List.of()),
                        false
                ),
                Tag.ASSIGN,
                fieldAccess
        );

        final List<IStatement> statements = List.of(new IExpressionStatement(binOp));

        final var method = createMethod(statements);
        final var methodGenerator = createMethodWriter();

        methodGenerator.generate(method);

        final var expected = """
                   L0
                    ALOAD 0
                    LDC Ljava/lang/String;.class
                    PUTFIELD SomeClass.stringType : Ljava/lang/class;
                   L1
                    LOCALVARIABLE this LSomeClass; L0 L1 0
                    MAXSTACK = -1
                    MAXLOCALS = -1
                """;

        final var actual = asString(methodGenerator.getTextifier().getText());

        assertEquals(expected, actual);
    }

    @Test
    void stringConcat() {
        final var frame = new Frame();

        final var thisType = IReferenceType.createClassType(
                null,
                "SomeClass",
                List.of()
        );

        frame.allocateLocal(
                "this",
                thisType,
                false
        );

        final var thisExp = new TempExpr(
                0,
                thisType
        );

        final var stringConst = new Const("Hello");
        stringConst.setType(IReferenceType.createClassType(
                null,
                "java.lang.String",
                List.of()
        ));

        final var binOp = new BinOp(
                stringConst,
                Tag.ADD,
                new Call(
                        InvocationType.VIRTUAL,
                        new Name("SomeClass"),
                        new Name("getName"),
                        IReferenceType.createClassType(
                                null,
                                "java.lang.String",
                                List.of()
                        ),
                        List.of(),
                        List.of(thisExp)
                )
        );

        final List<IStatement> statements = List.of(new IExpressionStatement(binOp));

        final var method = createMethod(statements);
        final var methodGenerator = createMethodWriter();

        methodGenerator.generate(method);

        final var actual = asString(methodGenerator.getTextifier().getText());
        final var expected = """
                   L0
                    ALOAD 0
                    INVOKEVIRTUAL SomeClass.getName ()Ljava/lang/String;
                    INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String; [
                      // handle kind 0x6 : INVOKESTATIC
                      java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;
                      // arguments:
                      "Hello\\u0001"
                    ]
                   L1
                    LOCALVARIABLE this LSomeClass; L0 L1 0
                    MAXSTACK = -1
                    MAXLOCALS = -1
                """;

        System.out.println(actual);

        assertEquals(expected, actual);

        assertEquals(expected, actual);
    }

    private String asString(final List<Object> list) {
        return list.stream()
                .map(Object::toString)
                .collect(Collectors.joining(""));
    }
}

