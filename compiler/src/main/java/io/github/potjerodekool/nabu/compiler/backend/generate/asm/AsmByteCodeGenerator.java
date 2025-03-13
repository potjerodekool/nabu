package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.Options;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.*;
import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.annotation.AsmAnnotationGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.signature.SignatureGenerator;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Set;

import static io.github.potjerodekool.nabu.compiler.resolve.ClassUtils.getInternalName;

public class AsmByteCodeGenerator implements ByteCodeGenerator, SymbolVisitor<Void, Options> {

    private final ClassWriter classWriter = new ClassWriter(
            ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES
    );

    private String internalName;

    @Override
    public void generate(final ClassDeclaration clazz,
                         final Options options) {
        final var symbol = (Symbol) clazz.getClassSymbol();
        symbol.accept(this, options);
    }

    private int resolveAccess(final Set<Modifier> modifiers) {
        int access = 0;
        access += addOpcode(modifiers, Modifier.ABSTRACT, Opcodes.ACC_ABSTRACT);
        access += addOpcode(modifiers, Modifier.PUBLIC, Opcodes.ACC_PUBLIC);
        access += addOpcode(modifiers, Modifier.PRIVATE, Opcodes.ACC_PRIVATE);
        access += addOpcode(modifiers, Modifier.PROTECTED, Opcodes.ACC_PROTECTED);
        access += addOpcode(modifiers, Modifier.STATIC, Opcodes.ACC_STATIC);
        access += addOpcode(modifiers, Modifier.FINAL, Opcodes.ACC_FINAL);
        access += addOpcode(modifiers, Modifier.SYNTHETIC, Opcodes.ACC_SYNTHETIC);
        return access;
    }

    private int addOpcode(final Set<Modifier> modifiers,
                          final Modifier modifier,
                          final int opcode) {
        return modifiers.contains(modifier)
                ? opcode
                : 0;
    }

    private void generateAnnotations(final TypeElement typeElement) {
        typeElement.getAnnotationMirrors().forEach(annotation ->
                AsmAnnotationGenerator.generate(annotation, classWriter));
    }

    private int resolveClassVersion(final Options.JavaVersion version) {
        if (version == null) {
            return Opcodes.V17;
        } else {
            return version.getValue();
        }
    }

    @Override
    public byte[] getBytecode() {
        return classWriter.toByteArray();
    }

    @Override
    public Void visitClass(final ClassSymbol classSymbol, final Options options) {
        final var fileObject = classSymbol.getMetaData(StandardElementMetaData.FILE_OBJECT, FileObject.class);
        final var fileName = fileObject.getFileName();

        final var access = resolveAccess(classSymbol.getModifiers());
        internalName = getInternalName(classSymbol.getQualifiedName());
        final String signature = SignatureGenerator.getClassSignature(classSymbol);

        final var superName = Type.getType(Object.class).getInternalName();
        final var interfaces = new String[0];

        final var classVersion = resolveClassVersion(options.getTargetVersion());

        classWriter.visit(classVersion, access, internalName, signature, superName, interfaces);
        classWriter.visitSource(fileName, null);

        generateAnnotations(classSymbol);
        classSymbol.getEnclosedElements().forEach(e -> e.accept(this, null));
        classWriter.visitEnd();

        return null;
    }

    @Override
    public Void visitUnknown(final Symbol symbol, final Options options) {
        return null;
    }

    @Override
    public Void visitMethod(final MethodSymbol methodSymbol, final Options options) {
        new AsmMethodByteCodeGenerator(classWriter, internalName).generate(methodSymbol);
        return null;
    }

    @Override
    public Void visitVariable(final VariableSymbol variableSymbol, final Options options) {
        new AsmFieldByteCodeGenerator(classWriter).generate(variableSymbol);
        return null;
    }
}
