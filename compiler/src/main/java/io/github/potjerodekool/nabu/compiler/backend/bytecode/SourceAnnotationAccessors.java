package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationValue;
import io.github.potjerodekool.nabu.lang.model.element.ClassAttribute;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AnnotationAccessors} for annotation types compiled from source: the
 * runtime makes annotation instances from the reflection metadata and nobody
 * implements the annotation interface, so a call of an element
 * ({@code cmd.value()}) goes straight to the interface's function. Each
 * element (String, primitive, Class, enum, nested annotation, and arrays of
 * those) gets a body that reads the value through the runtime's helpers,
 * with the element's default (not for a nested annotation, whose default is
 * not in the metadata).
 */
final class SourceAnnotationAccessors {

    private static final String HELPERS = "nabu.runtime.Annotations_";
    private static final IRType OBJECT = new IRType.Ptr(IRType.I8, "Ljava/lang/Object;");
    private static final IRType STRING = new IRType.Ptr(IRType.I8, "Ljava/lang/String;");

    private SourceAnnotationAccessors() {
    }

    /**
     * @param isAnnotationType whether a type (internal name) is an annotation
     *                         type: an element of that type is a nested annotation.
     */
    static void implement(final IRModule module, final java.util.function.Predicate<String> isAnnotationType) {
        if (!Flags.hasFlag(module.flags, Flags.ANNOTATION)) {
            return;
        }
        for (final IRFunction function : List.copyOf(module.functions())) {
            if (!function.blocks().isEmpty() || function.isExternal() || function.params.size() != 1
                    || Flags.hasFlag(function.getFlags(), Flags.STATIC)) {
                continue;
            }
            final IRFunction body = body(function, isAnnotationType);
            if (body != null) {
                module.removeFunction(function);
                module.addFunction(body);
            }
        }
    }

