package io.github.potjerodekool.nabu.compiler.ast.symbol;

import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AnnotationDeProxyProcessor;
import io.github.potjerodekool.nabu.compiler.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.util.Types;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;

import java.lang.annotation.Annotation;
import java.util.*;

public abstract class Symbol implements Element {

    private String simpleName;

    private ElementKind kind;

    private Symbol owner;

    private AbstractType type;

    private long flags;

    protected TypeMirror erasureType;

    private final List<CompoundAttribute> annotationMirrors = new ArrayList<>();

    private boolean deProxyAnnotations = true;

    private FileObject sourceFile;

    private FileObject classFile;

    private Completer completer;

    private boolean isError = false;

    public Symbol(final ElementKind kind,
                  final long flags,
                  final String name,
                  final AbstractType type,
                  final Symbol owner) {

        this.kind = kind;
        this.flags = flags;
        setSimpleName(name);
        this.type = type;
        setEnclosingElement(owner);
        this.completer = Completer.NULL_COMPLETER;
    }

    public static String createFlatName(final Symbol owner,
                                        final String name) {
        if (owner == null || owner instanceof PackageSymbol packageSymbol
            && packageSymbol.isUnnamed()) {
            return name;
        } else {
            final var separator = owner.getKind().isDeclaredType() ? '$' : '.';
            final var ownerName = owner.getFlatName();
            return ownerName + separator + name;
        }
    }

    public static String createFlatName(final String packageName) {
        return packageName.replace('/', '.');
    }

    @Override
    public String getSimpleName() {
        return simpleName;
    }

    public void setSimpleName(final String simpleName) {
        this.simpleName = simpleName;
    }

    public String getQualifiedName() {
        return simpleName;
    }

    public String getBinaryName() {
        return getQualifiedName();
    }

    public String getFlatName() {
        return getQualifiedName();
    }

    @Override
    public ElementKind getKind() {
        return kind;
    }

    public void setKind(final ElementKind kind) {
        this.kind = kind;
    }

    public FileObject getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(final FileObject sourceFile) {
        this.sourceFile = sourceFile;
    }

    public FileObject getClassFile() {
        return classFile;
    }

    public void setClassFile(final FileObject classFile) {
        if (classFile.getKind() != FileObject.CLASS_KIND) {
            throw new UnsupportedOperationException();
        }

        this.classFile = classFile;
    }

    @Override
    public Symbol getEnclosingElement() {
        return owner;
    }

    public void setEnclosingElement(final Symbol enclosingElement) {
        this.owner = enclosingElement;
        onEnclosingChanged();
    }

    protected void onEnclosingChanged() {
    }

    @Override
    public List<Symbol> getEnclosedElements() {
        return List.of();
    }

    @Override
    public AbstractType asType() {
        return type;
    }

    public void setType(final AbstractType type) {
        this.type = type;
    }

    public List<? extends TypeParameterElement> getTypeParameters() {
        return type.getTypeArguments().stream()
                .map(it -> (TypeVariable) it)
                .map(it -> (TypeVariableSymbol) it.asElement())
                .toList();
    }

    @Override
    public Set<Modifier> getModifiers() {
        complete();
        return Flags.createModifiers(this.flags);
    }

    public long getFlags() {
        return flags;
    }

    public boolean hasFlag(final long flag) {
        return Flags.hasFlag(this.flags, flag);
    }

    public void setFlags(final long flags) {
        this.flags = flags;
    }

    public boolean isPublic() {
        return hasFlag(Flags.PUBLIC);
    }

    public boolean isPrivate() {
        return hasFlag(Flags.PRIVATE);
    }

    public boolean isStatic() {
        return hasFlag(Flags.STATIC);
    }

    public boolean isFinal() {
        return hasFlag(Flags.FINAL);
    }

    public boolean isSynthetic() {
        return hasFlag(Flags.SYNTHETIC);
    }

    public boolean isAbstract() {
        return hasFlag(Flags.ABSTRACT);
    }

