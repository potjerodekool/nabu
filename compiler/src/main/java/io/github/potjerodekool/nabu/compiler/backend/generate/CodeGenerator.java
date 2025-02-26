package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.Options;
import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.StandardElementMetaData;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.CModifier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Set;

import static io.github.potjerodekool.nabu.compiler.resolve.ClassUtils.toInternalName;

public class CodeGenerator {

    private final ClassWriter classWriter = new ClassWriter(
            ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES
    );

    private String internalName;

    public void generate(final ClassDeclaration clazz,
                         final Options options) {
        final var classSymbol = clazz.classSymbol;
        final var fileObject = classSymbol.getMetaData(StandardElementMetaData.FILE_OBJECT, FileObject.class);
        final var fileName = fileObject.getFileName();

        final var access = resolveAccess(clazz.getModifiers());
        internalName = toInternalName(classSymbol.getQualifiedName());
        final String signature = generateSignature(classSymbol);

        final var superName = Type.getType(Object.class).getInternalName();
        final var interfaces = new String[0];

        final var classVersion = resolveClassVersion(options.getTargetVersion());

        classWriter.visit(classVersion, access, internalName, signature, superName, interfaces);
        classWriter.visitSource(fileName, null);

        clazz.getEnclosedElements().forEach(element -> {
            if (element instanceof Function function) {
                generate(function.methodSymbol);
            }
        });

        classWriter.visitEnd();
    }

    private String generateSignature(final TypeElement typeElement) {
        final List<? extends TypeParameterElement> typeParameters = typeElement.getTypeParameters();

        if (typeParameters.isEmpty()) {
            return null;
        } else {
            return  SignatureUtils.getClassSignature(
                    typeParameters,
                    typeElement.getSuperclass()
            );
        }
    }

    private int resolveAccess(final Set<CModifier> modifiers) {
        return Opcodes.ACC_SUPER + modifiers.stream()
                .mapToInt(this::toAccess)
                .reduce(Integer::sum)
                .orElse(0);
    }

    private int toAccess(final CModifier modifier) {
        return switch (modifier) {
            case ABSTRACT -> Opcodes.ACC_ABSTRACT;
            case PUBLIC -> Opcodes.ACC_PUBLIC;
            case PRIVATE -> Opcodes.ACC_PRIVATE;
            case PROTECTED -> Opcodes.ACC_PROTECTED;
            case STATIC -> Opcodes.ACC_STATIC;
            case FINAL -> Opcodes.ACC_FINAL;
        };
    }

    private int resolveClassVersion(final Options.JavaVersion version) {
        if (version == null) {
            return Opcodes.V17;
        } else {
            return version.getValue();
        }
    }

    private void generate(final ExecutableElement methodSymbol) {
        new MethodGenerator(classWriter, internalName).generate(methodSymbol);
    }

    public byte[] getBytecode() {
        return classWriter.toByteArray();
    }
}
