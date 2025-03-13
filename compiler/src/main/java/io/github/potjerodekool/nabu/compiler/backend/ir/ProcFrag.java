package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;

public class ProcFrag {

    private final long flags;
    private final String name;
    private final IType returnType;
    private final Frame frame;
    private final List<IStatement> body;

    public ProcFrag(final long flags,
                    final String name,
                    final IType returnType,
                    final Frame frame,
                    final IStatement body) {
        this(flags, name, returnType, frame, List.of(body));
    }

    public ProcFrag(final long flags,
                    final String name,
                    final IType returnType,
                    final Frame frame,
                    final List<IStatement> body) {
        this.flags = flags;
        this.name = name;
        this.returnType = returnType;
        this.frame = frame;
        this.body = body;
    }

    public long getFlags() {
        return flags;
    }

    public String getName() {
        return name;
    }

    public IType getReturnType() {
        return returnType;
    }

    public Frame getFrame() {
        return frame;
    }

    public List<IStatement> getBody() {
        return body;
    }
}
