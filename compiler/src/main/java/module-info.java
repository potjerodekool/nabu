import io.github.potjerodekool.nabu.compiler.NabuCompiler;

module io.github.potjerodekool.nabu.compiler {
    provides io.github.potjerodekool.nabu.tools.Compiler with NabuCompiler;
    uses io.github.potjerodekool.nabu.tools.Compiler;
    uses javax.annotation.processing.Processor;
    exports io.github.potjerodekool.nabu.compiler;  //Export the compiler so it can be used.
    exports io.github.potjerodekool.nabu.lang.spi;
    exports io.github.potjerodekool.nabu.log;
    exports io.github.potjerodekool.nabu.tools;
    exports io.github.potjerodekool.nabu.tree;
    exports io.github.potjerodekool.nabu.tree.expression;
    exports io.github.potjerodekool.nabu.tree.element;
    exports io.github.potjerodekool.nabu.tree.expression.impl;
    exports io.github.potjerodekool.nabu.tree.statement;
    requires org.antlr.antlr4.runtime; //Used for lexer and parsing.
    requires org.objectweb.asm.util; //Used for reading and writing .class files.
    requires org.objectweb.asm; //Used for reading and writing .class files.
    requires java.xml; //Used for parsing plugin.xml files.
    requires java.compiler; //Used to support annotation processing.
    requires org.bytedeco.llvm;
    requires org.bytedeco.javacpp; //Used by native backend
    requires lombok;
    requires jdk.compiler;
}