    private static IRFunction body(final IRFunction element,
                                   final java.util.function.Predicate<String> isAnnotationType) {
        final String name = elementName(element.name);
        final IRType type = element.returnType;
        final String descriptor = type instanceof IRType.Ptr ptr ? ptr.jvmDescriptor() : null;
        final Object dflt = element.annotationDefault() != null ? element.annotationDefault().getValue() : null;

        final IRBuilder builder = new IRBuilder(0, "annotation-accessor");
        builder.beginFunction(element.name, type, element.params,
                element.getFlags() & ~Flags.ABSTRACT, false);
        final IRValue self = element.params.getFirst();
        final IRValue elementName = IRValue.ofString(name);
        final IRValue result;
        if (type instanceof IRType.Bool) {
            result = builder.emitCall(CallKind.STATIC, HELPERS + "bool", IRType.BOOL,
                    List.of(OBJECT, STRING, IRType.BOOL),
                    List.of(self, elementName, IRValue.ofBool(Boolean.TRUE.equals(dflt))));
        } else if (type instanceof IRType.Int || type instanceof IRType.Float) {
            final double number = dflt instanceof Number n ? n.doubleValue()
                    : dflt instanceof Character c ? c : 0.0;
            final IRValue value = builder.emitCall(CallKind.STATIC, HELPERS + "number", IRType.F64,
                    List.of(OBJECT, STRING, IRType.F64),
                    List.of(self, elementName, new IRValue.ConstFloat(number, IRType.F64)));
            result = type instanceof IRType.Float f && f.bits() == 64 ? value : builder.emitCast(value, type);
        } else if ("Ljava/lang/String;".equals(descriptor)) {
            result = builder.emitCall(CallKind.STATIC, HELPERS + "text", type, List.of(OBJECT, STRING, STRING),
                    List.of(self, elementName, stringOrNull(dflt == null ? null : String.valueOf(dflt))));
        } else if ("Ljava/lang/Class;".equals(descriptor)) {
            result = builder.emitCall(CallKind.STATIC, HELPERS + "type", type, List.of(OBJECT, STRING, STRING),
                    List.of(self, elementName, stringOrNull(className(dflt))));
        } else if ("[Ljava/lang/String;".equals(descriptor) || "[Ljava/lang/Class;".equals(descriptor)) {
            final boolean classes = descriptor.contains("Class");
            final List<String> items = new ArrayList<>();
            if (dflt instanceof List<?> list) {
                for (final Object item : list) {
                    final Object value = item instanceof AnnotationValue v ? v.getValue() : item;
                    items.add(classes ? className(value) : String.valueOf(value));
                }
            }
            result = builder.emitCall(CallKind.STATIC, HELPERS + (classes ? "types" : "texts"), type,
                    List.of(OBJECT, STRING, STRING),
                    List.of(self, elementName, IRValue.ofString(String.join(",", items))));
        } else if (descriptor != null && descriptor.startsWith("[") && descriptor.length() == 2) {
            // An array of primitives, from its comma-separated items.
            result = builder.emitCast(builder.emitCall(CallKind.STATIC, HELPERS + "primitives", OBJECT,
                    List.of(OBJECT, STRING, STRING, STRING),
                    List.of(self, elementName, IRValue.ofString(String.join(",", items(dflt))),
                            IRValue.ofString(descriptor))), type);
        } else if (descriptor != null && descriptor.startsWith("[L")) {
            final String component = descriptor.substring(2, descriptor.length() - 1);
            final IRType.Ptr componentType = new IRType.Ptr(IRType.I8, "L" + component + ";");
            if (isAnnotationType.test(component)) {
                result = builder.emitCast(builder.emitCall(CallKind.STATIC, HELPERS + "annotations", OBJECT,
                        List.of(OBJECT, STRING, STRING),
                        List.of(self, elementName, IRValue.ofString(component))), type);
            } else {
                // An array of enum constants, by name.
                final IRType stringArray = new IRType.Ptr(STRING, "[Ljava/lang/String;");
                final IRValue names = builder.emitCall(CallKind.STATIC, HELPERS + "texts", stringArray,
                        List.of(OBJECT, STRING, STRING),
                        List.of(self, elementName, IRValue.ofString(String.join(",", items(dflt)))));
                result = builder.emitCast(builder.emitCall(CallKind.STATIC,
                        RuntimeCalls.RUNTIME_PACKAGE.replace('/', '.') + "AnnotationSupport_enumValues", OBJECT,
                        List.of(stringArray, new IRType.Ptr(IRType.I8, "Ljava/lang/Class;")),
                        List.of(names, new IRValue.ConstClass(componentType))), type);
            }
        } else if (descriptor != null && descriptor.startsWith("L")
                && isAnnotationType.test(descriptor.substring(1, descriptor.length() - 1))) {
            // A nested annotation: its own entry in the metadata.
            result = builder.emitCast(builder.emitCall(CallKind.STATIC, HELPERS + "annotation", OBJECT,
                    List.of(OBJECT, STRING, STRING),
                    List.of(self, elementName, IRValue.ofString(descriptor.substring(1, descriptor.length() - 1)))),
                    type);
        } else if (descriptor != null && descriptor.startsWith("L")) {
            // An enum (the metadata holds the constant's name).
            final String constant = dflt instanceof io.github.potjerodekool.nabu.lang.model.element.VariableElement variable
                    ? variable.getSimpleName() : null;
            final IRValue constantName = builder.emitCall(CallKind.STATIC, HELPERS + "text", STRING,
                    List.of(OBJECT, STRING, STRING), List.of(self, elementName, stringOrNull(constant)));
            final IRType.Ptr classType = new IRType.Ptr(IRType.I8, "Ljava/lang/Class;");
            final IRValue value = builder.emitCall(CallKind.STATIC,
                    RuntimeCalls.RUNTIME_PACKAGE.replace('/', '.') + "AnnotationSupport_enumValue", OBJECT,
                    List.of(STRING, classType),
                    List.of(constantName, new IRValue.ConstClass((IRType.Ptr) type)));
            result = builder.emitCast(value, type);
        } else {
            builder.endFunction();
            return null;
        }
        builder.emitReturn(result);
        builder.endFunction();
        final IRFunction function = builder.build().functions().getFirst();
        function.setAnnotationDefault(element.annotationDefault());
        return function;
    }

    /** The items of an array default as the metadata has them: names of enum constants, values. */
    private static List<String> items(final Object dflt) {
        final List<String> items = new ArrayList<>();
        if (dflt instanceof List<?> list) {
            for (final Object item : list) {
                final Object value = item instanceof AnnotationValue v ? v.getValue() : item;
                items.add(value instanceof io.github.potjerodekool.nabu.lang.model.element.VariableElement variable
                        ? variable.getSimpleName() : String.valueOf(value));
            }
        }
        return items;
    }

    private static IRValue stringOrNull(final String value) {
        return value == null ? new IRValue.ConstNull(STRING) : IRValue.ofString(value);
    }

    /** The internal name of a Class value (a type, or a class attribute holding one). */
    private static String className(final Object value) {
        final Object type = value instanceof ClassAttribute attribute ? attribute.getValue() : value;
        if (type instanceof TypeMirror mirror && mirror.asTypeElement() != null) {
            return ElementSignatures.internalName(mirror.asTypeElement());
        }
        return null;
    }

    /** The element name of a function {@code Owner_name}. */
    private static String elementName(final String functionName) {
        final int split = functionName.lastIndexOf('_');
        return split >= 0 ? functionName.substring(split + 1) : functionName;
    }
}
