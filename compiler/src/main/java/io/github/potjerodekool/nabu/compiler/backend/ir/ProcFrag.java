package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;
import java.util.Objects;

public class ProcFrag {

    private final int flags;
    private final String name;
    private final List<Param> params;
    private final IType returnType;
    private final Frame frame;
    private final List<IStatement> body;

    public ProcFrag(final int flags,
                    final String name,
                    final List<Param> params,
                    final IType returnType,
                    final Frame frame,
                    final IStatement body) {
        this(flags, name, params, returnType, frame, List.of(body));
    }

    public ProcFrag(final int flags,
                    final String name,
                    final List<Param> params,
                    final IType returnType,
                    final Frame frame,
                    final List<IStatement> body) {
        Objects.requireNonNull(name);
        this.flags = flags;
        this.name = name;
        this.params = params;
        this.returnType = returnType;
        this.frame = frame;
        this.body = body;
    }

    public int getFlags() {
        return flags;
    }

    public String getName() {
        return name;
    }

    public List<Param> getParams() {
        return params;
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
