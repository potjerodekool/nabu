package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.nabu.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.tools.FileObject;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;

/**
 * Asserts trees of Nabu language.
 */
public final class JavaLangTreeAssert extends AbstractTreeAssert<Java20Parser> {

    public static final JavaLangTreeAssert INSTANCE = new JavaLangTreeAssert();

    private JavaLangTreeAssert() {
        super(".java");
    }

    @Override
    protected Java20Parser createParser(final CodePointCharStream inputSteam) {
        return new Java20Parser(new CommonTokenStream(new Java20Lexer(inputSteam)));
    }

}