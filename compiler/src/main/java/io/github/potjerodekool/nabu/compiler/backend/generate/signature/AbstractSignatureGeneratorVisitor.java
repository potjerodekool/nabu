package io.github.potjerodekool.nabu.compiler.backend.generate.signature;

import io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature.AsmISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.*;

import java.util.stream.Collectors;

public abstract class AbstractSignatureGeneratorVisitor implements ITypeVisitor<String, Void> {

    @Override
    public String visitIntersectionType(final IIntersectionType intersectionType, final Void param) {
        return intersectionType.getBounds().stream()
                .map(referenceType -> (IReferenceType) referenceType)
                .map(referenceType -> referenceType.accept(this, param))
                .collect(Collectors.joining(""));
    }

    @Override
    public String visitPrimitiveType(final IPrimitiveType primitiveType, final Void param) {
        return AsmISignatureGenerator.toAsmType(primitiveType).getDescriptor();
    }

    @Override
    public String visitReferenceType(final IReferenceType referenceType, final Void param) {
        final var asmType = AsmISignatureGenerator.toAsmType(referenceType);
        if (!referenceType.getTypeArguments().isEmpty()) {
            final var typeArgs = referenceType.getTypeArguments().stream()
                    .map(ta -> ta.accept(this, param))
                    .collect(Collectors.joining("", "<", ">"));
            var name = asmType.getDescriptor();
            name = name.substring(0, name.length() - 1);
            return name + typeArgs + ";";
        } else {
            return asmType.getDescriptor();
        }
    }


    @Override
    public String visitWildcardType(final IWildcardType wildcardType, final Void param) {
        return "*";
    }

    @Override
    public String visitArrayType(final IArrayType arrayType, final Void param) {
        final var componentType = arrayType.getComponentType().accept(this, param);
        return "[" + componentType;
    }
}
