package com.cleanroommc.ids;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SectionBlockFormatTest {
    @Test
    public void bitsPerIndex() {
        assertEquals(4, SectionBlockFormat.bitsFor(2));
        assertEquals(4, SectionBlockFormat.bitsFor(16));
        assertEquals(5, SectionBlockFormat.bitsFor(17));
        assertEquals(12, SectionBlockFormat.bitsFor(4096));
        assertEquals(13, SectionBlockFormat.bitsFor(4097));
    }

    @Test
    public void longsDontSplitIndices() {
        assertEquals(256, SectionBlockFormat.longCount(4));   // 16 per long
        assertEquals(342, SectionBlockFormat.longCount(5));   // 12 per long, 4 bits unused
        assertEquals(456, SectionBlockFormat.longCount(7));   // 9 per long
        assertEquals(1024, SectionBlockFormat.longCount(16)); // 4 per long
    }

    @Test
    public void packRoundTripsEveryWidth() {
        Random random = new Random(42);
        for (int bits = 4; bits <= 20; bits++) {
            int[] indices = new int[4096];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = random.nextInt(1 << bits);
            }
            indices[0] = (1 << bits) - 1; // the largest value, every bit set
            long[] packed = SectionBlockFormat.pack(indices, bits);
            assertEquals(SectionBlockFormat.longCount(bits), packed.length);
            assertArrayEquals(indices, SectionBlockFormat.unpack(packed, bits), "bits " + bits);
        }
    }
}
