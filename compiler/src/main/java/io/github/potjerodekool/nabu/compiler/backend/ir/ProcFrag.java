package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;

import java.util.List;

public class ProcFrag {

    private final List<IStatement> body;
    private final Frame frame;

    public ProcFrag(final IStatement body,
                    final Frame frame) {
        this(List.of(body), frame);
    }

    public ProcFrag(final List<IStatement> body,
                    final Frame frame) {
        this.body = body;
        this.frame = frame;
    }

    public List<IStatement> getBody() {
        return body;
    }

    public Frame getFrame() {
        return frame;
    }
}
