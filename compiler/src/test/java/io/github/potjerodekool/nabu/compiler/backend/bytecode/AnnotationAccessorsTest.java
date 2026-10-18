package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * De elementen van een annotatie-interface uit bytecode krijgen een body die
 * de waarde via {@code nabu/runtime/Annotations} uit de metadata leest, met
 * de default uit {@code AnnotationDefault}.
 */
class AnnotationAccessorsTest {

    private static ClassNode annotation(final MethodNode... elements) {
        final ClassNode node = new ClassNode();
        node.name = "lib/Command";
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION;
        node.superName = "java/lang/Object";
        node.methods.addAll(List.of(elements));
        return node;
    }

    private static MethodNode element(final String name, final String desc, final Object dflt) {
        final MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null);
        method.annotationDefault = dflt;
        return method;
    }

    private static ClassNode enumClass() {
        final ClassNode node = new ClassNode();
        node.name = "lib/Scope";
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ENUM;
        return node;
    }

    private static List<String> calls(final MethodNode method) {
        final List<String> result = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call) {
                result.add(call.owner + "." + call.name);
            }
        }
        return result;
    }

    private static List<Object> constants(final MethodNode method) {
        final List<Object> result = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc) {
                result.add(ldc.cst);
            }
        }
        return result;
    }

    @Test
    void stringElementWithDefault() {
        final MethodNode separator = element("separator", "()Ljava/lang/String;", "=");
        AnnotationAccessors.implementElements(annotation(separator), name -> null);

        assertEquals(0, separator.access & Opcodes.ACC_ABSTRACT);
        assertEquals(List.of("nabu/runtime/Annotations.text"), calls(separator));
        assertEquals(List.of("separator", "="), constants(separator));
    }

    @Test
    void primitiveClassAndArrayElements() {
        final MethodNode width = element("usageHelpWidth", "()I", 80);
        final MethodNode hidden = element("hidden", "()Z", false);
        final MethodNode provider = element("versionProvider", "()Ljava/lang/Class;",
                Type.getObjectType("lib/NoVersionProvider"));
        final MethodNode names = element("names", "()[Ljava/lang/String;", null);
        AnnotationAccessors.implementElements(annotation(width, hidden, provider, names), name -> null);

        assertEquals(List.of("nabu/runtime/Annotations.number"), calls(width));
        assertEquals(List.of("nabu/runtime/Annotations.bool"), calls(hidden));
        assertEquals(List.of("nabu/runtime/Annotations.type"), calls(provider));
        assertTrue(constants(provider).contains("lib/NoVersionProvider"));
        assertEquals(List.of("nabu/runtime/Annotations.texts"), calls(names));
    }

    @Test
    void enumElementUsesTheRealConstant() {
        final MethodNode scope = element("scope", "()Llib/Scope;", new String[] {"Llib/Scope;", "LOCAL"});
        final ClassNode scopeEnum = enumClass();
        AnnotationAccessors.implementElements(annotation(scope),
                name -> "lib/Scope".equals(name) ? scopeEnum : null);

        assertEquals(List.of("nabu/runtime/Annotations.text", "lib/Scope.valueOf"), calls(scope));
        assertTrue(constants(scope).contains("LOCAL"));
    }

    @Test
    void unsupportedElementStaysAbstract() {
        final MethodNode nested = element("group", "()Llib/Group;", null);
        AnnotationAccessors.implementElements(annotation(nested), name -> null);

        assertTrue((nested.access & Opcodes.ACC_ABSTRACT) != 0);
    }
}
