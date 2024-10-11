package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.Options;
import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static io.github.potjerodekool.nabu.compiler.resolve.ClassUtils.toInternalName;

public class CodeGenerator {

    private final ClassWriter classWriter = new ClassWriter(
            ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES
    );

    private String internalName;

    public void generate(final CClassDeclaration clazz,
                         final Options options) {
        final var classSymbol = clazz.classSymbol;
        final var fileObject = classSymbol.getFileObject();
        final var fileName = fileObject.getFileName();

        final var access = Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER;
        internalName = toInternalName(clazz.getQualifiedName());
        final String signature = null;
        final var superName = Type.getType(Object.class).getInternalName();
        final var interfaces = new String[0];

        final var classVersion = resolveClassVersion(options.getTargetVersion());

        classWriter.visit(classVersion, access, internalName, signature, superName, interfaces);
        classWriter.visitSource(fileName, null);

        clazz.getEnclosedElements().forEach(element -> {
            if (element instanceof CFunction function) {
                generate(function.methodSymbol);
            }
        });

        classWriter.visitEnd();
    }

    private int resolveClassVersion(final Options.JavaVersion version) {
        if (version == null) {
            return Opcodes.V17;
        } else {
            return version.getValue();
        }
    }

    private void generate(final MethodSymbol methodSymbol) {
        if (methodSymbol.getFrag() != null) {
            new MethodGenerator(classWriter, internalName).generate(methodSymbol);
        }
    }

    public byte[] getBytecode() {
        return classWriter.toByteArray();
    }
}
