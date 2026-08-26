package io.github.potjerodekool.nabu.compiler.backend.java;

import io.github.potjerodekool.nabu.compiler.backend.asm.AsmHelper;
import io.github.potjerodekool.nabu.compiler.backend.asm.SlotAllocator;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import io.github.potjerodekool.nabu.compiler.lang.model.element.AnnotationValue;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ArrayAttribute;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ClassAttribute;
import io.github.potjerodekool.nabu.compiler.lang.model.element.CompoundAttribute;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ConstantAttribute;
import io.github.potjerodekool.nabu.compiler.lang.model.element.EnumAttribute;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.FieldBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hulpmethoden voor het schrijven van attributen met de ClassFile API.
 */
final class AttributeFactories {

    private static final ConstantPoolBuilder CP = ConstantPoolBuilder.of();

    private AttributeFactories() {
    }

    private static ClassEntry classEntry(final String internalName) {
        return CP.classEntry(ClassDesc.ofInternalName(internalName));
    }

    static void sourceFile(final ClassBuilder classBuilder,
                           final String fileName) {
        classBuilder.with(SourceFileAttribute.of(fileName));
    }

    static void permittedSubclasses(final ClassBuilder classBuilder,
                                    final List<String> permittedInternalNames) {
        final var entries = permittedInternalNames.stream()
                .map(AttributeFactories::classEntry)
                .toList();
        classBuilder.with(PermittedSubclassesAttribute.of(entries));
    }

