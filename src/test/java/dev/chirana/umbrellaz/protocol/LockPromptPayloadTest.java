package dev.chirana.umbrellaz.protocol;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LockPromptPayloadTest {
    private static final UUID TOKEN = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private static final UUID RETRY_TOKEN = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");

    @Test
    void roundTripsAllPayloads() {
        assertEquals(new LockPromptOpenPayload(TOKEN, LockPromptKind.CONFIRM),
                roundTrip(LockPromptOpenPayload.CODEC, new LockPromptOpenPayload(TOKEN, LockPromptKind.CONFIRM)));
        assertEquals(new LockPromptSubmitPayload(TOKEN, "A123"),
                roundTrip(LockPromptSubmitPayload.CODEC, new LockPromptSubmitPayload(TOKEN, "A123")));
        assertEquals(new LockPromptCancelPayload(TOKEN),
                roundTrip(LockPromptCancelPayload.CODEC, new LockPromptCancelPayload(TOKEN)));
        LockPromptResultPayload result = new LockPromptResultPayload(TOKEN,
                LockPromptResultCode.COOLDOWN, 30, "Tente novamente.", RETRY_TOKEN);
        assertEquals(result, roundTrip(LockPromptResultPayload.CODEC, result));
        LockPromptResultPayload error = new LockPromptResultPayload(TOKEN,
                LockPromptResultCode.ERROR, 0, "Falha.", RETRY_TOKEN);
        assertEquals(error, roundTrip(LockPromptResultPayload.CODEC, error));
        LockPromptResultPayload success = new LockPromptResultPayload(TOKEN,
                LockPromptResultCode.SUCCESS, 0, "Concluido.");
        assertEquals(success, roundTrip(LockPromptResultPayload.CODEC, success));
    }

    @Test
    void validatesNullEmptyAndOverlongFields() {
        assertThrows(NullPointerException.class, () -> new LockPromptOpenPayload(null, LockPromptKind.CREATE));
        assertThrows(NullPointerException.class, () -> new LockPromptOpenPayload(TOKEN, null));
        assertThrows(NullPointerException.class, () -> new LockPromptSubmitPayload(TOKEN, null));
        assertEquals("", new LockPromptSubmitPayload(TOKEN, "").password());
        assertThrows(IllegalArgumentException.class, () -> new LockPromptSubmitPayload(TOKEN, "ABCDE"));
         assertThrows(NullPointerException.class,
                 () -> new LockPromptResultPayload(TOKEN, LockPromptResultCode.ERROR, 0, null));
         assertEquals("", new LockPromptResultPayload(TOKEN, LockPromptResultCode.SUCCESS, 0, "").message());
        assertThrows(IllegalArgumentException.class,
                () -> new LockPromptResultPayload(TOKEN, LockPromptResultCode.ERROR, 0, "x".repeat(97)));
         assertThrows(IllegalArgumentException.class,
                 () -> new LockPromptResultPayload(TOKEN, LockPromptResultCode.COOLDOWN, 31, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LockPromptResultPayload(TOKEN, LockPromptResultCode.ERROR, 0, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new LockPromptResultPayload(TOKEN, LockPromptResultCode.ERROR, 0, "", TOKEN));
        assertThrows(IllegalArgumentException.class,
                () -> new LockPromptResultPayload(TOKEN, LockPromptResultCode.COOLDOWN, 0, "", RETRY_TOKEN));
        assertThrows(IllegalArgumentException.class,
                () -> new LockPromptResultPayload(TOKEN, LockPromptResultCode.SUCCESS, 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LockPromptResultPayload(TOKEN, LockPromptResultCode.SUCCESS, 0, "", RETRY_TOKEN));
    }

    @Test
    void rejectsUnknownEnumsAndMalformedBounds() {
        RegistryFriendlyByteBuf open = buffer();
        open.writeUUID(TOKEN).writeVarInt(2);
        assertThrows(IllegalArgumentException.class, () -> LockPromptOpenPayload.CODEC.decode(open));

        RegistryFriendlyByteBuf result = buffer();
        result.writeUUID(TOKEN).writeVarInt(4);
        assertThrows(IllegalArgumentException.class, () -> LockPromptResultPayload.CODEC.decode(result));

        RegistryFriendlyByteBuf cooldown = buffer();
        cooldown.writeUUID(TOKEN).writeVarInt(LockPromptResultCode.COOLDOWN.ordinal());
        cooldown.writeVarInt(31);
        assertThrows(IllegalArgumentException.class, () -> LockPromptResultPayload.CODEC.decode(cooldown));

        RegistryFriendlyByteBuf missingRetry = buffer();
        missingRetry.writeUUID(TOKEN).writeVarInt(LockPromptResultCode.ERROR.ordinal());
        missingRetry.writeVarInt(0).writeUtf("").writeBoolean(false);
        assertThrows(IllegalArgumentException.class, () -> LockPromptResultPayload.CODEC.decode(missingRetry));

        RegistryFriendlyByteBuf sameRetry = buffer();
        sameRetry.writeUUID(TOKEN).writeVarInt(LockPromptResultCode.ERROR.ordinal());
        sameRetry.writeVarInt(0).writeUtf("").writeBoolean(true).writeUUID(TOKEN);
        assertThrows(IllegalArgumentException.class, () -> LockPromptResultPayload.CODEC.decode(sameRetry));

        RegistryFriendlyByteBuf invalidMarker = buffer();
        invalidMarker.writeUUID(TOKEN).writeVarInt(LockPromptResultCode.SUCCESS.ordinal());
        invalidMarker.writeVarInt(0).writeUtf("").writeByte(2);
        assertThrows(IllegalArgumentException.class, () -> LockPromptResultPayload.CODEC.decode(invalidMarker));

        RegistryFriendlyByteBuf invalidSuccess = buffer();
        invalidSuccess.writeUUID(TOKEN).writeVarInt(LockPromptResultCode.SUCCESS.ordinal());
        invalidSuccess.writeVarInt(1).writeUtf("").writeBoolean(false);
        assertThrows(IllegalArgumentException.class, () -> LockPromptResultPayload.CODEC.decode(invalidSuccess));

        RegistryFriendlyByteBuf password = buffer();
        password.writeUUID(TOKEN).writeUtf("ABCDE");
        assertThrows(RuntimeException.class, () -> LockPromptSubmitPayload.CODEC.decode(password));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buffer = buffer();
        codec.encode(buffer, value);
        return codec.decode(buffer);
    }
}
