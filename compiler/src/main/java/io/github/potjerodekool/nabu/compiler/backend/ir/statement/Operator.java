package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import java.util.stream.Stream;

public enum Operator {

    PLUS("+"),
    PLUS_PLUS("++"),
    MIN("-"),
    MIN_MIN("--"),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">="),
    ASSIGN("="),
    PLUS_ASSIGN("+="),
    EQUALS("=="),
    NOT_EQUALS("!="),
    ULT("ULT"),
    ULE("ULE"),
    UGT("UGT"),
    UGE("UGE"),
    OR("||"),
    AND("AND"),
    BI_OR("|"),
    BE_OR("^"),
    B_AND("&"),
    NOT("!"),
    MUL("*"),
    DIV("/"),
    REM("%"),
    INCLUSIVE_OR("|"),
    DSHIFT_LEFT("<<"),
    DSHIFT_RIGHT(">>"),
    TSHIFT_RIGHT(">>>"),
    UNARY_BITWISE_COMPLEMENT("~"),
    OR_ASSIGN("|="),
    MUL_ASSIGN("*="),
    DIV_ASSIGN("/=");

    private final String value;

    Operator(final String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Operator parse(final String s) {
        return Stream.of(values())
                .filter(oper -> oper.value.equals(s))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException("operateor:" + s));
    }

    @Override
    public String toString() {
        return value;
    }

}
