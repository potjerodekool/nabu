package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.MethodSignature;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.SignatureParser;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.TypeMirror;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;

public class TypeBuilder {

    public TypeMirror parseFieldSignature(final String signature,
                                          final AsmTypeResolver asmTypeResolver,
                                          final ModuleSymbol moduleSymbol) {
        final var reader = new SignatureReader(signature);

        final var signatureBuilder =
                new SignatureParser(Opcodes.ASM9, asmTypeResolver.getClassElementLoader(), moduleSymbol);

        reader.accept(signatureBuilder);
        return signatureBuilder.createFieldType();
    }

    public MethodSignature parseMethodSignature(final String signature,
                                                final AsmTypeResolver asmTypeResolver,
                                                final ModuleSymbol moduleSymbol) {
        final var reader = new SignatureReader(signature);

        final var signatureBuilder =
                new SignatureParser(Opcodes.ASM9, asmTypeResolver.getClassElementLoader(), moduleSymbol);

        reader.accept(signatureBuilder);

        return signatureBuilder.createMethodSignature();
    }

    public void parseClassSignature(final String signature,
                                    final ClassSymbol classSymbol,
                                    final AsmTypeResolver asmTypeResolver,
                                    final ModuleSymbol moduleSymbol) {
        final var enclosingElement = classSymbol.getEnclosingElement();
        final var outerType = enclosingElement instanceof TypeElement enclosingTypeElement
                ? enclosingTypeElement.asType()
                : null;

        final var reader = new SignatureReader(signature);
        final var signatureBuilder =
                new SignatureParser(Opcodes.ASM9, asmTypeResolver.getClassElementLoader(), moduleSymbol);

        reader.accept(signatureBuilder);
        final var formalTypeParameters = signatureBuilder.createFormalTypeParameters();
        final var superType = signatureBuilder.createSuperType();
        final var interfaceTypes = signatureBuilder.createInterfaceTypes();

        classSymbol.setSuperClass(superType);
        classSymbol.setInterfaces(interfaceTypes);

        final var type = (CClassType) classSymbol.asType();
        type.setOuterType(outerType);
        type.setTypeArguments(formalTypeParameters);
    }

    public TypeMirror build(final String signature,
                            final AsmTypeResolver asmTypeResolver) {
        final var reader = new SignatureReader(signature);
        final var signatureBuilder =
                new SignatureParser(Opcodes.ASM9, asmTypeResolver.getClassElementLoader(), null);

        reader.accept(signatureBuilder);
        return signatureBuilder.createFieldType();
    }
}
