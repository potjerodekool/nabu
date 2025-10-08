module io.github.potjerodekool.nabu.compiler {
    uses io.github.potjerodekool.nabu.compiler.lang.support.LanguageParser;
    exports io.github.potjerodekool.nabu.compiler;
    exports io.github.potjerodekool.nabu.compiler.diagnostic;
    exports io.github.potjerodekool.nabu.compiler.resolve;
    exports io.github.potjerodekool.nabu.compiler.resolve.scope;
    exports io.github.potjerodekool.nabu.compiler.transform;
    exports io.github.potjerodekool.nabu.compiler.tree;
    exports io.github.potjerodekool.nabu.compiler.tree.element;
    exports io.github.potjerodekool.nabu.compiler.tree.element.builder;
    exports io.github.potjerodekool.nabu.compiler.tree.expression;
    exports io.github.potjerodekool.nabu.compiler.tree.expression.builder;
    exports io.github.potjerodekool.nabu.compiler.tree.statement;
    exports io.github.potjerodekool.nabu.compiler.tree.statement.builder;
    exports io.github.potjerodekool.nabu.compiler.util;
    exports io.github.potjerodekool.nabu.compiler.resolve.spi;
    exports io.github.potjerodekool.nabu.compiler.io;
    exports io.github.potjerodekool.nabu.compiler.resolve.method;
    exports io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;
    requires org.antlr.antlr4.runtime;
    requires org.objectweb.asm.util;

    requires org.objectweb.asm;
    requires io.github.potjerodekool.dependency;
    requires io.github.potjerodekool.compiler.ast;
}