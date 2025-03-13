package io.github.potjerodekool.nabu.compiler.backend.generate.asm.annotation;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.ClassUtils;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;

public class AsmAnnotationGenerator implements AnnotationValueVisitor<Void, AsmAnnotationGenerator.AnnotationGeneratorContext> {

    private static final AsmAnnotationGenerator INSTANCE = new AsmAnnotationGenerator();

    private AsmAnnotationGenerator() {
    }

    public static void generate(final AnnotationMirror annotation,
                                final ClassVisitor classWriter) {
        final var descriptor = ClassUtils.getDescriptor(annotation.getAnnotationType());

        final var annotationVisitor = classWriter.visitAnnotation(
                descriptor,
                isVisible(annotation)
        );
        generate(annotation, annotationVisitor);
    }

    private static boolean isVisible(final AnnotationMirror annotation) {
        final var annotationType = annotation.getAnnotationType();
        final var element = annotationType.getTypeElement();
        return element.getAnnotationMirrors().stream()
                .filter(AsmAnnotationGenerator::isRetentionAnnotation)
                .flatMap(it -> it.getElementValues().entrySet().stream())
                .filter(it ->
                        "value".equals(it.getKey().getSimpleName()))
                .map(it -> (EnumAttribute) it.getValue())
                .map(EnumAttribute::getValue)
                .anyMatch(it -> "RUNTIME".equals(it.getSimpleName()));
    }

    private static boolean isRetentionAnnotation(final AnnotationMirror annotation) {
        final var annotationType = annotation.getAnnotationType();
        return "java.lang.annotation.Retention".equals(
                annotationType.getTypeElement().getQualifiedName()
        );
    }

    public static void generate(final AnnotationMirror annotation,
                                final MethodVisitor methodVisitor) {
        final var descriptor = ClassUtils.getDescriptor(annotation.getAnnotationType());

        final var annotationVisitor = methodVisitor.visitAnnotation(
                descriptor,
                isVisible(annotation)
        );
        generate(annotation, annotationVisitor);
    }

    public static void generate(final AnnotationMirror annotation,
                                final AnnotationVisitor annotationVisitor) {
        annotation.getElementValues()
                .forEach((name, value) ->
                        value.accept(INSTANCE, new AnnotationGeneratorContext(name.getSimpleName(), annotationVisitor)));

        annotationVisitor.visitEnd();
    }

    @Override
    public Void visit(final AnnotationValue av, final AnnotationGeneratorContext annotationGeneratorContext) {
        return av.accept(this, annotationGeneratorContext);
    }

    @Override
    public Void visitBoolean(final boolean b, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, b);
        return null;
    }

    @Override
    public Void visitByte(final byte b, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, b);
        return null;
    }

    @Override
    public Void visitChar(final char c, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, c);
        return null;
    }

    @Override
    public Void visitDouble(final double d, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, d);
        return null;
    }

    @Override
    public Void visitFloat(final float f, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, f);
        return null;
    }

    @Override
    public Void visitInt(final int i, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, i);
        return null;
    }

    @Override
    public Void visitLong(final long l, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, l);
        return null;
    }

    @Override
    public Void visitShort(final short s, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, s);
        return null;
    }

    @Override
    public Void visitString(final String s, final AnnotationGeneratorContext annotationGeneratorContext) {
        annotationGeneratorContext.annotationVisitor.visit(annotationGeneratorContext.name, s);
        return null;
    }

    @Override
    public Void visitType(final TypeMirror t, final AnnotationGeneratorContext annotationGeneratorContext) {
        final var declaredType = (DeclaredType) t;

        final var internalName =
                ClassUtils.getInternalName(declaredType.getTypeElement().getQualifiedName());
        annotationGeneratorContext.annotationVisitor.visit(
                annotationGeneratorContext.name,
                Type.getObjectType(internalName)
        );

        return null;
    }

    @Override
    public Void visitEnumConstant(final VariableElement c, final AnnotationGeneratorContext annotationGeneratorContext) {
        final var annotationElement = (TypeElement) c.getEnclosingElement();
        final var descriptor = ClassUtils.getDescriptor(annotationElement.asType());
        annotationGeneratorContext.annotationVisitor.visitEnum(
                annotationGeneratorContext.name,
                descriptor,
                c.getSimpleName()
        );
        return null;
    }

    @Override
    public Void visitAnnotation(final AnnotationMirror a, final AnnotationGeneratorContext annotationGeneratorContext) {
        final var descriptor = ClassUtils.getDescriptor(a.getAnnotationType());
        final var annotationVisitor = annotationGeneratorContext.annotationVisitor
                .visitAnnotation(
                        annotationGeneratorContext.name,
                        descriptor
                );

        a.getElementValues().forEach((name, value) ->
                value.accept(this, new AnnotationGeneratorContext(name.getSimpleName(), annotationVisitor)));

        return null;
    }

    @Override
    public Void visitArray(final List<? extends AnnotationValue> values, final AnnotationGeneratorContext annotationGeneratorContext) {
        final var annotationVisitor = annotationGeneratorContext.annotationVisitor.visitArray(annotationGeneratorContext.name);
        final var newContext = new AnnotationGeneratorContext(null, annotationVisitor);
        values.forEach(value -> value.accept(this, newContext));
        return null;
    }

    @Override
    public Void visitUnknown(final AnnotationValue av,
                             final AnnotationGeneratorContext annotationGeneratorContext) {
        throw new UnsupportedOperationException();
    }

    public record AnnotationGeneratorContext(String name,
                                             AnnotationVisitor annotationVisitor) {

    }
}
