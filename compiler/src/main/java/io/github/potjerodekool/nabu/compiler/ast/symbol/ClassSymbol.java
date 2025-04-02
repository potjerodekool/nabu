package io.github.potjerodekool.nabu.compiler.ast.symbol;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.compiler.type.ArrayType;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.util.Types;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Predicate;

public class ClassSymbol extends TypeSymbol implements TypeElement {

    private NestingKind nestingKind;

    private String qualifiedName;

    private String binaryName;

    private String flatName;

    private TypeMirror superclass;

    private final List<TypeMirror> interfaces = new ArrayList<>();

    private WritableScope members = null;

    private final List<Symbol> permitted = new ArrayList<>();

    private TypeMirror erasureField;

    public ClassSymbol() {
        this(0, null, null);
    }

    public ClassSymbol(final long flags,
                       final String name,
                       final Symbol owner) {
        this(
                ElementKind.CLASS,
                NestingKind.TOP_LEVEL,
                flags, name,
                new CClassType(
                        resolveOuterType(owner),
                        null,
                        List.of()
                ),
                owner,
                List.of(),
                List.of());
        asType().setElement(this);
    }

    private static CClassType resolveOuterType(final Symbol symbol) {
        if (symbol instanceof ClassSymbol classSymbol) {
            return (CClassType) classSymbol.asType();
        } else {
            return null;
        }
    }

    public ClassSymbol(final ElementKind kind,
                       final NestingKind nestingKind,
                       final long flags,
                       final String name,
                       final AbstractType type,
                       final Symbol owner,
                       final List<Symbol> enclosedElements,
                       final List<? extends AnnotationMirror> annotations) {
        super(kind, flags, name, type, owner);
        this.nestingKind = nestingKind;
        setAnnotations(annotations);
        setEnclosedElements(enclosedElements);
    }

    @Override
    public boolean isType() {
        return true;
    }

    @Override
    public ElementKind getKind() {
        complete();
        return super.getKind();
    }

    @Override
    public WritableScope getMembers() {
        complete();
        return members;
    }

    public void setMembers(final WritableScope members) {
        this.members = members;
    }

    public void addEnclosedElement(final Symbol enclosedElement) {
        initMembersIfNeeded();
        members.define(enclosedElement);
        enclosedElement.setEnclosingElement(this);
    }

    public void addEnclosedElement(final int index,
                                   final Symbol enclosedElement) {
        initMembersIfNeeded();
        members.define(index, enclosedElement);
        enclosedElement.setEnclosingElement(this);
    }

    private void initMembersIfNeeded() {
        if (members == null) {
            members = new WritableScope();
        }
    }

    public void setEnclosedElements(final List<Symbol> elements) {
        elements.forEach(this::addEnclosedElement);
    }

    @Override
    public String getBinaryName() {
        if (binaryName == null) {
            binaryName = createBinaryName(getEnclosingElement(), getSimpleName());
        }
        return binaryName;
    }

    @Override
    public String getFlatName() {
        if (flatName == null) {
            flatName = createFlatName(getEnclosingElement(), getSimpleName());
        }

        return flatName;
    }

    private String createBinaryName(final Symbol owner, final String name) {
        if (owner == null) {
            return name;
        } else {
            final var separator = owner.getKind().isDeclaredType() ? '$' : '.';
            final var ownerName = owner.getBinaryName();
            return ownerName + separator + name;
        }
    }

    @Override
    public NestingKind getNestingKind() {
        return nestingKind;
    }

    public void setNestingKind(final NestingKind nestingKind) {
        this.nestingKind = nestingKind;
    }

    private void resolveQualifiedName() {
        if (qualifiedName != null && qualifiedName.contains("$")) {
            throw new IllegalStateException();
        }

        if (qualifiedName != null) {
            return;
        }

        final var enclosing = getEnclosingElement();

        if (enclosing != null) {
            final var enclosingName = enclosing instanceof QualifiedNameable qn
                    ? qn.getQualifiedName()
                    : enclosing.getSimpleName();
            qualifiedName = enclosingName + "." + getSimpleName();
        } else {
            qualifiedName = getSimpleName();
        }
    }

    @Override
    public void setEnclosingElement(final Symbol enclosingElement) {
        this.qualifiedName = null;
        super.setEnclosingElement(enclosingElement);
    }

    @Override
    protected void onEnclosingChanged() {
        this.qualifiedName = null;
        this.binaryName = null;
        this.flatName = null;
    }

    @Override
    public String getQualifiedName() {
        resolveQualifiedName();
        return qualifiedName;
    }

    @Override
    public ExecutableElement findFunctionalMethod() {
        return getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(element -> (ExecutableElement) element)
                .findFirst()
                .orElse(null);
    }

    @Override
    public TypeMirror getSuperclass() {
        return superclass;
    }

    public void setSuperClass(final TypeMirror superClass) {
        this.superclass = superClass;
    }

    @Override
    public List<TypeMirror> getInterfaces() {
        complete();
        return interfaces;
    }

