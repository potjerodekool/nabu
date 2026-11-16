package io.github.potjerodekool.nabu.backend.ir.dataflow;

/**
 * Richting van een dataflow-analyse.
 */
public enum DataFlowDirection {
    /** Informatie stroomt van entry naar exit (voorwaarts). */
    FORWARD,
    /** Informatie stroomt van exit naar entry (achterwaarts). */
    BACKWARD
}