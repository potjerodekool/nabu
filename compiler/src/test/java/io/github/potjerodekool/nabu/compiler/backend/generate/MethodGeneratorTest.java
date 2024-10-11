package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.InvocationType;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IExpressionStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Move;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.awt.color.ICC_ProfileRGB;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodGeneratorTest {

    @Test
    void generate() {
        final var classWriter =
                new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);

        final var methodGenerator = new MethodGenerator(
                classWriter,
                "SomeClass"
        );

        final var method = new MethodSymbol(ElementKind.METHOD,"someMethod", null);
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
                        Unop.Oper.NOT,
                        new Const(true),
                        true
                ))
        ));

        final var frag = new ProcFrag(
                Flags.STATIC,
                "SomeMethod",
                List.of(),
                IPrimitiveType.VOID,
                frame,
                List.of(call)
        );
        method.setFrag(frag);

        methodGenerator.generate(method);

        methodGenerator.getTextifier().getText().forEach(System.out::println);

    }
}