    public void setInterfaces(final List<TypeMirror> interfaces) {
        this.interfaces.clear();
        this.interfaces.addAll(interfaces);
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitType(this, p);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return v.visitClass(this, p);
    }

    public TypeMirror erasure(final Types types) {
        if (erasureType == null) {
            final var type = asType();
            final var enclosingType = type.getEnclosingType() != null
                    ? types.erasure(type.getEnclosingType())
                    : null;

            erasureType = new CClassType(
                    enclosingType,
                    this,
                    List.of()
            );
        }

        return erasureType;
    }

    public TypeMirror getErasureField() {
        return erasureField;
    }

    @Override
    public <A extends Annotation> A getAnnotation(final Class<A> annotationType) {
        final var mirrorOptional = findAnnotationMirror(annotationType.getName());
        return mirrorOptional
                .map(mirror -> annotationType.cast(AnnotationUtils.proxy(mirror, annotationType.getClassLoader())))
                .orElse(null);
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(final Class<A> annotationType) {
        final var classloader = getClass().getClassLoader();

        return (A[]) findAnnotationMirrors(annotationType.getName()).stream()
                .map(annotationMirror -> annotationType.cast(AnnotationUtils.proxy(
                        annotationMirror,
                        classloader
                )))
                .toArray(Annotation[]::new);
    }

    private Predicate<AnnotationMirror> annotationMirrorFilter(final String className) {
        return annotationMirror -> {
            final var annotationType = annotationMirror.getAnnotationType();
            final var element = (TypeElement) annotationType.asElement();
            return className.equals(element.getQualifiedName());
        };
    }

    private Optional<? extends AnnotationMirror> findAnnotationMirror(final String className) {
        return getAnnotationMirrors().stream()
                .filter(annotationMirrorFilter(className)).findFirst();
    }

    private List<? extends AnnotationMirror> findAnnotationMirrors(final String className) {
        return getAnnotationMirrors().stream()
                .filter(annotationMirrorFilter(className))
                .toList();
    }

    @Override
    public List<? extends TypeMirror> getPermittedSubclasses() {
        return this.permitted.stream()
                .map(Symbol::asType)
                .toList();
    }

    public void setPermitted(final List<Symbol> permitted) {
        this.permitted.clear();
        this.permitted.addAll(permitted);
    }


    public ModuleSymbol resolveModuleSymbol() {
        return resolveModuleSymbol(this);
    }

    private ModuleSymbol resolveModuleSymbol(final Symbol symbol) {
        if (symbol == null) {
            return null;
        } else if (symbol instanceof PackageSymbol packageSymbol) {
            return packageSymbol.getModuleSymbol();
        } else {
            return resolveModuleSymbol(symbol.getEnclosingElement());
        }
    }
}

class ValueCollector implements AnnotationValueVisitor<Object, ExecutableElement> {

    @Override
    public Object visitBoolean(final boolean b, final ExecutableElement executableElement) {
        return b;
    }

    @Override
    public Object visitByte(final byte b, final ExecutableElement executableElement) {
        return b;
    }

    @Override
    public Object visitChar(final char c, final ExecutableElement executableElement) {
        return c;
    }

    @Override
    public Object visitDouble(final double d, final ExecutableElement executableElement) {
        return d;
    }

    @Override
    public Object visitFloat(final float f, final ExecutableElement executableElement) {
        return f;
    }

    @Override
    public Object visitInt(final int i, final ExecutableElement executableElement) {
        return i;
    }

    @Override
    public Object visitLong(final long l, final ExecutableElement executableElement) {
        return l;
    }

    @Override
    public Object visitShort(final short s, final ExecutableElement executableElement) {
        return s;
    }

    @Override
    public Object visitString(final String s, final ExecutableElement executableElement) {
        return s;
    }

    @Override
    public Object visitEnumConstant(final VariableElement c, final ExecutableElement executableElement) {
        final var type = (DeclaredType) c.asType();
        final var className = type.asTypeElement().getQualifiedName();
        final var constantName = c.getSimpleName();
        return Enum.valueOf(loadAnnotationClass(className), constantName);
    }

    public <T extends Enum<T>> Class<? extends T> loadAnnotationClass(final String className) {
        return AnnotationUtils.loadClass(className, getClass().getClassLoader());
    }

    @Override
    public Object visitArray(final List<? extends AnnotationValue> values, final ExecutableElement executableElement) {
        final var returnType = (ArrayType) executableElement.getReturnType();
        final var componentType = returnType.getComponentType();

        final var className = componentType.asTypeElement().getQualifiedName();
        final Class<?> elementType = AnnotationUtils.loadClass(className, getClass().getClassLoader());

        return values.stream()
                .map(it -> it.accept(this, executableElement))
                .toArray(createArray(elementType));
    }

    private <R> IntFunction<R> createArray(final Class<?> elementType) {
        return value -> (R) Array.newInstance(elementType, value);
    }

    @Override
    public Object visitUnknown(final AnnotationValue av, final ExecutableElement o) {
        System.err.println("ClassSymbol.ValueCollector#visitUnknown " + av);
        return null;
    }

}

