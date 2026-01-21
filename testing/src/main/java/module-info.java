module io.github.potjerodekool.compiler.testing {
    uses io.github.potjerodekool.nabu.tools.Compiler;
    exports io.github.potjerodekool.nabu.testing;
    requires io.github.potjerodekool.compiler.api;
    requires org.antlr.antlr4.runtime;
    requires org.junit.jupiter.api;
}