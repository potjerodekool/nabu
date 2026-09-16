package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationValue;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationValueVisitor;
import io.github.potjerodekool.nabu.lang.model.element.CompoundAttribute;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ElementVisitor;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.Modifier;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilder;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVisitor;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gedeeld test-model voor de reflectie-tests (E2-emissie, E3-runtime-e2e):
 * een GreetCommand-module met velden `count` (int) en `name` (String) waarop
 * een @Option-annotatie staat, plus de minimale model-stubs die de backend
 * nodig heeft (de volledige model-impls leven in het compiler-moduul).
 */
final class TestReflectionModel {

    static final String GREET_INTERNAL = "greet/GreetCommand";
    static final String OPTION_INTERNAL = "picocli/CommandLine$Option";

    private TestReflectionModel() {
    }

    /** GreetCommand-module: count (int, offset-laag), name (String), @Option op name. */
    static IRModule greetCommandModule() {
        final IRModule module = new IRModule(GREET_INTERNAL);
        module.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));

        final IRField count = IRField.field(0, "count", new IRType.Int(32), IRValue.ofI32(0));
        final IRField name = IRField.field(Flags.PRIVATE, "name",
                new IRType.Ptr(IRType.I8, "Ljava/lang/String;"), IRValue.ofString(""));
        name.setAnnotations(List.of(optionAnnotation()));

        module.emitField(count);
        module.emitField(name);
        return module;
    }

    /** CompoundAttribute-stub voor @Option(names = {"-n", "--name"}, count = 3). */
    static CompoundAttributeStub optionAnnotation() {
        final List<AnnotationValue> namesValues = List.of(
                AnnotationBuilder.createConstantValue("-n"),
                AnnotationBuilder.createConstantValue("--name"));
        return new CompoundAttributeStub(Map.of(
                new AnnMethod("names"),
                AnnotationBuilder.createArrayValue(null, namesValues),
                new AnnMethod("count"),
                AnnotationBuilder.createConstantValue(3)));
    }

    static final class CompoundAttributeStub implements CompoundAttribute {

        private final Map<? extends ExecutableElement, ? extends AnnotationValue> values;

        CompoundAttributeStub(
                final Map<? extends ExecutableElement, ? extends AnnotationValue> values) {
            this.values = values;
        }

        @Override
        public DeclaredTypeStub getAnnotationType() {
            return new DeclaredTypeStub(new AnnType("picocli.CommandLine$Option"));
        }

        @Override
        public DeclaredTypeStub getType() {
            return getAnnotationType();
        }

        @Override
        public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
            return values;
        }

        @Override
        public boolean isSynthesized() {
            return false;
        }

        @Override
        public Object getValue() {
            return this;
        }

        @Override
        public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
            return v.visitAnnotation(this, p);
        }
    }

    /** DeclaredType die naar een {@link AnnType} wijst. */
    static final class DeclaredTypeStub implements io.github.potjerodekool.nabu.type.DeclaredType {

        private final AnnType element;

        DeclaredTypeStub(final AnnType element) {
            this.element = element;
        }

        @Override
        public AnnType asElement() {
            return element;
        }

        @Override
        public TypeMirror getEnclosingType() {
            return null;
        }

        @Override
        public List<? extends TypeMirror> getTypeArguments() {
            return List.of();
        }

        @Override
        public TypeKind getKind() {
            return TypeKind.DECLARED;
        }

        @Override
        public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
            return null;
        }

        @Override
        public String getClassName() {
            return element.getQualifiedName();
        }

        @Override
        public boolean isError() {
            return false;
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof DeclaredTypeStub d && element == d.element;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(element);
        }
    }

    /** TypeElement: levert de qualified name van het annotatietype. */
    static final class AnnType implements TypeElement {

        private final String qualifiedName;

        AnnType(final String qualifiedName) {
            this.qualifiedName = qualifiedName;
        }

        @Override
        public String getQualifiedName() {
            return qualifiedName;
        }

        @Override
        public String getSimpleName() {
            return qualifiedName;
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.ANNOTATION_TYPE;
        }

        @Override
        public Element getEnclosingElement() {
            return null;
        }

        @Override
        public List<? extends Element> getEnclosedElements() {
            return List.of();
        }

        @Override
        public TypeMirror asType() {
            return null;
        }

        @Override
        public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
            return null;
        }

        @Override
        public Set<Modifier> getModifiers() {
            return Set.of(Modifier.ABSTRACT);
        }

        @Override
        public long getFlags() {
            return 0;
        }

        @Override
        public boolean hasFlag(final long flag) {
            return false;
        }

        @Override
        public boolean isPublic() {
            return true;
        }

        @Override
        public boolean isPrivate() {
            return false;
        }

        @Override
        public boolean isProtected() {
            return false;
        }

        @Override
        public boolean isDefaultAccess() {
            return false;
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public boolean isFinal() {
            return false;
        }

        @Override
        public boolean isSynthetic() {
            return false;
        }

        @Override
        public boolean isAbstract() {
            return true;
        }

        @Override
        public boolean isNative() {
            return false;
        }

        @Override
        public AnnotationMirror attribute(final TypeElement typeElement) {
            return null;
        }

        @Override
        public ElementBuilder<?> builder() {
            return null;
        }

        @Override
        public PackageElement getPackageElement() {
            return null;
        }

        @Override
        public ModuleElement getModuleElement() {
            return null;
        }

        @Override
        public List<? extends AnnotationMirror> getAnnotationMirrors() {
            return List.of();
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
        public NestingKind getNestingKind() {
            return NestingKind.TOP_LEVEL;
        }

        @Override
        public List<? extends TypeParameterElement> getTypeParameters() {
            return List.of();
        }

        @Override
        public boolean isFunctionalInterface() {
            return false;
        }

        @Override
        public ExecutableElement findFunctionalMethod() {
            return null;
        }

        @Override
        public TypeMirror getSuperclass() {
            return null;
        }

        @Override
        public List<? extends TypeMirror> getInterfaces() {
            return List.of();
        }

        @Override
        public TypeMirror getErasureField() {
            return null;
        }

        @Override
        public String getFlatName() {
            return qualifiedName;
        }

        @Override
        public void addEnclosedElement(final Element element) {
        }

        @Override
        public void setSuperClass(final TypeMirror type) {
        }

        @Override
        public void complete() {
        }
    }

    /** ExecutableElement voor een annotatie-attribuut (alleen de naam telt). */
    static final class AnnMethod implements ExecutableElement {

        private final String simpleName;

        AnnMethod(final String simpleName) {
            this.simpleName = simpleName;
        }

        @Override
        public String getSimpleName() {
            return simpleName;
        }

        public String getQualifiedName() {
            return simpleName;
        }

        @Override
        public List<? extends TypeParameterElement> getTypeParameters() {
            return List.of();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.METHOD;
        }

        @Override
        public Element getEnclosingElement() {
            return null;
        }

        @Override
        public List<? extends Element> getEnclosedElements() {
            return List.of();
        }

        @Override
        public TypeMirror asType() {
            return null;
        }

        @Override
        public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
            return null;
        }

        @Override
        public Set<Modifier> getModifiers() {
            return Set.of(Modifier.PUBLIC, Modifier.ABSTRACT);
        }

        @Override
        public long getFlags() {
            return 0;
        }

        @Override
        public boolean hasFlag(final long flag) {
            return false;
        }

        @Override
        public boolean isPublic() {
            return true;
        }

        @Override
        public boolean isPrivate() {
            return false;
        }

        @Override
        public boolean isProtected() {
            return false;
        }

        @Override
        public boolean isDefaultAccess() {
            return false;
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public boolean isFinal() {
            return false;
        }

        @Override
        public boolean isSynthetic() {
            return false;
        }

        @Override
        public boolean isAbstract() {
            return true;
        }

        @Override
        public boolean isNative() {
            return false;
        }

        @Override
        public AnnotationMirror attribute(final TypeElement typeElement) {
            return null;
        }

        @Override
        public ElementBuilder<?> builder() {
            return null;
        }

        @Override
        public PackageElement getPackageElement() {
            return null;
        }

        @Override
        public List<? extends AnnotationMirror> getAnnotationMirrors() {
            return List.of();
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
        public TypeMirror getReturnType() {
            return null;
        }

        @Override
        public List<? extends VariableElement> getParameters() {
            return List.of();
        }

        @Override
        public TypeMirror getReceiverType() {
            return null;
        }

        @Override
        public boolean isVarArgs() {
            return false;
        }

        @Override
        public boolean isDefault() {
            return false;
        }

        @Override
        public List<? extends TypeMirror> getThrownTypes() {
            return List.of();
        }

        @Override
        public AnnotationValue getDefaultValue() {
            return null;
        }
    }
}