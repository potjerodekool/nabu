package java.lang;

import jdk.internal.vm.annotation.IntrinsicCandidate;

public class Object {

    public Object() {
    }

    @IntrinsicCandidate
    protected native Object clone() throws CloneNotSupportedException;
}