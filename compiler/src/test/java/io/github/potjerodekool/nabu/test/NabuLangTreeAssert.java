package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.tools.FileObject;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;

/**
 * Asserts trees of Nabu language.
 */
public final class NabuLangTreeAssert extends AbstractTreeAssert<NabuParser> {

    public static final NabuLangTreeAssert INSTANCE = new NabuLangTreeAssert();

    private NabuLangTreeAssert() {
        super(".nabu");
    }

    @Override
    protected NabuParser createParser(final  CodePointCharStream inputSteam) {
        return new NabuParser(new CommonTokenStream(new NabuLexer(inputSteam)));
    }

    @Override
    protected AbstractParseTreeVisitor<?> createVisitor(final FileObject fileObject) {
        return new NabuCompilerVisitor(fileObject);
    }
}