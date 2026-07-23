package com.mentra.bluetoothsdk.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MessageChunkerTest {

    private static String cWrapped(String payload, boolean wakeup) throws JSONException {
        JSONObject wrapper = new JSONObject();
        wrapper.put("C", payload);
        if (wakeup) {
            wrapper.put("W", 1);
        }
        return wrapper.toString();
    }

    private static int packedLength(JSONObject chunk, boolean wakeup) {
        byte[] packed = K900ProtocolUtils.packJsonToK900(chunk.toString(), wakeup);
        return packed == null ? Integer.MAX_VALUE : packed.length;
    }

    @Test
    public void cosmeticLedCommand_afterDroppingMidAndPackageName_isSentAsSingleUnchunkedWrite()
            throws JSONException {
        // Post-fix LED command shape: no mId (fire-and-forget) and no packageName.
        // Its C-wrapped form must stay under the 200-byte chunking threshold so a
        // cosmetic blink costs exactly one BLE write (no chunker, no ACK gate).
        String ledOn =
                new JSONObject()
                        .put("requestId", "550e8400-e29b-41d4-a716-446655440000")
                        .put("type", "rgb_led_control_on")
                        .put("led", 0)
                        .put("ontime", 500)
                        .put("offtime", 500)
                        .put("count", 3)
                        .toString();

        assertThat(MessageChunker.needsChunking(cWrapped(ledOn, true))).isFalse();
    }

    @Test
    public void chunkedCommand_usesRaisedSliceSize_producingFewerChunksEachWithinBes2700Limit()
            throws JSONException {
        // The pre-fix LED command shape (mId + packageName present, ~173 bytes) still
        // exceeds the threshold and is chunked. With the raised 200-byte initial slice
        // the search settles on a ~116-byte slice -> 2 chunks. The old 80-byte cap forced
        // ceil(173/80) = 3 chunks. Every packed chunk must fit the 253-byte BES2700 limit.
        String payload =
                new JSONObject()
                        .put("requestId", "550e8400-e29b-41d4-a716-446655440000")
                        .put("packageName", "com.dimenso.app")
                        .put("type", "rgb_led_control_on")
                        .put("led", 0)
                        .put("ontime", 500)
                        .put("offtime", 500)
                        .put("count", 3)
                        .put("mId", 123456789L)
                        .toString();

        assertThat(MessageChunker.needsChunking(cWrapped(payload, true))).isTrue();

        List<JSONObject> chunks = MessageChunker.createChunks(payload, 123456789L, true);

        assertThat(chunks).hasSize(2);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(packedLength(chunks.get(i), i == 0)).isLessThanOrEqualTo(253);
        }
    }
}