    public boolean isNative() {
        return hasFlag(Flags.NATIVE);
    }

    public TypeMirror erasure(final Types types) {
        if (erasureType == null)
            erasureType = types.erasure(type);
        return erasureType;
    }

    @Override
    public List<CompoundAttribute> getAnnotationMirrors() {
        deProxyAnnotations();
        return annotationMirrors;
    }

    private void deProxyAnnotations() {
        if (deProxyAnnotations) {
            final var processor = new AnnotationDeProxyProcessor();
            final var newAnnotations = this.annotationMirrors.stream()
                    .map(processor::process)
                    .toList();
            this.annotationMirrors.clear();
            this.annotationMirrors.addAll(newAnnotations);
            deProxyAnnotations = false;
        }
    }

    public void setAnnotations(final List<? extends AnnotationMirror> annotations) {
        this.deProxyAnnotations = true;
        this.annotationMirrors.addAll(
                annotations.stream()
                        .map(it -> (CompoundAttribute) it)
                        .toList()
        );
    }

    public void addAnnotationMirror(final AnnotationMirror annotationMirror) {
        this.deProxyAnnotations = true;
        this.annotationMirrors.add((CompoundAttribute) annotationMirror);
    }

    @Override
    public <A extends Annotation> A getAnnotation(final Class<A> annotationType) {
        return null;
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(final Class<A> annotationType) {
        return null;
    }

    @Override
    public CompoundAttribute attribute(final TypeElement typeElement) {
        return getAnnotationMirrors().stream()
                .filter(it -> it.getType().asTypeElement() == typeElement)
                .findFirst()
                .orElse(null);
    }

    public abstract <R, P> R accept(SymbolVisitor<R, P> v, P p);

    public boolean isNoModule() {
        return false;
    }

    public boolean isError() {
        return isError;
    }

    public void setError(final boolean error) {
        isError = error;
    }

    public void setCompleter(final Completer completer) {
        this.completer = completer;
    }

    public void complete() {
        this.completer.complete(this);
    }

    public WritableScope getMembers() {
        return null;
    }

    public boolean isAccessibleIn(final Symbol clazz,
                                  final Types types) {
        if (Flags.hasFlag(flags, Flags.PUBLIC)) {
            return true;
        } else if (Flags.hasFlag(flags, Flags.PRIVATE)) {
            return this.getEnclosingElement() == clazz;
        } else if (Flags.hasFlag(flags, Flags.PROTECTED)) {
            return !Flags.hasFlag(flags, Flags.INTERFACE);
        } else {
            final var packageSymbol = this.findPackageSymbol();

            for (var symbol = clazz;
                 symbol != null && symbol != this.getEnclosingElement();
                 symbol = (Symbol) types.supertype(symbol.type).asElement()) {

                while (symbol.type.isTypeVariable()) {
                    symbol = (Symbol) symbol.type.getUpperBound().asElement();
                }

                if (symbol.type.isError()) {
                    return true;
                } else if (symbol.findPackageSymbol() != packageSymbol) {
                    return false;
                }
            }

            return !clazz.hasFlag(Flags.INTERFACE);
        }
    }

    public PackageSymbol findPackageSymbol() {
        var symbol = this;

        while (symbol.getKind() != ElementKind.PACKAGE) {
            symbol = symbol.getEnclosingElement();
        }

        return (PackageSymbol) symbol;
    }

    public boolean isSubClass(final Symbol base,
                              final TypesImpl types) {
        if (this == base) {
            return true;
        } else if (base.hasFlag(Flags.INTERFACE)) {
            for (var t = type; t.isDeclaredType(); types.supertype(t)) {
                if (types.interfaces(t).stream()
                        .anyMatch(it -> ((Symbol)it.asElement()).isSubClass(base, types))) {
                    return true;
                }
            }
        } else {
            for (var t = type; t.isDeclaredType(); types.supertype(t)) {
                if (t.asElement() == base) {
                    return true;
                }
            }
        }
        return false;
    }
}
