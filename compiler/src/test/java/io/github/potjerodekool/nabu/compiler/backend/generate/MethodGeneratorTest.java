package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.StandardElementMetaData;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.InvocationType;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.postir.canon.BasicBlocks;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

import java.util.ArrayList;
import java.util.List;

class MethodGeneratorTest {

    private ClassWriter createClassWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
    }

    @Test
    void generate() {
        final var classWriter = createClassWriter();

        final var methodGenerator = new MethodGenerator(
                classWriter,
                "SomeClass"
        );

        final var method = new MethodBuilder()
                .name("someMethod")
                .build();

        final var frame = new Frame();

        final var call = new IExpressionStatement(new DefaultCall(
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

        final var frag = new ProcFrag(
                Flags.STATIC,
                "SomeMethod",
                IPrimitiveType.VOID,
                frame,
                List.of(call)
        );
        method.setMetaData(StandardElementMetaData.FRAG, frag);

        methodGenerator.generate(method);

        methodGenerator.getTextifier().getText().forEach(System.out::println);
    }

    @Test
    void generateIf() {
        final var classWriter = createClassWriter();
        final var method = new MethodBuilder()
                .name("someMethod")
                .build();

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
                        new TempExpr(varIndex, frame, IPrimitiveType.BOOLEAN),
                        new Const(1),
                        trueLabel,
                        falseLabel
                )
        );
        statements.add(new ILabelStatement(trueLabel));
        statements.add(new Move(
                new Const(1),
                new TempExpr(frame.rv().getIndex(), frame, null)
        ));
        statements.add(new ILabelStatement(falseLabel));
        statements.add(new Move(
                new Const(0),
                new TempExpr(frame.rv().getIndex(), frame, null)
        ));
        statements.add(endLabel);

        final var frag = new ProcFrag(
                Flags.STATIC,
                "SomeMethod",
                IPrimitiveType.VOID,
                frame,
                statements
        );

        method.setMetaData(StandardElementMetaData.FRAG, frag);

        final var methodGenerator = new MethodGenerator(
                classWriter,
                "SomeClass"
        );

        methodGenerator.generate(method);
        methodGenerator.getTextifier().getText().forEach(System.out::println);
    }

    @Test
    void test() {
        final var statements = new ArrayList<IStatement>();
        statements.add(new ILabelStatement());
        statements.add(
                new Move(
                        new Const(10),
                        new TempExpr(1, null, null)
                )
        );
        statements.add(new ILabelStatement());

        statements.add(new ILabelStatement());
        statements.add(
                new Move(
                        new Const(10),
                        new TempExpr(2, null, null)
                )
        );
        statements.add(new ILabelStatement());

        final var basicBlocks = new BasicBlocks(statements);
        System.out.println(basicBlocks);

    }
}