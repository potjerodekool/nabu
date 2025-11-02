module io.github.potjerodekool.nabu.compiler {
    exports io.github.potjerodekool.nabu.compiler.log;
    exports io.github.potjerodekool.nabu.compiler.impl;
    requires io.github.potjerodekool.compiler.api;
    requires org.antlr.antlr4.runtime;
    requires org.objectweb.asm.util;
    requires org.objectweb.asm;
    requires java.xml;
}