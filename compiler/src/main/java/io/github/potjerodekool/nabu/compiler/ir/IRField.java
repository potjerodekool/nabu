package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.model.element.CompoundAttribute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IRField {

    private final Kind kind;
    private final long flags;
    private final String name;
    private final IRType type;
    private final IRValue value;
    private final List<CompoundAttribute> annotations = new ArrayList<>();
    private String genericSignature;

    private IRField(Kind kind, long flags, String name, IRType type, IRValue value) {
        this.kind = kind;
        this.flags = flags;
        this.name = name;
        this.type = type;
        this.value = value;
    }

    public Kind kind() { return kind; }
    public long flags() { return flags; }
    public String name() { return name; }
    public IRType type() { return type; }
    public IRValue value() { return value; }

    public void setAnnotations(List<CompoundAttribute> annotations) {
        this.annotations.addAll(annotations);
    }

    public List<CompoundAttribute> annotations() {
        return Collections.unmodifiableList(annotations);
    }

    public void setGenericSignature(String genericSignature) {
        this.genericSignature = genericSignature;
    }

    public String genericSignature() {
        return genericSignature;
    }

    public static IRField recordComponent(String name,
                                          IRType type) {
        return new IRField(Kind.RECORD_COMPONENT, 0, name, type, null);
    }

    public static IRField field(long flags,
                                String name,
                                IRType type,
                                IRValue value) {
        return new IRField(Kind.FIELD, flags, name, type, value);
    }

    public enum Kind {
        FIELD,
        RECORD_COMPONENT
    }
}
