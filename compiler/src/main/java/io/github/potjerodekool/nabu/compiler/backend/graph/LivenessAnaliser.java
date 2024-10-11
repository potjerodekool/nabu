package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.*;
import java.util.stream.Collectors;

public class LivenessAnaliser {

    private final FlowGraph<?> flowGraph;
    private final Map<Integer, List<Temp>> in;
    private final Map<Integer, List<Temp>> out;
    private Map<Integer, List<Temp>> inPrime;
    private Map<Integer, List<Temp>> outPrime;

    public LivenessAnaliser(final FlowGraph<?> flowGraph) {
        in = new HashMap<>();
        out = new HashMap<>();

        flowGraph.getNodes().forEach(node -> {
            input(node, null);
            output(node, null);
        });

        this.flowGraph = flowGraph;
        liveness();
    }

    private void input(final Node node,
                       final List<Temp> value) {
        in.put(node.getKey(), value);
    }

    private void output(final Node node,
                        final List<Temp> value) {
        out.put(node.getKey(), value);
    }

    FlowGraph<?> getFlowGraph() {
        return flowGraph;
    }

    public Map<Integer, List<Temp>> getIn() {
        return in;
    }

    public Map<Integer, List<Temp>> getOut() {
        return out;
    }

    private void liveness() {
        inPrime = new HashMap<>();
        outPrime = new HashMap<>();
        do {
            copyIn();
            copyOut();
            this.flowGraph.getNodes().forEach(node -> {
                updateIn(node);
                updateOut(node);
            });
        } while (!fixPoint(inPrime, outPrime));
    }

    private void copyIn() {
        inPrime = new HashMap<>(in);
    }

    private void copyOut() {
        outPrime = new HashMap<>(out);
    }

    private void updateOut(final Node node) {
        final List<Temp> list = node.succ().stream()
                .map(succNode -> nonNull(this.in.get(succNode.getKey())))
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
        output(node, list);
    }

    private List<Temp> nonNull(final List<Temp> list) {
        return Objects.requireNonNullElseGet(list, List::of);
    }

    private void updateIn(final Node node) {
        final List<Temp> list;
        final Temp[] temps = this.flowGraph.use(node);
        final List<Temp> outTemps = this.out.get(node.getKey());

        if (temps != null) {
            list = Arrays.asList(temps);
        } else {
            list = new ArrayList<>();
        }

        if (outTemps != null) {
            outTemps.forEach(outTemp -> {
                if ((this.flowGraph.def(node) == null || !contains(this.flowGraph.def(node), outTemp)) &&
                    (list.isEmpty() || !list.contains(outTemp))) {
                    list.add(outTemp);
                }
            });
        }
        input(node, list);
    }

    private boolean contains(final Temp[] array, final Temp temp) {
        for (final Temp t : array) {
            if (t.getIndex() == temp.getIndex()) {
                return true;
            }
        }
        return false;
    }

    private boolean fixPoint(final Map<Integer, List<Temp>> inPrime, final Map<Integer, List<Temp>> outPrime) {
        for (final Node node : flowGraph.getNodes()) {
            if (out.get(node.getKey()) == null && outPrime.get(node.getKey()) != null) {
                if (outPrime.get(node.getKey()) != null) {
                    return false;
                }
            } else if (!out.get(node.getKey()).equals(outPrime.get(node.getKey()))) {
                return false;
            }
        }

        for (final Node node : flowGraph.getNodes()) {
            if (in.get(node.getKey()) == null && inPrime.get(node.getKey()) != null) {
                if (inPrime.get(node.getKey()) != null) {
                    return false;
                }
            } else if (!in.get(node.getKey()).equals(inPrime.get(node.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
