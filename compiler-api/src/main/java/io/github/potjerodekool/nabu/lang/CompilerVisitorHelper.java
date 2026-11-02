package io.github.potjerodekool.nabu.lang;

import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MemberReference;
import io.github.potjerodekool.nabu.tree.expression.builder.MemberReferenceBuilder;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.List;

public final class CompilerVisitorHelper {

    private CompilerVisitorHelper() {
    }

    public static MemberReference createMemberReference(final List<IdentifierTree> typeArguments,
                                                         final IdentifierTree identifier,
                                                         final ParserRuleContext ctx) {
        final IdentifierTree expression;
        final String name;
        final MemberReference.ReferenceKind mode;

        if (Constants.NEW.equals(identifier.getName())) {
            expression = TreeMaker.identifier(
                    Constants.INIT,
                    identifier.getLineNumber(),
                    identifier.getColumnNumber()
            );
            name = Constants.INIT;
            mode = MemberReference.ReferenceKind.NEW;
        } else {
            // In de pNNA-regel is de identifier de referentie-naam (`::<T>get`),
            // niet de omsluitende expressie: de naam moet als name en niet als
            // expression overgenomen worden, anders print de terugronde
            // `name::<...>` in plaats van `::<...>name`.
            expression = null;
            name = identifier.getName();
            mode = MemberReference.ReferenceKind.INVOKE;
        }


        return new MemberReferenceBuilder()
                .name(name)
                .typeArguments(typeArguments)
                .expression(expression)
                .mode(mode)
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .build();
    }

    public static boolean isStringLiteral(final ExpressionTree expressionTree) {
        return expressionTree instanceof LiteralExpressionTree literalExpressionTree
                && literalExpressionTree.getLiteralKind() == LiteralExpressionTree.Kind.STRING;
    }

    public static LiteralExpressionTree mergeLiterals(final LiteralExpressionTree left,
                                                      final LiteralExpressionTree right) {
        final var leftValue = left.getLiteral().toString();
        final var rightValue = right.getLiteral().toString();
        return TreeMaker.literalExpressionTree(
                leftValue + rightValue,
                left.getLineNumber(),
                left.getColumnNumber()
        );
    }

    public static VariableDeclaratorTree toLambdaVariable(final Tree tree) {
        return TreeMaker.variableDeclarator(
                Kind.PARAMETER,
                new Modifiers(),
                null,
                (IdentifierTree) tree,
                null,
                null,
                tree.getLineNumber(),
                tree.getColumnNumber()
        );
    }

    /**
     * De waarde van de tekst tussen de quotes van een string- of char-literal:
     * eerst unicode-escapes (JLS 3.3), dan de escape-sequenties van JLS 3.10.7
     * (newline, tab, octaal, ...). Ontbrak: een char-literal met een
     * escape werd de backslash zelf, en picocli's ESC-teken (CSI) kwam
     * letterlijk als escape-tekst in de uitvoer.
     */
    public static String unescapeJava(final String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            final char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length() && raw.charAt(i + 1) == 'u' && isUnicodeEscapeStart(raw, i)) {
                int j = i + 1;
                while (j < raw.length() && raw.charAt(j) == 'u') {
                    j++;
                }
                if (j + 4 <= raw.length()) {
                    sb.append((char) Integer.parseInt(raw.substring(j, j + 4), 16));
                    i = j + 4;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString().translateEscapes();
    }

    /** Een backslash begint alleen een unicode-escape als hij zelf niet ge-escaped is (oneven aantal). */
    private static boolean isUnicodeEscapeStart(final String raw, final int index) {
        int backslashes = 0;
        for (int k = index; k >= 0 && raw.charAt(k) == '\\'; k--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }
}
