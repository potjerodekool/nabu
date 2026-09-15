package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.TypeApplyTree;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of TypeApplyTree.
 */
public class CTypeApplyTree extends CExpressionTree implements TypeApplyTree {

    private final ExpressionTree clazz;

    private final List<Tree> typeParameters = new ArrayList<>();

    public CTypeApplyTree(final ExpressionTree clazz,
                          final List<? extends Tree> typeParameters,
                          final int lineNumber,
                          final int columnNumber) {
        super(lineNumber, columnNumber);
        this.clazz = clazz;
        this.typeParameters.addAll(typeParameters);
    }

    @Override
    public ExpressionTree getClazz() {
        return clazz;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeIdentifier(this, param);
    }

    public List<Tree> getTypeParameters() {
        return typeParameters;
    }

    @Override
    public void setType(final TypeMirror type) {
        final var clazzText = getClazz().toString();
        if (type != null && type.isError()
                && (clazzText.contains("AbstractHandler")
                || clazzText.contains("IParseResultHandler2")
                || clazzText.contains("IExceptionHandler2"))) {
            try (final var pw = new java.io.PrintWriter(
                    new java.io.FileWriter("C:/Users/evert/AppData/Local/Temp/opencode/diag.log", true))) {
                pw.println("[ERR-WRITER] tree@" + System.identityHashCode(this)
                        + " line=" + getLineNumber()
                        + " col=" + getColumnNumber()
                        + " clazz=" + clazzText
                        + " newType=" + (type.getClassName() == null ? type : type.getClassName()));
                pw.println("    typeClass=" + type.getClass().getName()
                        + " isErrorType=" + (type instanceof io.github.potjerodekool.nabu.type.ErrorType)
                        + " elemClass=" + (type.asTypeElement() == null ? "null" : type.asTypeElement().getClass().getName())
                        + " elem@=" + System.identityHashCode(type.asTypeElement())
                        + " elemIsError=" + (type.asTypeElement() == null ? "null" : type.asTypeElement().isError()));
                java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                        .limit(18)
                        .forEach(frame -> pw.println("    at " + frame));
            } catch (java.io.IOException e) {
                // ignore
            }
        }
        super.setType(type);
    }

    @Override
    public String toString() {
        return clazz.toString();
    }
}
