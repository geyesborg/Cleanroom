package com.cleanroommc.ids;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EntityIdsTest {
    @Test
    public void countsUpUntilTheCounterWrapsThenSkipsLiveIds() {
        // Before a wrap the counter is used as is (no world lookups)
        assertEquals(41, EntityIds.next(41, id -> true));
        // Integer.MAX_VALUE + 1 overflowed: start over at 0, skipping ids live entities hold
        Set<Integer> live = Set.of(0, 1, 2, 5);
        int id = EntityIds.next(Integer.MIN_VALUE, live::contains);
        assertEquals(3, id);
        assertEquals(4, EntityIds.next(id + 1, live::contains));
        assertEquals(6, EntityIds.next(5, live::contains));
    }
}
