package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

import java.lang.annotation.Annotation;
import java.util.*;
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

    private static TypeMirror resolveOuterType(final Symbol symbol) {
        if (symbol instanceof ClassSymbol classSymbol) {
            return classSymbol.asType();
        } else {
            return AbstractType.noType;
        }
    }

    public ClassSymbol(final ElementKind kind,
                       final NestingKind nestingKind,
                       final long flags,
                       final String name,
                       final AbstractType type,
                       final Symbol owner,
                       final List<? extends Symbol> enclosedElements,
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
    public ClassSymbolBuilder builder() {
        return new ClassSymbolBuilder();
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

    public void addEnclosedElement(final Element enclosedElement) {
        initMembersIfNeeded();
        members.define(enclosedElement);
        ((Symbol)enclosedElement).setEnclosingElement(this);
    }

    public void addEnclosedElement(final int index,
                                   final Symbol enclosedElement) {
        initMembersIfNeeded();
        members.define(index, enclosedElement);
        enclosedElement.setEnclosingElement(this);
    }

    public void removeEnclosedElement(final Symbol enclosedElement) {
        initMembersIfNeeded();
        members.remove(enclosedElement);
        enclosedElement.setEnclosingElement(null);
    }

    private void initMembersIfNeeded() {
        if (members == null) {
            members = new WritableScope();
        }
    }

    public void setEnclosedElements(final List<? extends Symbol> elements) {
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
        complete();
        return nestingKind;
    }

    public void setNestingKind(final NestingKind nestingKind) {
        this.nestingKind = nestingKind;
        this.qualifiedName = null;
    }

    private void resolveQualifiedName() {
        if (qualifiedName != null) {
            return;
        }

        final var enclosing = getEnclosingElement();

        if (enclosing != null && !isUnnamed(enclosing)) {
            final var enclosingName = enclosing instanceof QualifiedNameable qn
                    ? qn.getQualifiedName()
                    : enclosing.getSimpleName();

            // Ook een anonieme klasse krijgt de binaire vorm (Outer$1), zoals
            // javac: die naam belandt in descriptors en module-namen.
            final var separator = nestingKind == NestingKind.MEMBER
                    || nestingKind == NestingKind.ANONYMOUS ? "$" : ".";

            qualifiedName = enclosingName + separator + getSimpleName();
        } else {
            qualifiedName = getSimpleName();
        }
    }

    private boolean isUnnamed(final Symbol symbol) {
        return symbol instanceof PackageSymbol packageSymbol
                && packageSymbol.isUnnamed();
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

    /**
     * JLS 9.8: een interface met precies één abstracte methode (methoden van
     * Object niet meegeteld), met of zonder {@code @FunctionalInterface}. De
     * klasse wordt eerst gecompleteerd: de annotatie was anders pas zichtbaar
     * nadat iets anders de klasse had geladen, en een lambda in
     * {@code IntSupplier s = () -> 1} kreeg dan geen doeltype.
     */
    @Override
    public boolean isFunctionalInterface() {
        complete();
        if (getKind() != ElementKind.INTERFACE) {
            return false;
        }
        return findSingleAbstractMethod() != null;
    }

    @Override
    public ExecutableElement findFunctionalMethod() {
        if (!isFunctionalInterface()) {
            return null;
        }
        return findSingleAbstractMethod();
    }

    /**
     * De enige abstracte methode van deze interface en haar superinterfaces,
     * of null. Een methode wordt herkend op naam en aantal parameters, zodat
     * een generieke herdeclaratie in een subinterface (UnaryOperator) met die
     * van de superinterface (Function.apply) samenvalt; een default in een
     * subinterface dekt een abstracte methode van de super af. Ook de eerste
     * declaratie telt, niet de eerste methode: Comparator heeft tientallen
     * default- en statische methoden en een abstracte {@code equals(Object)}.
     */
    private ExecutableElement findSingleAbstractMethod() {
        final Set<String> seen = new HashSet<>();
        final Map<String, ExecutableElement> abstractMethods = new LinkedHashMap<>();
        final Deque<TypeElement> pending = new ArrayDeque<>();
        final Set<TypeElement> visited = new HashSet<>();
        pending.add(this);
        while (!pending.isEmpty()) {
            final TypeElement current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof ClassSymbol classSymbol) {
                classSymbol.complete();
            }
            for (final Element element : current.getEnclosedElements()) {
                if (element.getKind() != ElementKind.METHOD
                        || !(element instanceof ExecutableElement method)
                        || method.isStatic()
                        || method.isPrivate()) {
                    continue;
                }
                final String key = method.getSimpleName() + "/" + method.getParameters().size();
                if (!seen.add(key) || method.isDefault() || isPublicObjectMethod(method)) {
                    continue;
                }
                abstractMethods.put(key, method);
            }
            for (final TypeMirror superInterface : current.getInterfaces()) {
                final TypeElement element = superInterface.asTypeElement();
                if (element != null) {
                    pending.add(element);
                }
            }
        }
        return abstractMethods.size() == 1 ? abstractMethods.values().iterator().next() : null;
    }

    /** equals(Object), hashCode() en toString() tellen niet mee (JLS 9.8). */
    private static boolean isPublicObjectMethod(final ExecutableElement method) {
        final String name = method.getSimpleName();
        final int arity = method.getParameters().size();
        return ("equals".equals(name) && arity == 1)
                || (("hashCode".equals(name) || "toString".equals(name)) && arity == 0);
    }

    @Override
    public TypeMirror getSuperclass() {
        complete();
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
            final var enclosingType =
                    type.getEnclosingType() == null || type.getEnclosingType().getKind() == TypeKind.NONE ? type.getEnclosingType()
                    : types.erasure(type.getEnclosingType());

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
        final var mirrorOptional = findAnnotationMirror(annotationType);
        return mirrorOptional
                .map(mirror -> annotationType.cast(AnnotationUtils.proxy(mirror, annotationType.getClassLoader())))
                .orElse(null);
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(final Class<A> annotationType) {
        final var classloader = getClass().getClassLoader();

        return (A[]) findAnnotationMirrors(annotationType).stream()
                .map(annotationMirror -> annotationType.cast(AnnotationUtils.proxy(
                        annotationMirror,
                        classloader
                )))
                .toArray(Annotation[]::new);
    }

    /**
     * Een genest type heet uit een class-bestand {@code picocli.CommandLine$Command}
     * en uit broncode {@code picocli.CommandLine.Command}; beide vormen tellen.
     */
    private Predicate<AnnotationMirror> annotationMirrorFilter(final Class<?> type) {
        return annotationMirror -> {
            final var annotationType = annotationMirror.getAnnotationType();
            final var qualifiedName = ((TypeElement) annotationType.asElement()).getQualifiedName();
            return type.getName().equals(qualifiedName)
                    || (type.getCanonicalName() != null && type.getCanonicalName().equals(qualifiedName));
        };
    }

    private Optional<? extends AnnotationMirror> findAnnotationMirror(final Class<?> type) {
        return getAnnotationMirrors().stream()
                .filter(annotationMirrorFilter(type)).findFirst();
    }

    private List<? extends AnnotationMirror> findAnnotationMirrors(final Class<?> type) {
        return getAnnotationMirrors().stream()
                .filter(annotationMirrorFilter(type))
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

    @Override
    public ModuleElement getModuleElement() {
        return resolveModuleSymbol(this);
    }

    private ModuleElement resolveModuleSymbol(final Symbol symbol) {
        if (symbol == null) {
            return null;
        } else if (symbol instanceof PackageSymbol packageSymbol) {
            return packageSymbol.getModuleSymbol();
        } else {
            return resolveModuleSymbol(symbol.getEnclosingElement());
        }
    }

    @Override
    public void setClassFile(final FileObject classFile) {
        super.setClassFile(classFile);
        if (classFile == null) {
            members = null;
        }
    }
}

