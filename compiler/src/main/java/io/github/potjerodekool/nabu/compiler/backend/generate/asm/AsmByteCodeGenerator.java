package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.symbol.*;
import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.annotation.AsmAnnotationGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.signature.SignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.ToIType;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassUtils;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AccessUtils;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.ModuleDeclaration;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator.toAsmType;
import static io.github.potjerodekool.nabu.compiler.resolve.internal.ClassUtils.getInternalName;

public class AsmByteCodeGenerator implements ByteCodeGenerator, SymbolVisitor<Void, CompilerOptions> {

    private final ClassWriter classWriter = new ClassWriter(
            ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES
    );

    private String internalName;

    private final ToIType toIType = new ToIType();

    @Override
    public void generate(final ClassDeclaration clazz,
                         final CompilerOptions options) {
        final var symbol = (Symbol) clazz.getClassSymbol();
        symbol.accept(this, options);
    }

    @Override
    public void generate(final ModuleDeclaration moduleDeclaration,
                         final CompilerOptions options) {
        final var symbol = (Symbol) moduleDeclaration.getModuleSymbol();
        symbol.accept(this, options);
    }

    private int resolveAccess(final ClassSymbol classSymbol) {
        int access = AccessUtils.flagsToAccess(classSymbol.getFlags());

        if (classSymbol.getKind() == ElementKind.INTERFACE
                && !Flags.hasFlag(classSymbol.getFlags(), Flags.INTERFACE)) {
            access += Opcodes.ACC_INTERFACE;
        }

        return access;
    }

    private void generateAnnotations(final TypeElement typeElement) {
        typeElement.getAnnotationMirrors().forEach(annotation ->
                AsmAnnotationGenerator.generate(annotation, classWriter));
    }

    private void generatePermittedSubClasses(final TypeElement typeElement) {
        typeElement.getPermittedSubclasses().stream()
                .map(it -> (DeclaredType) it)
                .map(DeclaredType::asTypeElement)
                .map(it -> ClassUtils.getInternalName(it.getQualifiedName()))
                .forEach(classWriter::visitPermittedSubclass);
    }

    @Override
    public byte[] getBytecode() {
        return classWriter.toByteArray();
    }

    @Override
    public Void visitModule(final ModuleSymbol moduleSymbol, final CompilerOptions options) {
        var access = moduleSymbol.isSynthetic()
                ? Opcodes.ACC_SYNTHETIC
                : Opcodes.ACC_MANDATED;

        if (moduleSymbol.isOpen()) {
            access += Opcodes.ACC_OPEN;
        }

        final var visitor = classWriter.visitModule(
                moduleSymbol.getQualifiedName(),
                access,
                null
        );

        final int api = Opcodes.ASM9;
        final var moduleGenerator = new AsmModuleCodeGenerator(
                api,
                visitor
        );

        moduleSymbol.accept(moduleGenerator, null);

        return null;
    }

    @Override
    public Void visitClass(final ClassSymbol classSymbol, final CompilerOptions options) {
        final var fileObject = classSymbol.getSourceFile();
        final var fileName = fileObject.getFileName();

        final var access = resolveAccess(classSymbol);
        internalName = getInternalName(classSymbol.getQualifiedName());
        final String signature = SignatureGenerator.getClassSignature(classSymbol);


        final var superType = classSymbol.getSuperclass().accept(toIType, null);
        final var superName = toAsmType(superType).getInternalName();
        final var interfaces = classSymbol.getInterfaces().stream()
                .map(it -> it.accept(toIType, null))
                .map(AsmISignatureGenerator::toAsmType)
                .map(Type::getInternalName)
                .toArray(String[]::new);

        final var javaVersion = options.getTargetVersion();
        final var classVersion = javaVersion.getValue();

        classWriter.visit(classVersion, access, internalName, signature, superName, interfaces);
        classWriter.visitSource(fileName, null);

        generateAnnotations(classSymbol);
        generatePermittedSubClasses(classSymbol);

        classSymbol.getMembers().elements().stream()
                .map(it -> (Symbol) it)
                .forEach(e -> e.accept(this, null));
        classWriter.visitEnd();
        return null;
    }


    @Override
    public Void visitUnknown(final Symbol symbol, final CompilerOptions options) {
        return null;
    }

    @Override
    public Void visitMethod(final MethodSymbol methodSymbol, final CompilerOptions options) {
        new AsmMethodByteCodeGenerator(classWriter, internalName).generate(methodSymbol);
        return null;
    }

    @Override
    public Void visitVariable(final VariableSymbol variableSymbol, final CompilerOptions options) {
        new AsmFieldByteCodeGenerator(classWriter).generate(variableSymbol);
        return null;
    }
}
