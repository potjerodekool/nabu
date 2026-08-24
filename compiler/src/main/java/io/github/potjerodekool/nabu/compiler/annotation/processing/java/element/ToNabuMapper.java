package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.compiler.lang.model.element.*;
import io.github.potjerodekool.nabu.type.TypeMirror;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ToNabuMapper {

    private static final Mapper MAPPER = new Mapper();

    private ToNabuMapper() {
    }

    public static io.github.potjerodekool.nabu.compiler.lang.model.element.AnnotationMirror accept(final AnnotationMirror mirror) {
        return MAPPER.accept(mirror);
    }

    public static TypeMirror accept(final javax.lang.model.type.TypeMirror typeMirror) {
        return typeMirror.accept(MAPPER, null);
    }

    private static class Mapper implements TypeVisitor<TypeMirror, Void>, AnnotationValueVisitor<AnnotationValue, Void> {

        public io.github.potjerodekool.nabu.compiler.lang.model.element.AnnotationMirror accept(final AnnotationMirror mirror) {
            final var annotationType = (io.github.potjerodekool.nabu.type.DeclaredType) mirror.getAnnotationType().accept(this, null);
            final Map<ExecutableElement, AnnotationValue> values = mirror.getElementValues().entrySet().stream()
                    .map(it -> {
                        final var key = (ExecutableElement) ElementWrapperFactory.toNabuElement(it.getKey());
                        final var value = (AnnotationValue) it.getValue().accept(this, null);
                        return Map.entry(key, value);
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            return new CCompoundAttribute(annotationType, values);
        }

        @Override
        public TypeMirror visit(final javax.lang.model.type.TypeMirror t, final Void unused) {
            return t.accept(this, null);
        }

        @Override
        public TypeMirror visitPrimitive(final PrimitiveType t, final Void unused) {
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitNull(final NullType t, final Void unused) {
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitArray(final ArrayType t, final Void unused) {
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitDeclared(final DeclaredType t, final Void unused) {
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitError(final ErrorType t, final Void unused) {
            throw new UnsupportedOperationException("Error type not supported");
        }

        @Override
        public TypeMirror visitTypeVariable(final TypeVariable t, final Void unused) {
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitWildcard(final WildcardType t, final Void unused) {
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitExecutable(final ExecutableType t, final Void unused) {
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitNoType(final NoType t, final Void unused) {
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitUnknown(final javax.lang.model.type.TypeMirror t, final Void unused) {
            throw new UnsupportedOperationException("Unknown type not supported");
        }

        @Override
        public TypeMirror visitUnion(final UnionType t, final Void unused) {
            // Map union types to appropriate Nabu representation 
            // This is needed for Java 8+ annotation processing interop
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public TypeMirror visitIntersection(final IntersectionType t, final Void unused) {
            // Map intersection types to appropriate Nabu representation
            // This is needed for Java 8+ annotation processing interop
            return TypeWrapperFactory.unwrap(t);
        }

        @Override
        public AnnotationValue visit(final javax.lang.model.element.AnnotationValue av, final Void unused) {
            return av.accept(this, null);
        }

        @Override
        public AnnotationValue visitBoolean(final boolean b, final Void unused) {
            return new CConstantAttribute(b);
        }

        @Override
        public AnnotationValue visitByte(final byte b, final Void unused) {
            return new CConstantAttribute(b);
        }

        @Override
        public AnnotationValue visitChar(final char c, final Void unused) {
            return new CConstantAttribute(c);
        }

        @Override
        public AnnotationValue visitDouble(final double d, final Void unused) {
            return new CConstantAttribute(d);
        }

        @Override
        public AnnotationValue visitFloat(final float f, final Void unused) {
            return new CConstantAttribute(f);
        }

        @Override
        public AnnotationValue visitInt(final int i, final Void unused) {
            return new CConstantAttribute(i);
        }

        @Override
        public AnnotationValue visitLong(final long i, final Void unused) {
            return new CConstantAttribute(i);
        }

        @Override
        public AnnotationValue visitShort(final short s, final Void unused) {
            return new CConstantAttribute(s);
        }

        @Override
        public AnnotationValue visitString(final String s, final Void unused) {
            return new CConstantAttribute(s);
        }

        @Override
        public AnnotationValue visitType(final javax.lang.model.type.TypeMirror t, final Void unused) {
            return new CClassAttribute(ToNabuMapper.accept(t));
        }

        @Override
        public AnnotationValue visitEnumConstant(final VariableElement c, final Void unused) {
            return new CConstantAttribute(c.getSimpleName().toString());
        }

        @Override
        public AnnotationValue visitAnnotation(final AnnotationMirror a, final Void unused) {
            return (AnnotationValue) accept(a);
        }

        @Override
        public AnnotationValue visitArray(final List<? extends javax.lang.model.element.AnnotationValue> vals, final Void unused) {
            final var nabuValues = new ArrayList<AnnotationValue>();
            for (final var val : vals) {
                nabuValues.add(val.accept(this, null));
            }
            return new CArrayAttribute(null, nabuValues);
        }

        @Override
        public AnnotationValue visitUnknown(final javax.lang.model.element.AnnotationValue av, final Void unused) {
            throw new UnsupportedOperationException("Unknown annotation value type");
        }
    }
}