    static void annotations(final ClassBuilder builder,
                            final List<CompoundAttribute> annotations) {
        final var runtimeAnnotations = runtimeAnnotations(annotations);
        if (!runtimeAnnotations.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(runtimeAnnotations));
        }
    }

    static void annotations(final FieldBuilder builder,
                            final List<CompoundAttribute> annotations) {
        final var runtimeAnnotations = runtimeAnnotations(annotations);
        if (!runtimeAnnotations.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(runtimeAnnotations));
        }
    }

    static void annotations(final MethodBuilder builder,
                            final List<CompoundAttribute> annotations) {
        final var runtimeAnnotations = runtimeAnnotations(annotations);
        if (!runtimeAnnotations.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(runtimeAnnotations));
        }
    }

    private static List<Annotation> runtimeAnnotations(final List<CompoundAttribute> annotations) {
        final var runtimeAnnotations = new ArrayList<Annotation>();

        if (annotations != null) {
            for (final var annotation : annotations) {
                final var cfAnnotation = toCfAnnotation(annotation);
                if (cfAnnotation != null) {
                    runtimeAnnotations.add(cfAnnotation);
                }
            }
        }

        return runtimeAnnotations;
    }

    static void parameterAnnotations(final MethodBuilder builder,
                                     final List<IRValue> declaredParams,
                                     final boolean isStatic,
                                     final List<List<CompoundAttribute>> parameterAnnotations) {
        if (parameterAnnotations == null || parameterAnnotations.isEmpty()
                || parameterAnnotations.stream().allMatch(List::isEmpty)) {
            return;
        }

        final int offset = isStatic ? 0 : 1;
        final var perParam = new ArrayList<List<Annotation>>();

        for (int i = 0; i < declaredParams.size(); i++) {
            final var paramIndex = i + offset;
            final var cfAnnotations = new ArrayList<Annotation>();
            if (paramIndex < parameterAnnotations.size()) {
                for (final var ann : parameterAnnotations.get(paramIndex)) {
                    final var cfAnn = toCfAnnotation(ann);
                    if (cfAnn != null) {
                        cfAnnotations.add(cfAnn);
                    }
                }
            }
            perParam.add(cfAnnotations);
        }

        if (!perParam.isEmpty()) {
            builder.with(RuntimeVisibleParameterAnnotationsAttribute.of(perParam));
        }
    }

    static void methodParameters(final MethodBuilder builder,
                                 final List<IRValue> declaredParams) {
        // Parameter-namen zijn optionele metadata; alleen emit als er namen zijn.
        if (declaredParams == null || declaredParams.isEmpty()) {
            return;
        }
        final var entries = new ArrayList<MethodParameterInfo>();
        for (final var param : declaredParams) {
            if (param instanceof IRValue.Temp temp) {
                entries.add(MethodParameterInfo.ofParameter(
                        Optional.of(SlotAllocator.normalize(temp.name())),
                        0
                ));
            }
        }
        if (!entries.isEmpty()) {
            builder.with(MethodParametersAttribute.of(entries));
        }
    }

    static void signature(final ClassBuilder builder,
                          final String genericSignature) {
        if (genericSignature != null && !genericSignature.isBlank()) {
            builder.with(SignatureAttribute.of(CP.utf8Entry(genericSignature)));
        }
    }

    static void signature(final FieldBuilder builder,
                          final String genericSignature) {
        if (genericSignature != null && !genericSignature.isBlank()) {
            builder.with(SignatureAttribute.of(CP.utf8Entry(genericSignature)));
        }
    }

    static void signature(final MethodBuilder builder,
                          final String genericSignature) {
        if (genericSignature != null && !genericSignature.isBlank()) {
            builder.with(SignatureAttribute.of(CP.utf8Entry(genericSignature)));
        }
    }

    static void recordComponent(final ClassBuilder classBuilder,
                                final String name,
                                final String fieldDescriptor,
                                final String genericSignature,
                                final List<CompoundAttribute> annotations) {
        final var componentAnnotations = new ArrayList<Annotation>();
        if (annotations != null) {
            for (final var ann : annotations) {
                final var cf = toCfAnnotation(ann);
                if (cf != null) {
                    componentAnnotations.add(cf);
                }
            }
        }

        final var attrs = new ArrayList<java.lang.classfile.Attribute<?>>();
        if (!componentAnnotations.isEmpty()) {
            attrs.add(RuntimeVisibleAnnotationsAttribute.of(componentAnnotations));
        }
        if (genericSignature != null && !genericSignature.isBlank()) {
            attrs.add(SignatureAttribute.of(CP.utf8Entry(genericSignature)));
        }

        final var componentInfo = RecordComponentInfo.of(
                CP.utf8Entry(name),
                CP.utf8Entry(fieldDescriptor),
                attrs
        );

        classBuilder.with(RecordAttribute.of(List.of(componentInfo)));
    }

    private static Annotation toCfAnnotation(final CompoundAttribute annotation) {
        final var annotationType = annotation.getAnnotationType();
        if (annotationType == null) {
            return null;
        }

        final var typeDescriptor = AsmHelper.createDescriptor(annotationType);
        final var classDesc = ClassDesc.ofDescriptor(typeDescriptor);

        final var elements = new ArrayList<AnnotationElement>();
        for (final var entry : annotation.getElementValues().entrySet()) {
            final var methodName = entry.getKey().getSimpleName();
            final var value = toCfValue(entry.getValue());
            if (value != null) {
                elements.add(AnnotationElement.of(methodName, value));
            }
        }

        return Annotation.of(classDesc, elements);
    }

    private static java.lang.classfile.AnnotationValue toCfValue(final AnnotationValue value) {
        if (value instanceof ConstantAttribute constant) {
            final var raw = constant.getValue();
            if (raw instanceof Boolean b) return java.lang.classfile.AnnotationValue.ofBoolean(b);
            if (raw instanceof Byte b) return java.lang.classfile.AnnotationValue.ofByte(b);
            if (raw instanceof Character c) return java.lang.classfile.AnnotationValue.ofChar(c);
            if (raw instanceof Double d) return java.lang.classfile.AnnotationValue.ofDouble(d);
            if (raw instanceof Float f) return java.lang.classfile.AnnotationValue.ofFloat(f);
            if (raw instanceof Integer i) return java.lang.classfile.AnnotationValue.ofInt(i);
            if (raw instanceof Long l) return java.lang.classfile.AnnotationValue.ofLong(l);
            if (raw instanceof Short s) return java.lang.classfile.AnnotationValue.ofShort(s);
            if (raw instanceof String s) return java.lang.classfile.AnnotationValue.ofString(s);
            return null;
        } else if (value instanceof EnumAttribute enumAttr) {
            final var enumType = enumAttr.getType();
            final var enumDesc = AsmHelper.createDescriptor(enumType);
            final var constantName = enumAttr.getValue().getSimpleName();
            return java.lang.classfile.AnnotationValue.ofEnum(
                    ClassDesc.ofDescriptor(enumDesc),
                    constantName
            );
        } else if (value instanceof CompoundAttribute nested) {
            final var nestedAnnotation = toCfAnnotation(nested);
            if (nestedAnnotation == null) {
                return null;
            }
            return java.lang.classfile.AnnotationValue.ofAnnotation(nestedAnnotation);
        } else if (value instanceof ArrayAttribute arrayAttr) {
            final var elementValues = new ArrayList<java.lang.classfile.AnnotationValue>();
            for (final var elem : arrayAttr.getValue()) {
                final var cf = toCfValue(elem);
                if (cf != null) {
                    elementValues.add(cf);
                }
            }
            return java.lang.classfile.AnnotationValue.ofArray(elementValues);
        } else if (value instanceof ClassAttribute classAttr) {
            final var typeMirror = classAttr.getValue();
            if (typeMirror instanceof TypeMirror tm) {
                return java.lang.classfile.AnnotationValue.ofClass(
                        ClassDesc.ofDescriptor(AsmHelper.createDescriptor(tm))
                );
            }
            return null;
        }
        return null;
    }
}
