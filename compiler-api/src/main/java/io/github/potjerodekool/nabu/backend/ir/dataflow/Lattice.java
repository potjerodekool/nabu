package io.github.potjerodekool.nabu.backend.ir.dataflow;

/**
 * Een lattice voor dataflow-waarden.
 *
 * @param <V> het type van de dataflow-waarde
 */
public interface Lattice<V> {

    /** Ondergrens (bottom): lege verzameling / onbereikbaar. */
    V bottom();

    /** Bovengrens (top): alle mogelijke waarden. */
    V top();

    /**
     * Combineer twee waarden tot een enkele (join voor "may"-analyses,
     * meet voor "must"-analyses; de analyse kiest de juiste via
     * {@link DataFlowAnalysis#meet}).
     */
    V combine(V a, V b);

    /** Controleert gelijkheid van twee lattice-waarden. */
    boolean equalsValue(V a, V b);
}