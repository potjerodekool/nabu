package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;

/**
 * Bepaalt de soort methode-aanroep op basis van het Element dat
 * na semantic analysis aan de selector van een MethodInvocationTree hangt.
 *
 * Aanroepsoorten:
 *   STATIC     — statische methode, directe aanroep
 *   VIRTUAL    — instantie-methode op een klasse
 *   INTERFACE  — methode gedefinieerd op een interface (invokevirtual / invokeinterface)
 *   SPECIAL    — constructor (<init>) of super-aanroep
 */
public final class CallKindResolver {

    private CallKindResolver() {}

    public enum CallKind {
        STATIC,
        VIRTUAL,
        INTERFACE,
        SPECIAL   // constructor of super
    }

    /**
     * Bepaalt de CallKind voor een MethodInvocationTree.
     *
     * @param invocation De methode-aanroep.
     * @return De CallKind.
     */
    public static CallKind resolve(MethodInvocationTree invocation) {
        ExpressionTree selector = invocation.getMethodSelector();
        Element symbol = extractSymbol(selector);

        if (symbol == null) {
            // Geen symbool — behandel als virtual (veilige fallback)
            return CallKind.VIRTUAL;
        }

        ElementKind kind = symbol.getKind();

        // Constructor
        if (kind == ElementKind.CONSTRUCTOR) {
            return CallKind.SPECIAL;
        }

        // Statische methode
        if (symbol.isStatic()) {
            return CallKind.STATIC;
        }

        // Instantie-methode — check of de eigenaar een interface is
        Element enclosing = symbol.getEnclosingElement();
        if (enclosing instanceof TypeElement typeElement) {
            if (typeElement.getKind().isInterface()) {
                return CallKind.INTERFACE;
            }
        }

        return CallKind.VIRTUAL;
    }

    /**
     * Geeft het ExecutableElement terug dat aan de aanroep hangt,
     * of null als dat niet beschikbaar is.
     */
    public static ExecutableElement resolveExecutable(MethodInvocationTree invocation) {
        // Het methodType bevat de methode-informatie na semantic analysis
        if (invocation.getMethodType() != null) {
            return invocation.getMethodType().getMethodSymbol();
        }

        ExpressionTree selector = invocation.getMethodSelector();
        Element symbol = extractSymbol(selector);

        if (symbol instanceof ExecutableElement exec) {
            return exec;
        }
        return null;
    }

    /**
     * Geeft de methodenaam terug voor gebruik in de IR-aanroep.
     */
    public static String resolveName(MethodInvocationTree invocation) {
        ExpressionTree selector = invocation.getMethodSelector();

        if (selector instanceof FieldAccessExpressionTree fieldAccess) {
            return fieldAccess.getField().getName();
        }
        if (selector instanceof IdentifierTree identifier) {
            return identifier.getName();
        }
        return "<unknown>";
    }

    /**
     * Geeft de geselecteerde expressie terug (het object waar de methode
     * op wordt aangeroepen), of null voor statische methoden.
     */
    public static ExpressionTree resolveTarget(MethodInvocationTree invocation) {
        ExpressionTree selector = invocation.getMethodSelector();

        if (selector instanceof FieldAccessExpressionTree fieldAccess) {
            return fieldAccess.getSelected();
        }
        // IdentifierTree zonder selector = this (impliciet) of statisch
        return null;
    }

    /**
     * Geeft true als de aanroep een super-aanroep is.
     * Super-aanroepen worden als SPECIAL behandeld.
     */
    public static boolean isSuper(MethodInvocationTree invocation) {
        ExpressionTree selector = invocation.getMethodSelector();

        if (selector instanceof FieldAccessExpressionTree fieldAccess) {
            ExpressionTree selected = fieldAccess.getSelected();
            if (selected instanceof IdentifierTree id) {
                return "super".equals(id.getName());
            }
        }
        if (selector instanceof IdentifierTree id) {
            return "super".equals(id.getName());
        }
        return false;
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    private static Element extractSymbol(ExpressionTree expr) {
        if (expr instanceof FieldAccessExpressionTree fieldAccess) {
            // Het symbool zit op het veld-identifier
            IdentifierTree field = fieldAccess.getField();
            if (field instanceof io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree cId) {
                return cId.getSymbol();
            }
        }
        if (expr instanceof io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree cId) {
            return cId.getSymbol();
        }
        return null;
    }
}
