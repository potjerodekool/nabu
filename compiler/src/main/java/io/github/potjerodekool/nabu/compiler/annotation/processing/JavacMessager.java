package io.github.potjerodekool.nabu.compiler.annotation.processing;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.JElement;
import io.github.potjerodekool.nabu.tools.diagnostic.DefaultDiagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.util.Elements;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

public class JavacMessager implements Messager {

    private final DiagnosticListener listener;
    private final Elements elements;

    public JavacMessager(final DiagnosticListener listener,
                         final Elements elements) {
        this.listener = listener;
        this.elements = elements;
    }

    @Override
    public void printMessage(final Diagnostic.Kind kind,
                             final CharSequence msg) {
        printMessage(kind, msg, null, null, null);
    }

    @Override
    public void printMessage(final Diagnostic.Kind kind,
                             final CharSequence msg,
                             final Element e) {
        printMessage(kind, msg, e, null, null);
    }

    @Override
    public void printMessage(final Diagnostic.Kind kind,
                             final CharSequence msg,
                             final Element e,
                             final AnnotationMirror a) {
        printMessage(kind, msg, e, a, null);
    }

    @Override
    public void printMessage(final Diagnostic.Kind kind,
                             final CharSequence msg,
                             final Element e,
                             final AnnotationMirror a,
                             final AnnotationValue v) {
        listener.report(
                createDiagnostic(
                        kind,
                        msg,
                        e
                )
        );
    }

    private DefaultDiagnostic createDiagnostic(final Diagnostic.Kind kind,
                                               final CharSequence message,
                                               final Element e) {
        final var fileObject = e != null
                ? elements.getFileObjectOf(extractSymbol(e))
                : null;
        return new DefaultDiagnostic(
                convertKind(kind),
                message,
                fileObject
        );
    }

    private io.github.potjerodekool.nabu.lang.model.element.Element extractSymbol(final Element element) {
        if (element instanceof JElement<?> symbol) {
            return symbol.getOriginal();
        } else {
            return null;
        }
    }

    private io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic.Kind convertKind(final Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic.Kind.ERROR;
            case WARNING -> io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic.Kind.WARN;
            case MANDATORY_WARNING -> io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic.Kind.MANDATORY_WARNING;
            case NOTE -> io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic.Kind.NOTE;
            case OTHER -> io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic.Kind.OTHER;
        };
    }
}
