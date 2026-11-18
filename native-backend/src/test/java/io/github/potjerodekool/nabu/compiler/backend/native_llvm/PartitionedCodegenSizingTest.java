package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How many partitions are generated at the same time follows from the memory of the machine and the size of the program. */
class PartitionedCodegenSizingTest {

    private static final long GB = 1L << 30;

    @Test
    void thePetstoreOnA32GbMachineGetsFourThreads() {
        assertEquals(4, PartitionedCodegen.threadsForMemory(32 * GB, 200_000));
    }

    @Test
    void thePetstoreOnA16GbMachineGetsTwoThreads() {
        assertEquals(2, PartitionedCodegen.threadsForMemory(16 * GB, 200_000));
    }

    @Test
    void aSmallProgramGetsAsManyThreadsAsThereArePartitions() {
        assertTrue(PartitionedCodegen.threadsForMemory(16 * GB, 20_000) >= 8);
    }

    @Test
    void thereIsAlwaysOneThreadAndAnUnknownMemoryTwo() {
        assertEquals(1, PartitionedCodegen.threadsForMemory(4 * GB, 2_000_000));
        assertEquals(2, PartitionedCodegen.threadsForMemory(0, 200_000));
    }

    @Test
    void halfTheThreadsSplitAtTheSameTimeAtMostTwo() {
        assertEquals(1, PartitionedCodegen.splitThreads(1));
        assertEquals(1, PartitionedCodegen.splitThreads(2));
        assertEquals(2, PartitionedCodegen.splitThreads(4));
        assertEquals(2, PartitionedCodegen.splitThreads(16));
    }

    @Test
    void theNumberOfPartitionsFollowsTheProcessorsAndStaysBetweenTwoAndEight() {
        final int p = PartitionedCodegen.partitionCount(100_000);
        assertTrue(p >= 2 && p <= 8, "partitions: " + p);
        assertEquals(1, PartitionedCodegen.partitionCount(10));
    }
}
