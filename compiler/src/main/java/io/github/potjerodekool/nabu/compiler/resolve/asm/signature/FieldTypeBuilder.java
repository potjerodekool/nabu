package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class FieldTypeBuilder extends AbstractSignatureVisitor {

    private TypeMirror fieldType;

    public FieldTypeBuilder(final int api,
                            final AsmClassElementLoader loader) {
        super(api, loader, null);
    }

    @Override
    protected void setSuperClass(final TypeMirror superType) {
        this.fieldType = superType;
    }

    public TypeMirror getFieldType() {
        return fieldType;
    }
}
