package com.cleanroommc.test.ids;

import com.cleanroommc.ids.WideWindowProperty;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketWindowProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WideWindowPropertyTest {
    private static int throughPacket(int value) throws IOException {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        new SPacketWindowProperty(1, 0, value).writePacketData(buf);
        SPacketWindowProperty read = new SPacketWindowProperty();
        read.readPacketData(buf);
        return read.getValue();
    }

    @Test
    public void idsSurviveTheShortPacketInBothHalves() throws IOException {
        for (int id : new int[] { -1, 0, 1, 32767, 32768, 40000, 65535, 65536, 70000, 1 << 24, Integer.MAX_VALUE }) {
            int high = throughPacket(WideWindowProperty.high(id));
            int low = throughPacket(WideWindowProperty.low(id));
            assertEquals(id, WideWindowProperty.combine(high, low), "id " + id);
        }
    }

    @Test
    public void lowHalfAloneKeepsTheVanillaMeaning() throws IOException {
        for (int id : new int[] { -1, 0, 1, 200, 32767 }) {
            assertEquals(id, WideWindowProperty.combine(Integer.MIN_VALUE, throughPacket(id)), "id " + id);
        }
    }

    @Test
    public void idsThatFitKeepTheirVanillaPropertyValue() {
        for (int id = 0; id < 32768; id += 97) {
            assertEquals(id, WideWindowProperty.low(id));
            assertEquals(0, WideWindowProperty.high(id));
        }
    }
}
