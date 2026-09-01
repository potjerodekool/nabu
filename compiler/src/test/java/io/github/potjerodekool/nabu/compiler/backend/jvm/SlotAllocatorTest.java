package io.github.potjerodekool.nabu.compiler.backend.jvm;

import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.jvm.SlotAllocator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlotAllocatorTest {

    @Test
    void allocateParamReturnsSequentialSlots() {
        final var allocator = new SlotAllocator();

        final var slot0 = allocator.allocateParam("%arg0", IRType.I32);
        final var slot1 = allocator.allocateParam("%arg1", IRType.I32);
        final var slot2 = allocator.allocateParam("%arg2", IRType.I32);

        assertEquals(0, slot0);
        assertEquals(1, slot1);
        assertEquals(2, slot2);
    }

    @Test
    void allocateParamLongTakesTwoSlots() {
        final var allocator = new SlotAllocator();

        final var slot0 = allocator.allocateParam("%arg0", IRType.I64);
        final var slot1 = allocator.allocateParam("%arg1", IRType.I32);

        assertEquals(0, slot0);
        assertEquals(2, slot1);
        assertEquals(3, allocator.size());
    }

    @Test
    void allocateParamDoubleTakesTwoSlots() {
        final var allocator = new SlotAllocator();

        final var slot0 = allocator.allocateParam("%arg0", IRType.F64);
        final var slot1 = allocator.allocateParam("%arg1", IRType.I32);

        assertEquals(0, slot0);
        assertEquals(2, slot1);
        assertEquals(3, allocator.size());
    }

    @Test
    void slotOfReturnsSameSlotOnRepeatedCalls() {
        final var allocator = new SlotAllocator();

        final var first = allocator.slotOf("%temp1", IRType.I32);
        final var second = allocator.slotOf("%temp1", IRType.I32);

        assertEquals(first, second);
    }

    @Test
    void slotOfAllocatesNewSlotForNewName() {
        final var allocator = new SlotAllocator();

        final var slot0 = allocator.slotOf("%temp1", IRType.I32);
        final var slot1 = allocator.slotOf("%temp2", IRType.I32);

        assertEquals(0, slot0);
        assertEquals(1, slot1);
    }

    @Test
    void slotOfAfterAllocateParamUsesNextSlot() {
        final var allocator = new SlotAllocator();

        allocator.allocateParam("%arg0", IRType.I32);
        allocator.allocateParam("%arg1", IRType.I32);

        final var tempSlot = allocator.slotOf("%temp1", IRType.I32);

        assertEquals(2, tempSlot);
    }

    @Test
    void getSlotReturnsCorrectSlot() {
        final var allocator = new SlotAllocator();

        allocator.allocateParam("%arg0", IRType.I32);
        allocator.slotOf("%temp1", IRType.I32);

        assertEquals(0, allocator.getSlot("%arg0"));
        assertEquals(1, allocator.getSlot("%temp1"));
    }

    @Test
    void getSlotThrowsForUnallocatedName() {
        final var allocator = new SlotAllocator();

        assertThrows(IllegalStateException.class, () -> allocator.getSlot("%unknown"));
    }

    @Test
    void hasSlotReturnsTrueForAllocated() {
        final var allocator = new SlotAllocator();

        allocator.allocateParam("%arg0", IRType.I32);
        allocator.slotOf("%temp1", IRType.I32);

        assertTrue(allocator.hasSlot("%arg0"));
        assertTrue(allocator.hasSlot("%temp1"));
        assertFalse(allocator.hasSlot("%unknown"));
    }

    @Test
    void sizeAccountsForAllSlotSizes() {
        final var allocator = new SlotAllocator();

        allocator.allocateParam("%arg0", IRType.I64);  // 2 slots
        allocator.allocateParam("%arg1", IRType.I32);  // 1 slot
        allocator.slotOf("%temp1", IRType.F64);         // 2 slots

        assertEquals(5, allocator.size());
    }

    @Test
    void normalizeStripsLeadingPercent() {
        assertEquals("arg0", SlotAllocator.normalize("%arg0"));
        assertEquals("temp1", SlotAllocator.normalize("%temp1"));
        assertEquals("plain", SlotAllocator.normalize("plain"));
    }

    @Test
    void normalizeHandlesNull() {
        assertNull(SlotAllocator.normalize(null));
    }

    @Test
    void percentAndNonPercentMapToSameSlot() {
        final var allocator = new SlotAllocator();

        allocator.allocateParam("%arg0", IRType.I32);

        assertTrue(allocator.hasSlot("arg0"));
        assertEquals(0, allocator.getSlot("arg0"));
    }

    @Test
    void slotSizeForPrimitiveTypes() {
        assertEquals(1, SlotAllocator.slotSize(IRType.I8));
        assertEquals(1, SlotAllocator.slotSize(IRType.I16));
        assertEquals(1, SlotAllocator.slotSize(IRType.I32));
        assertEquals(2, SlotAllocator.slotSize(IRType.I64));
        assertEquals(1, SlotAllocator.slotSize(IRType.F32));
        assertEquals(2, SlotAllocator.slotSize(IRType.F64));
        assertEquals(1, SlotAllocator.slotSize(IRType.BOOL));
    }

    @Test
    void slotSizeForCompoundTypes() {
        assertEquals(1, SlotAllocator.slotSize(new IRType.Ptr(IRType.I32)));
        assertEquals(1, SlotAllocator.slotSize(new IRType.Array(IRType.I32, 10)));
        assertEquals(1, SlotAllocator.slotSize(new IRType.Void()));
    }

    @Test
    void mixedParamsAndTemps() {
        final var allocator = new SlotAllocator();

        // params first: this(0), arg0(1), arg1(2)
        allocator.allocateParam("%this", new IRType.Ptr(IRType.VOID));
        allocator.allocateParam("%arg0", IRType.I32);
        allocator.allocateParam("%arg1", IRType.I64);

        // temps after: temp1(4), temp2(5)
        allocator.slotOf("%temp1", IRType.F32);
        allocator.slotOf("%temp2", IRType.I64);

        assertEquals(0, allocator.getSlot("%this"));
        assertEquals(1, allocator.getSlot("%arg0"));
        assertEquals(2, allocator.getSlot("%arg1"));
        assertEquals(4, allocator.getSlot("%temp1"));
        assertEquals(5, allocator.getSlot("%temp2"));
        assertEquals(7, allocator.size());
    }
}
