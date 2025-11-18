module io.github.potjerodekool.nabu.compiler {
    exports io.github.potjerodekool.nabu.compiler.ast.symbol.impl to io.github.potjerodekool.compiler.testing;
    exports io.github.potjerodekool.nabu to io.github.potjerodekool.compiler.testing;
    exports io.github.potjerodekool.nabu.compiler.io.impl to io.github.potjerodekool.compiler.testing;
    exports io.github.potjerodekool.nabu.compiler.internal to io.github.potjerodekool.compiler.testing;
    exports io.github.potjerodekool.nabu.compiler.frontend.parser.nabu to io.github.potjerodekool.compiler.testing;
    exports io.github.potjerodekool.nabu.compiler.resolve.internal to io.github.potjerodekool.compiler.testing;
    exports io.github.potjerodekool.nabu.compiler.resolve.impl to io.github.potjerodekool.compiler.testing;
    exports io.github.potjerodekool.nabu.compiler;
    requires io.github.potjerodekool.compiler.api;
    requires org.antlr.antlr4.runtime; //Used for lexer and parsing.
    requires org.objectweb.asm.util; //Used for reading and writing .class files.
    requires org.objectweb.asm; //Used for reading and writing .class files.
    requires java.xml; //Used for parsing plugin.xml files.
}