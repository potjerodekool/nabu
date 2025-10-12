package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.test.TestClassElementLoader;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmMethodByteCodeGenerator;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.InvocationType;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.IrCleaner;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.impl.CVoidType;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType.createClassType;
import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AsmMethodByteCodeGeneratorTest {

    private final TestClassElementLoader loader = new TestClassElementLoader();

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
                .simpleName("someMethod")
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
                createClassType("OtherClass"),
                new Name("someMethod"),
                IPrimitiveType.VOID,
                of(IPrimitiveType.BOOLEAN),
                of(new Unop(Tag.NOT, new Const(true)))
        ));

        final var method = createMethod(
                of(call)
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
                    IFEQ L1
                   L2
                    ICONST_1
                    IRETURN
                   L1
                    ICONST_0
                    IRETURN
                   L3
                    IRETURN
                   L4
                    LOCALVARIABLE this LSomeClass; L0 L4 0
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
        final var thisType = createClassType(
                null,
                "SomeClass",
                of()
        );

        final var frame = new Frame();
        frame.allocateLocal(
                Constants.THIS,
                thisType,
                false
        );

        frame.allocateLocal(
                "stringType",
                createClassType(
                        null,
                        "java.lang.String",
                        of()
                ),
                false
        );


        final var right = new ExpList(
                new TempExpr(0, thisType),
                new IFieldAccess(
                        new Name("java.lang.String"),
                        "class",
                        createClassType(
                                null,
                                "java.lang.Class",
                                of()
                        ),
                        true
                )
        );

        final var binOp = new BinOp(
                new IFieldAccess(
                        new Name("SomeClass"),
                        "stringType",
                        createClassType(null, "java.lang.class", of()),
                        false
                ),
                Tag.ASSIGN,
                right
        );

        final List<IStatement> statements = of(new IExpressionStatement(binOp));

        final var method = createMethod(statements);
        final var methodGenerator = createMethodWriter();

        methodGenerator.generate(method);

        final var expected = """
                   L0
                    ALOAD 0
                    LDC Ljava/lang/String;.class
                    PUTFIELD SomeClass.stringType : Ljava/lang/class;
                    GOTO L1
                   L1
                    RETURN
                   L2
                    LOCALVARIABLE this LSomeClass; L0 L2 0
                    MAXSTACK = -1
                    MAXLOCALS = -1
                """;

        final var actual = asString(methodGenerator.getTextifier().getText());

        assertEquals(expected, actual);
    }

    @Test
    void stringConcat() {
        final var frame = new Frame();

        final var thisType = createClassType(
                null,
                "SomeClass",
                of()
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
        stringConst.setType(createClassType(
                null,
                "java.lang.String",
                of()
        ));

        final var binOp = new BinOp(
                stringConst,
                Tag.ADD,
                new Call(
                        InvocationType.VIRTUAL,
                        createClassType(
                                null,
                                "SomeClass",
                                of()
                        ),
                        new Name("getName"),
                        createClassType(
                                null,
                                "java.lang.String",
                                of()
                        ),
                        of(),
                        of(thisExp)
                )
        );

        final List<IStatement> statements = of(new IExpressionStatement(binOp));

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
                    GOTO L1
                   L1
                    ARETURN
                   L2
                    LOCALVARIABLE this LSomeClass; L0 L2 0
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

    @Test
    void newArray() {
        final var methodGenerator = createMethodWriter();

        final var newArrayExpression = new ITypeExpression(
                ITypeExpression.Kind.NEWARRAY,
                createClassType(
                        null,
                        "java.lang.String",
                        of()
                ),
                new Const(2)
        );

        final var statements = List.<IStatement>of(
                new IExpressionStatement(newArrayExpression),
                new IExpressionStatement(InstExpression.dup()),
                new IExpressionStatement(new Const(0)),
                new IExpressionStatement(new Const("Hello")),
                new IExpressionStatement(InstExpression.arrayStore()),
                new IExpressionStatement(InstExpression.dup()),
                new IExpressionStatement(new Const(1)),
                new IExpressionStatement(new Const("World")),
                new IExpressionStatement(InstExpression.arrayStore())
        );

        final var method = createMethod(statements);
        methodGenerator.generate(method);

        methodGenerator.getTextifier().getText().forEach(System.out::print);
    }

    @Test
    void visitFieldAccess() {
        final var methodGenerator = createMethodWriter();

        final var fieldAccess = new IFieldAccess(
                new Name("SomeClass"),
                "text",
                createClassType(
                        null,
                        "java.lang.String",
                        of()
                ),
                false
        );

        final var statements = of(
                new IExpressionStatement(new Mem(new TempExpr(0, IReferenceType.createClassType("SomeClass")))) ,
                new IExpressionStatement(fieldAccess),
                new Move(
                        new TempExpr(),
                        new TempExpr(new Temp(Frame.RV))
                )
        );

        final var method = createMethod(statements);
        methodGenerator.generate(method);

        methodGenerator.getTextifier().getText().forEach(System.out::print);
    }

    @Test
    void visitFieldAccess2() {
        final var methodGenerator = createMethodWriter();

        final var fieldAccess = new IFieldAccess(
                new TempExpr(0, createClassType(null, "SomeClass", of())),
                "text",
                createClassType(
                        null,
                        "java.lang.String",
                        of()
                ),
                false
        );

        final var statements = of(
                new IExpressionStatement(fieldAccess),
                new Move(
                        new TempExpr(),
                        new TempExpr(new Temp(Frame.RV))
                )
        );

        final var method = createMethod(statements);
        methodGenerator.generate(method);

        methodGenerator.getTextifier().getText().forEach(System.out::print);
    }

    @Test
    void generateSwitch() {
        final var defaultLabel = new ILabel();
        final var oneLabel = new ILabel();

        final var switchStatement = new ISwitchStatement(
                new Ex(new Const(10)),
                defaultLabel,
                new int[]{1},
                new ILabel[]{oneLabel}
        );

        final var method = createMethod(of(
                switchStatement,
                new ILabelStatement(oneLabel),
                new ILabelStatement(defaultLabel)
        ));
        final var methodGenerator = createMethodWriter();
        methodGenerator.generate(method);
        methodGenerator.getTextifier().getText().forEach(System.out::print);
    }

    @Test
    void test() {
        final var symbol = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("result")
                .type(new CPrimitiveType(TypeKind.BOOLEAN))
                .build();

        final var l = List.of(
                new IVariableDeclaratorStatement(
                        symbol,
                        IPrimitiveType.BOOLEAN,
                        new Const(false),
                        new TempExpr(2, IPrimitiveType.BOOLEAN)
                ),
                new CJump(
                        Tag.EQ,
                        new BinOp(
                                new TempExpr(1, createClassType("java.lang.String")),
                                Tag.EQ,
                                new Const(null)
                        ),
                        new Const(1),
                        new ILabel("L18"),
                        new ILabel("L19")
                ),
                new ILabelStatement(new ILabel("L18")),
                new IExpressionStatement(
                        new BinOp(
                                new TempExpr(2, IPrimitiveType.BOOLEAN),
                                Tag.ASSIGN,
                                new Const(true)
                        )
                ),
                new Jump(new ILabel("L20")),
                new ILabelStatement(new ILabel("L19")),
                new CJump(
                        Tag.EQ
                        ,new BinOp(
                                new TempExpr(1, createClassType("java.lang.String")),
                                Tag.NE,
                                new Const(null)
                        ),
                        new Const(1),
                        new ILabel("L15"),
                        new ILabel("L17")
                ),
                new ILabelStatement(new ILabel("L15")),
                new IExpressionStatement(
                        new BinOp(
                                new TempExpr(2, IPrimitiveType.BOOLEAN),
                                Tag.ASSIGN,
                                new Const(false)
                        )
                ),
                new Jump(new ILabel("L17")),
                new ILabelStatement(new ILabel("L17")),
                new Jump(new ILabel("L20")),
                new ILabelStatement(new ILabel("L20")),
                new Move(
                        new TempExpr(2, IPrimitiveType.BOOLEAN),
                        new TempExpr(Frame.RV, null)
                )
        );

        final var method = createMethod(l);

        final var javaLangPackage = loader.findOrCreatePackage(
                null,
                "java.lang"
        );

        final var stringClass = new ClassSymbolBuilder()
                .simpleName("String")
                .enclosingElement(javaLangPackage)
                .build();

        final var paramType = new CClassType(
                null,
                stringClass,
                List.of()
        );

        final var parameter = new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName("s")
                .type(paramType)
                        .build();

        method.addParameter(parameter);

        try (final var ignored = Mockito.mockStatic(IrCleaner.class)) {
            final var frag = method.getFrag();

            Mockito.when(IrCleaner.cleanUp(ArgumentMatchers.any()))
                    .thenReturn(frag);
            Mockito.when(IrCleaner.insertReturnIfNeeded(ArgumentMatchers.any()))
                    .thenAnswer(answer -> answer.getArgument(0));

            final var methodGenerator = createMethodWriter();
            methodGenerator.generate(method);
            methodGenerator.getTextifier().getText().forEach(System.out::print);
            final var actual = methodGenerator.getTextifier().getText().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(""));

            final var expected = """
                        ICONST_0
                        ISTORE 2
                       L0
                        ALOAD 1
                        IFNONNULL L1
                       L2
                        ICONST_1
                        ISTORE 2
                       L3
                        GOTO L4
                       L1
                        ALOAD 1
                        IFNULL L5
                       L6
                        ICONST_0
                        ISTORE 2
                       L7
                        GOTO L5
                       L5
                        GOTO L4
                       L4
                        ILOAD 2
                        IRETURN
                        LOCALVARIABLE this LSomeClass; L0 L4 0
                        LOCALVARIABLE s Ljava/lang/String; L0 L4 1
                        LOCALVARIABLE result Z L0 L4 2
                        MAXSTACK = -1
                        MAXLOCALS = -1
                    """;

            assertEquals(expected, actual);
        }
    }


}
