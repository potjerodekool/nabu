package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.function.Function;

/**
 * Annotatie-instanties maakt de C-runtime (uit de reflectie-metadata); een
 * aanroep van een annotatie-element ({@code command.separator()}) gaat direct
 * naar de functie van het annotatie-interface, want niemand implementeert
 * het. Deze klasse geeft elk element van een annotatie-interface uit bytecode
 * daarom een body: lees de waarde uit de metadata via
 * {@value #CLASS}, met de default uit het {@code AnnotationDefault}-attribuut.
 *
 * <p>Ondersteund: String, primitieven, Class, enum, geneste annotaties, en
 * arrays van String, Class, primitieven, enums en annotaties.
 */
final class AnnotationAccessors {

    /** Gesynthetiseerde klasse met native helpers; jcl_lite.c implementeert ze. */
    static final String CLASS = "nabu/runtime/Annotations";

    private static final String OBJ = "Ljava/lang/Object;";
    private static final String STR = "Ljava/lang/String;";
    private static final String TEXT_DESC = "(" + OBJ + STR + STR + ")" + STR;
    private static final String TEXTS_DESC = "(" + OBJ + STR + STR + ")[" + STR;
    private static final String BOOL_DESC = "(" + OBJ + STR + "Z)Z";
    private static final String NUMBER_DESC = "(" + OBJ + STR + "D)D";
    private static final String TYPE_DESC = "(" + OBJ + STR + STR + ")Ljava/lang/Class;";
    private static final String TYPES_DESC = "(" + OBJ + STR + STR + ")[Ljava/lang/Class;";
    /** (annotation, element, annotation type) → the nested annotation(s). */
    static final String ANNOTATION_DESC = "(" + OBJ + STR + STR + ")" + OBJ;
    /** (annotation, element, default as text, array descriptor) → a primitive array. */
    static final String PRIMITIVES_DESC = "(" + OBJ + STR + STR + STR + ")" + OBJ;
    static final String ENUM_VALUES_DESC = "([" + STR + "Ljava/lang/Class;)" + OBJ;
    static final String ANNOTATION_SUPPORT = RuntimeCalls.RUNTIME_PACKAGE + "AnnotationSupport";

    private AnnotationAccessors() {
    }

    static ClassNode build() {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = CLASS;
        node.superName = "java/lang/Object";
        final int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE;
        node.methods.add(new MethodNode(access, "text", TEXT_DESC, null, null));
        node.methods.add(new MethodNode(access, "texts", TEXTS_DESC, null, null));
        node.methods.add(new MethodNode(access, "bool", BOOL_DESC, null, null));
        node.methods.add(new MethodNode(access, "number", NUMBER_DESC, null, null));
        node.methods.add(new MethodNode(access, "type", TYPE_DESC, null, null));
        node.methods.add(new MethodNode(access, "types", TYPES_DESC, null, null));
        node.methods.add(new MethodNode(access, "annotation", ANNOTATION_DESC, null, null));
        node.methods.add(new MethodNode(access, "annotations", ANNOTATION_DESC, null, null));
        node.methods.add(new MethodNode(access, "primitives", PRIMITIVES_DESC, null, null));
        return node;
    }

