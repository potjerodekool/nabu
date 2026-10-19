package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Redirects calls to VM-bound JDK methods to static helpers in the runtime
 * package, in JDK and library bytecode and in the IR of source classes
 * alike: resource lookups to {@code ResourceSupport}, dynamic proxies and
 * {@code Method.invoke} to {@code ProxySupport}. Class, ClassLoader, Module,
 * Proxy and Method are modelled by the C runtime, so their own methods
 * cannot run; a helper takes the receiver of an instance method as its first
 * argument instead.
 *
 * <p>The helpers themselves are not rewritten: {@code ProxySupport.invoke}
 * still reaches the C runtime's {@code Method.invoke} for other methods.
 */
final class RuntimeCalls {

    /** The package of the helpers: these classes are read from the compiler itself. */
    static final String RUNTIME_PACKAGE = "io/github/potjerodekool/nabu/compiler/backend/bytecode/runtime/";

    static final String RESOURCE_SUPPORT = RUNTIME_PACKAGE + "ResourceSupport";

    static final String PROXY_SUPPORT = RUNTIME_PACKAGE + "ProxySupport";

    private record Redirect(String owner, String name, String desc, boolean isStatic,
                            String targetClass, String target) {

        String targetDesc() {
            return isStatic ? desc : "(L" + owner + ";" + desc.substring(1);
        }
    }

    private static final List<Redirect> REDIRECTS = List.of(
            new Redirect("java/lang/Class", "getResourceAsStream",
                    "(Ljava/lang/String;)Ljava/io/InputStream;", false, RESOURCE_SUPPORT, "classResourceAsStream"),
            new Redirect("java/lang/Class", "getResource",
                    "(Ljava/lang/String;)Ljava/net/URL;", false, RESOURCE_SUPPORT, "classResource"),
            new Redirect("java/lang/ClassLoader", "getResourceAsStream",
                    "(Ljava/lang/String;)Ljava/io/InputStream;", false, RESOURCE_SUPPORT, "loaderResourceAsStream"),
            new Redirect("java/lang/ClassLoader", "getResource",
                    "(Ljava/lang/String;)Ljava/net/URL;", false, RESOURCE_SUPPORT, "loaderResource"),
            new Redirect("java/lang/ClassLoader", "getResources",
                    "(Ljava/lang/String;)Ljava/util/Enumeration;", false, RESOURCE_SUPPORT, "loaderResources"),
            new Redirect("java/lang/ClassLoader", "getSystemResourceAsStream",
                    "(Ljava/lang/String;)Ljava/io/InputStream;", true, RESOURCE_SUPPORT, "systemResourceAsStream"),
            new Redirect("java/lang/ClassLoader", "getSystemResource",
                    "(Ljava/lang/String;)Ljava/net/URL;", true, RESOURCE_SUPPORT, "systemResource"),
            new Redirect("java/lang/ClassLoader", "getSystemResources",
                    "(Ljava/lang/String;)Ljava/util/Enumeration;", true, RESOURCE_SUPPORT, "systemResources"),
            new Redirect("java/lang/Module", "getResourceAsStream",
                    "(Ljava/lang/String;)Ljava/io/InputStream;", false, RESOURCE_SUPPORT, "moduleResourceAsStream"),
            new Redirect("java/lang/reflect/Proxy", "newProxyInstance",
                    "(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;",
                    true, PROXY_SUPPORT, "newProxyInstance"),
            new Redirect("java/lang/reflect/Proxy", "getInvocationHandler",
                    "(Ljava/lang/Object;)Ljava/lang/reflect/InvocationHandler;", true, PROXY_SUPPORT,
                    "getInvocationHandler"),
            new Redirect("java/lang/reflect/Proxy", "isProxyClass",
                    "(Ljava/lang/Class;)Z", true, PROXY_SUPPORT, "isProxyClass"),
            new Redirect("java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false, PROXY_SUPPORT, "invoke"));

    private RuntimeCalls() {
    }

    /** Rewrites the calls in lowered bytecode: the stack shape (receiver + arguments) stays. */
    static void rewrite(final ClassNode classNode) {
        if (classNode.name.startsWith(RUNTIME_PACKAGE)) {
            return;
        }
        for (final MethodNode method : classNode.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (final AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    final Redirect redirect = find(call.owner, call.name, call.desc);
                    if (redirect != null && (call.getOpcode() == Opcodes.INVOKESTATIC) == redirect.isStatic()) {
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                redirect.targetClass(), redirect.target(), redirect.targetDesc(), false));
                    }
                }
            }
        }
    }

    /**
     * Rewrites the calls in the IR of source classes: a virtual call carries
     * the receiver as its first argument but not in its parameter types; the
     * static call gets it in both.
     *
     * @return whether anything changed.
     */
    static boolean rewrite(final IRModule module) {
        boolean changed = false;
        for (final var function : module.functions()) {
            for (final var block : function.blocks()) {
                final var instructions = block.instructions();
                for (int i = 0; i < instructions.size(); i++) {
                    if (instructions.get(i) instanceof IRInstruction.Call call && call.function() != null) {
                        final Redirect redirect = find(call);
                        if (redirect == null) {
                            continue;
                        }
                        final List<IRType> paramTypes = new ArrayList<>(call.paramTypes());
                        if (!redirect.isStatic()) {
                            paramTypes.add(0, new IRType.Ptr(IRType.I8, "L" + redirect.owner() + ";"));
                        }
                        block.setInstruction(i, new IRInstruction.Call(CallKind.STATIC, call.returnType(),
                                paramTypes, call.result(),
                                redirect.targetClass().replace('/', '.') + "_" + redirect.target(),
                                call.args(), call.location()));
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static Redirect find(final String owner, final String name, final String desc) {
        for (final Redirect redirect : REDIRECTS) {
            if (redirect.owner().equals(owner) && redirect.name().equals(name) && redirect.desc().equals(desc)) {
                return redirect;
            }
        }
        return null;
    }

    private static Redirect find(final IRInstruction.Call call) {
        for (final Redirect redirect : REDIRECTS) {
            final boolean isStatic = call.callKind() == CallKind.STATIC;
            if (isStatic == redirect.isStatic()
                    && call.function().equals(redirect.owner().replace('/', '.') + "_" + redirect.name())
                    && call.paramTypes().size() == Type.getArgumentTypes(redirect.desc()).length) {
                return redirect;
            }
        }
        return null;
    }
}
