package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.MethodSignature;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.SignatureParser;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;


public class TypeBuilder {

    private final AsmTypeResolver asmTypeResolver;

    public TypeBuilder(final AsmTypeResolver asmTypeResolver) {
        this.asmTypeResolver = asmTypeResolver;
    }

    public TypeMirror parseFieldSignature(final String signature) {
        final var reader = new SignatureReader(signature);

        final var signatureBuilder =
                new SignatureParser(Opcodes.ASM9, asmTypeResolver.getClassElementLoader());

        reader.accept(signatureBuilder);
        return signatureBuilder.createFieldType();
    }

    public MethodSignature parseMethodSignature(final String signature) {
        final var reader = new SignatureReader(signature);

        final var signatureBuilder =
                new SignatureParser(Opcodes.ASM9, asmTypeResolver.getClassElementLoader());

        reader.accept(signatureBuilder);

        return signatureBuilder.createMethodSignature();
    }

    public void parseClassSignature(final String signature,
                                    final ClassSymbol classSymbol) {
        final var reader = new SignatureReader(signature);
        final var signatureBuilder =
                new SignatureParser(Opcodes.ASM9, asmTypeResolver.getClassElementLoader());

        reader.accept(signatureBuilder);
        final var formalTypeParameters = signatureBuilder.createFormalTypeParameters();
        final var superType = signatureBuilder.createSuperType();
        final var interfaceTypes = signatureBuilder.createInterfaceTypes();

        formalTypeParameters.stream()
                .map(it -> (TypeParameterElement) it.asElement())
                .forEach(classSymbol::addTypeParameter);

        classSymbol.setSuperClass(superType);
        interfaceTypes.forEach(classSymbol::addInterface);

        final var type = new CClassType(
                null,
                classSymbol,
                formalTypeParameters
        );

        classSymbol.setType(type);
    }

    public TypeMirror build(final String signature) {
        final var reader = new SignatureReader(signature);
        final var signatureBuilder =
                new SignatureParser(Opcodes.ASM9, asmTypeResolver.getClassElementLoader());

        reader.accept(signatureBuilder);
        return signatureBuilder.createFieldType();
    }
}
