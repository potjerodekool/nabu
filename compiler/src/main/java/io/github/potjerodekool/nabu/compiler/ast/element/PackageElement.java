package io.github.potjerodekool.nabu.compiler.ast.element;

public class PackageElement extends TypeSymbol implements QualifiedNameable {

    private String qualifiedName;

    public PackageElement(final PackageElement parentPackage,
                          final String packageName) {
        super(ElementKind.PACKAGE, packageName, parentPackage);
        if (packageName.contains(".")) {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public String getQualifiedName() {
        if (qualifiedName != null) {
            return qualifiedName;
        }

        final var parentPackage = (PackageElement) getEnclosingElement();

        if (parentPackage == null) {
            qualifiedName = getSimpleName();
        } else {
            final var parentName = parentPackage.getQualifiedName();
            qualifiedName = parentName + "." + getSimpleName();
        }
        return qualifiedName;
    }
}
