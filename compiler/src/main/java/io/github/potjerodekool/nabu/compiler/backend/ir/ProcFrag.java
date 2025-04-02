package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;

import java.util.List;

public class ProcFrag {

    private final List<IStatement> body;

    public ProcFrag(final IStatement body) {
        this(List.of(body));
    }

    public ProcFrag(final List<IStatement> body) {
        this.body = body;
    }

    public List<IStatement> getBody() {
        return body;
    }
}
