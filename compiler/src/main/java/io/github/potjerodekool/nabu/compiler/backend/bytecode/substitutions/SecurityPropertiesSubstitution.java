package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import java.security.Security;

/**
 * {@code Security.SecPropLoader.loadMaster}: the JDK reads the master {@code java.security} file from
 * {@code $JAVA_HOME/conf/security}, which a native executable does not have. These are the defaults
 * that file sets and that the providers built into the executable need.
 */
public final class SecurityPropertiesSubstitution {

    private SecurityPropertiesSubstitution() {
    }

    private static void loadMaster() {
        Security.setProperty("security.provider.1", "SUN");
        Security.setProperty("security.provider.2", "SunRsaSign");
        Security.setProperty("security.provider.3", "SunJCE");
        Security.setProperty("securerandom.source", "file:/dev/random");
        Security.setProperty("securerandom.strongAlgorithms", "DRBG:SUN");
        Security.setProperty("keystore.type", "pkcs12");
        Security.setProperty("keystore.type.compat", "true");
        Security.setProperty("package.access", "sun.misc.,sun.reflect.");
        Security.setProperty("package.definition", "sun.misc.,sun.reflect.");
        Security.setProperty("security.overridePropertiesFile", "true");
        Security.setProperty("ssl.KeyManagerFactory.algorithm", "SunX509");
        Security.setProperty("ssl.TrustManagerFactory.algorithm", "PKIX");
        Security.setProperty("networkaddress.cache.negative.ttl", "10");
        Security.setProperty("krb5.kdc.bad.policy", "tryLast");
        Security.setProperty("jdk.security.legacyAlgorithms", "RSA keySize < 2048, DSA keySize < 2048, DES, DESede, MD5, RC2, ARCFOUR");
    }
}
