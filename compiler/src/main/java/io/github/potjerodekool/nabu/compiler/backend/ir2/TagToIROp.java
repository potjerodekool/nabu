package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.ir.instructions.IRInstruction.BinaryOp.Op;
import io.github.potjerodekool.nabu.tree.Tag;

/**
 * Vertaalt een Nabu Tag (operator) naar een IR BinaryOp.Op.
 *
 * Compound assignments (+=, -=, etc.) worden door de visitor zelf
 * afgehandeld als load + op + store — ze verschijnen hier niet als
 * aparte IR-operaties.
 */
public final class TagToIROp {

    private TagToIROp() {}

    /**
     * Mapt een binaire operator-tag naar een IR-operatie.
     *
     * @throws UnsupportedOperationException voor operators die geen
     *         directe IR-operatie hebben (compound assignments,
     *         post-increment, etc.).
     */
    public static Op map(Tag tag) {
        return switch (tag) {
            // Rekenkundig
            case ADD -> Op.ADD;
            case SUB -> Op.SUB;

            // Vergelijkingen
            case EQ  -> Op.EQ;
            case NE  -> Op.NEQ;
            case LT  -> Op.LT;
            case LE  -> Op.LTE;
            case GT  -> Op.GT;
            case GE  -> Op.GTE;

            // Logisch (bit-level in IR — voor booleans identiek)
            case AND -> Op.AND;
            case OR  -> Op.OR;

            // Compound assignments en unaire operators worden
            // door de visitor uitgevouwen, niet hier gemapt
            case ASSIGN,
                 ADD_ASSIGN, MUL_ASSIGN, DIV_ASSIGN,
                 AND_ASSIGN, OR_ASSIGN, XOR_ASSIGN,
                 MOD_ASSIGN, LSHIFT_ASSIGN, RSHIFT_ASSIGN, URSHIFT_ASSIGN,
                 POST_INC, POST_DEC, NOT ->
                    throw new UnsupportedOperationException(
                            "Tag " + tag + " is geen directe binaire IR-operatie — "
                                    + "uitvouwen in de visitor");
        };
    }

    /**
     * Geeft true als de tag een vergelijkings-operator is.
     * Vergelijkingen produceren een i1 (boolean) resultaat in IR.
     */
    public static boolean isComparison(Tag tag) {
        return switch (tag) {
            case EQ, NE, LT, LE, GT, GE -> true;
            default -> false;
        };
    }

    /**
     * Geeft true als de tag een compound assignment is
     * (bijv. +=, -=) die door de visitor uitgevouwen moet worden.
     */
    public static boolean isCompoundAssignment(Tag tag) {
        return switch (tag) {
            case ADD_ASSIGN, MUL_ASSIGN, DIV_ASSIGN,
                 AND_ASSIGN, OR_ASSIGN, XOR_ASSIGN,
                 MOD_ASSIGN, LSHIFT_ASSIGN, RSHIFT_ASSIGN, URSHIFT_ASSIGN ->
                    true;
            default -> false;
        };
    }

    /**
     * Geeft de basis-operator terug voor een compound assignment.
     * Bijv. ADD_ASSIGN → ADD, AND_ASSIGN → AND.
     */
    public static Op compoundAssignmentOp(Tag tag) {
        return switch (tag) {
            case ADD_ASSIGN    -> Op.ADD;
            case AND_ASSIGN    -> Op.AND;
            case OR_ASSIGN     -> Op.OR;
            case XOR_ASSIGN    -> Op.XOR;
            case MOD_ASSIGN    -> Op.MOD;
            default -> throw new UnsupportedOperationException(
                    "Geen basis-operatie voor: " + tag);
        };
    }
}
