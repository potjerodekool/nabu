import io.github.potjerodekool.nabu.compiler.NabuCompiler;

open module io.github.potjerodekool.nabu.compiler {
    provides io.github.potjerodekool.nabu.tools.Compiler with NabuCompiler;
    uses io.github.potjerodekool.nabu.tools.Compiler;
    uses javax.annotation.processing.Processor;
    exports io.github.potjerodekool.nabu.compiler;  //Export the compiler so it can be used.
    exports io.github.potjerodekool.nabu.compiler.resolve.impl;
    requires org.antlr.antlr4.runtime; //Used for lexer and parsing.
    requires org.objectweb.asm.util; //Used for reading and writing .class files.
    requires org.objectweb.asm; //Used for reading and writing .class files.
    requires java.xml; //Used for parsing plugin.xml files.
    requires java.compiler; //Used to support annotation processing.
    requires jdk.compiler; requires io.github.potjerodekool.compiler.api;
    exports io.github.potjerodekool.nabu.compiler.backend.asm;
    exports io.github.potjerodekool.nabu.compiler.resolve.asm;
    exports io.github.potjerodekool.nabu.compiler.extension;
}