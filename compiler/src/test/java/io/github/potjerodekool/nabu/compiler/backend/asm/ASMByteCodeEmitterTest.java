package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.backend.ASMTestUtils;
import io.github.potjerodekool.nabu.compiler.backend.ir.TypeMirrorToIRType;
import io.github.potjerodekool.nabu.compiler.ir.IRBuilder;
import io.github.potjerodekool.nabu.compiler.ir.IRField;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.lang.Flags;
import io.github.potjerodekool.nabu.tools.Constants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ASMByteCodeEmitterTest extends AbstractCompilerTest {

    @Test
    void emit() {
        final var recordType = getCompilerContext().getClassElementLoader().loadClass(
                getCompilerContext().getModules().getJavaBase(),
                "java/lang/Record"
        ).asType();

        final var emitter = new ASMByteCodeEmitter();
        final var flags = Flags.PUBLIC + Flags.FINAL + Flags.RECORD;

        final var irBuilder = new IRBuilder(flags, "MyClass");
        irBuilder.superType(new IRType.Ptr(IRType.I8, TypeMirrorToIRType.toJvmDescriptor(recordType)));

        final var stringType = loadClass(Constants.STRING).asType();
        final var stringDesc = TypeMirrorToIRType.toJvmDescriptor(stringType);

        irBuilder.field(IRField.recordComponent("id", new IRType.Ptr(IRType.I8, stringDesc)));
        irBuilder.field((IRField.recordComponent("name", new IRType.Ptr(IRType.I8, stringDesc))));

        irBuilder.field(IRField.field(
                Flags.PRIVATE +  Flags.FINAL,
                "id", new IRType.Ptr(IRType.I8, stringDesc),
                null
        ));

        irBuilder.field(IRField.field(
                Flags.PRIVATE + Flags.FINAL,
                "name", new IRType.Ptr(IRType.I8, stringDesc),
                null
        ));

        final var module = irBuilder.build();

        emitter.emit(module);
        final var bytecode = emitter.getBytecode();
        final var actual = ASMTestUtils.byteCodeToText(bytecode);
        final var expected = loadResource("ASMByteCodeEmitterTest/record.txt");
        assertEquals(expected, actual);
    }

    protected String loadResource(final String resourceName) {
        try (var resource = resolveResource(resourceName)) {
            return new String(resource.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private InputStream resolveResource(final String resourceName) throws IOException {
        final var input = getClass().getClassLoader().getResourceAsStream(resourceName);

        if (input != null) {
            return input;
        } else {
            final var absolute = Paths.get("src/test/resources/" + resourceName).toAbsolutePath();
            return Files.newInputStream(absolute);
        }
    }
}