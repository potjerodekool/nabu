package io.github.potjerodekool.nabu.testing;

import io.github.potjerodekool.nabu.tree.Tree;

import java.util.function.Consumer;

public class SimpleTreeWalker {

    public static void walk(final Tree root,
                            final TreeProcessor processor) {
        processTree(root, processor);
    }

    public static void walk(final Tree root,
                            final Consumer<Tree> processor) {
        walk(root, tree -> {
            processor.accept(tree);
            return true;
        });
    }

    private static boolean processTree(final Tree tree,
                                       final TreeProcessor processor) {
        if (processor.process(tree)) {
            for (final var child : tree.children()) {
                if (!processTree(child, processor)) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }
}