    /**
     * Geeft de abstracte elementen van een annotatie-interface een body.
     *
     * @param classLoader laadt het type van een element, om een enum te herkennen.
     */
    static void implementElements(final ClassNode classNode,
                                  final Function<String, ClassNode> classLoader) {
        if ((classNode.access & Opcodes.ACC_ANNOTATION) == 0) {
            return;
        }
        for (final MethodNode method : classNode.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0
                    || (method.access & Opcodes.ACC_STATIC) != 0
                    || !method.desc.startsWith("()")) {
                continue;
            }
            final InsnList body = body(method, classLoader);
            if (body != null) {
                method.access &= ~Opcodes.ACC_ABSTRACT;
                method.instructions = body;
                method.maxStack = 6;
                method.maxLocals = 1;
            }
        }
    }

    private static InsnList body(final MethodNode method,
                                 final Function<String, ClassNode> classLoader) {
        final Type type = Type.getReturnType(method.desc);
        final Object dflt = method.annotationDefault;
        final InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode(method.name));

        switch (type.getSort()) {
            case Type.BOOLEAN -> {
                code.add(new InsnNode(Boolean.TRUE.equals(dflt) ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                code.add(helper("bool", BOOL_DESC));
                code.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.BYTE, Type.SHORT, Type.CHAR, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE -> {
                code.add(new LdcInsnNode(numberOf(dflt)));
                code.add(helper("number", NUMBER_DESC));
                switch (type.getSort()) {
                    case Type.BYTE -> code.add(convert(Opcodes.D2I, Opcodes.I2B));
                    case Type.SHORT -> code.add(convert(Opcodes.D2I, Opcodes.I2S));
                    case Type.CHAR -> code.add(convert(Opcodes.D2I, Opcodes.I2C));
                    case Type.INT -> code.add(new InsnNode(Opcodes.D2I));
                    case Type.LONG -> code.add(new InsnNode(Opcodes.D2L));
                    case Type.FLOAT -> code.add(new InsnNode(Opcodes.D2F));
                    default -> {
                    }
                }
                code.add(new InsnNode(type.getOpcode(Opcodes.IRETURN)));
            }
            case Type.OBJECT -> {
                final String internal = type.getInternalName();
                if ("java/lang/String".equals(internal)) {
                    code.add(stringConstant(dflt));
                    code.add(helper("text", TEXT_DESC));
                } else if ("java/lang/Class".equals(internal)) {
                    code.add(stringConstant(dflt instanceof Type t ? t.getInternalName() : null));
                    code.add(helper("type", TYPE_DESC));
                } else if (isEnum(internal, classLoader)) {
                    // De echte enum-constante: picocli vergelijkt met ==.
                    code.add(stringConstant(dflt instanceof String[] e && e.length == 2 ? e[1] : null));
                    code.add(helper("text", TEXT_DESC));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internal, "valueOf",
                            "(" + STR + ")L" + internal + ";", false));
                } else if (isAnnotation(internal, classLoader)) {
                    // A nested annotation: its own entry in the metadata.
                    code.add(new LdcInsnNode(internal));
                    code.add(helper("annotation", ANNOTATION_DESC));
                    code.add(new TypeInsnNode(Opcodes.CHECKCAST, internal));
                } else {
                    return null;
                }
                code.add(new InsnNode(Opcodes.ARETURN));
            }
            case Type.ARRAY -> {
                if (type.getDimensions() != 1) {
                    return null;
                }
                final String element = type.getElementType().getDescriptor();
                if (STR.equals(element)) {
                    code.add(stringConstant(joined(dflt, Function.identity())));
                    code.add(helper("texts", TEXTS_DESC));
                } else if ("Ljava/lang/Class;".equals(element)) {
                    code.add(stringConstant(joined(dflt, v -> v instanceof Type t ? t.getInternalName() : v)));
                    code.add(helper("types", TYPES_DESC));
                } else if (type.getElementType().getSort() != Type.OBJECT) {
                    // An array of primitives, from its comma-separated items.
                    code.add(stringConstant(joined(dflt, Function.identity())));
                    code.add(new LdcInsnNode(type.getDescriptor()));
                    code.add(helper("primitives", PRIMITIVES_DESC));
                    code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
                } else if (isEnum(type.getElementType().getInternalName(), classLoader)) {
                    // Enum.valueOf finds the enum through its values() in the
                    // runtime's enum table: reach it (the result is unused).
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, type.getElementType().getInternalName(),
                            "values", "()" + type.getDescriptor(), false));
                    code.add(new InsnNode(Opcodes.POP));
                    code.add(stringConstant(joined(dflt, v -> v instanceof String[] e && e.length == 2 ? e[1] : v)));
                    code.add(helper("texts", TEXTS_DESC));
                    code.add(new LdcInsnNode(type.getElementType()));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ANNOTATION_SUPPORT, "enumValues",
                            ENUM_VALUES_DESC, false));
                    code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
                } else if (isAnnotation(type.getElementType().getInternalName(), classLoader)) {
                    code.add(new LdcInsnNode(type.getElementType().getInternalName()));
                    code.add(helper("annotations", ANNOTATION_DESC));
                    code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
                } else {
                    return null;
                }
                code.add(new InsnNode(Opcodes.ARETURN));
            }
            default -> {
                return null;
            }
        }
        return code;
    }

    private static boolean isAnnotation(final String internalName,
                                        final Function<String, ClassNode> classLoader) {
        final ClassNode node = classLoader.apply(internalName);
        return node != null && (node.access & Opcodes.ACC_ANNOTATION) != 0;
    }

    private static boolean isEnum(final String internalName,
                                  final Function<String, ClassNode> classLoader) {
        final ClassNode node = classLoader.apply(internalName);
        return node != null && (node.access & Opcodes.ACC_ENUM) != 0;
    }

    private static MethodInsnNode helper(final String name, final String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, CLASS, name, desc, false);
    }

    private static InsnList convert(final int first, final int second) {
        final InsnList list = new InsnList();
        list.add(new InsnNode(first));
        list.add(new InsnNode(second));
        return list;
    }

    private static Double numberOf(final Object dflt) {
        if (dflt instanceof Number n) {
            return n.doubleValue();
        }
        if (dflt instanceof Character c) {
            return (double) c;
        }
        return 0.0;
    }

    private static org.objectweb.asm.tree.AbstractInsnNode stringConstant(final Object value) {
        return value == null ? new InsnNode(Opcodes.ACONST_NULL) : new LdcInsnNode(String.valueOf(value));
    }

    /** Een array-default als komma-gescheiden tekst, het formaat van de reflectie-metadata. */
    private static String joined(final Object dflt, final Function<Object, Object> element) {
        if (!(dflt instanceof List<?> list)) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final Object item : list) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(element.apply(item));
        }
        return sb.toString();
    }
}
