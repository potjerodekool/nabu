module io.github.potjerodekool.nabu.daemon {
    requires io.github.potjerodekool.nabu.compiler;
    requires java.logging;

    exports io.github.potjerodekool.nabu.compiler.daemon;
    opens io.github.potjerodekool.nabu.compiler.daemon to org.junit.platform.commons;

}