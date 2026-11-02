package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.AbstractTreeAssert;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java25Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java25Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.tools.FileObject;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;

/**
 * Asserts trees of Nabu language.
 */
public final class JavaLangTreeAssert extends AbstractTreeAssert<Java25Parser> {

    public static final JavaLangTreeAssert INSTANCE = new JavaLangTreeAssert();

    private JavaLangTreeAssert() {
        super(".java");
    }

    @Override
    protected Java25Parser createParser(final CodePointCharStream inputSteam) {
        return new Java25Parser(new CommonTokenStream(new Java25Lexer(inputSteam)));
    }

    @Override
    protected AbstractParseTreeVisitor<?> createVisitor(final FileObject fileObject) {
        return new JavaCompilerVisitor(fileObject);
    }

}