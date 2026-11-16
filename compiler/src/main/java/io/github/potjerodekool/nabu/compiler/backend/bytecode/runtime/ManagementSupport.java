package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ManagementFactory.getRuntimeMXBean} for native programs. The JDK's management classes load a native library
 * and talk to the VM; a native executable has no VM to ask. Frameworks use the bean for a few facts (Spring Boot: the
 * process id and the uptime for its start-up message, the class path for its static resources), which are answered
 * here from the runtime. Written for bytecode lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class ManagementSupport {

    private static RuntimeMXBean runtime;

    private ManagementSupport() {
    }

    /** The time the process started, in milliseconds since the epoch (written by the C runtime at start-up). */
    private static native long startTime0();

    private static Runtime.Version version;

    /** {@code Runtime.version()}: the version of the JDK the program was compiled with. */
    public static synchronized Runtime.Version runtimeVersion() {
        if (version == null) {
            version = Runtime.Version.parse("25.0.4+1");
        }
        return version;
    }

    public static synchronized RuntimeMXBean runtimeMXBean() {
        if (runtime == null) {
            runtime = new NativeRuntimeMXBean();
        }
        return runtime;
    }

    private static final class NativeRuntimeMXBean implements RuntimeMXBean {

        @Override
        public long getPid() {
            return ProcessHandle.current().pid();
        }

        @Override
        public String getName() {
            final StringBuilder name = new StringBuilder();
            name.append(getPid());
            name.append("@localhost");
            return name.toString();
        }

        @Override
        public String getVmName() {
            return "Nabu native";
        }

        @Override
        public String getVmVendor() {
            return "Nabu";
        }

        @Override
        public String getVmVersion() {
            return "25";
        }

        @Override
        public String getSpecName() {
            return "Java Virtual Machine Specification";
        }

        @Override
        public String getSpecVendor() {
            return "Oracle Corporation";
        }

        @Override
        public String getSpecVersion() {
            return "25";
        }

        @Override
        public String getManagementSpecVersion() {
            return "4.0";
        }

        @Override
        public String getClassPath() {
            return System.getProperty("java.class.path", "");
        }

        @Override
        public String getLibraryPath() {
            return "";
        }

        @Override
        public boolean isBootClassPathSupported() {
            return false;
        }

        @Override
        public String getBootClassPath() {
            throw new UnsupportedOperationException("boot class path is not supported");
        }

        @Override
        public List<String> getInputArguments() {
            return List.of();
        }

        @Override
        public long getUptime() {
            return System.currentTimeMillis() - startTime0();
        }

        @Override
        public long getStartTime() {
            return startTime0();
        }

        @Override
        public Map<String, String> getSystemProperties() {
            final Map<String, String> map = new HashMap<String, String>();
            for (final String name : System.getProperties().stringPropertyNames()) {
                map.put(name, System.getProperty(name));
            }
            return map;
        }

        @Override
        public javax.management.ObjectName getObjectName() {
            return null;
        }
    }
}
