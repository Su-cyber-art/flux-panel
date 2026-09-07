package com.admin.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CaptchaVerifyDtoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode fixture() throws IOException {
        try (var input = getClass().getResourceAsStream("/captcha/verify-request.json")) {
            assertNotNull(input);
            return (ObjectNode) mapper.readTree(input);
        }
    }

    @Test
    void acceptsTheExactRequestSerializedByTheFrontend() throws IOException {
        var request = mapper.treeToValue(fixture(), CaptchaVerifyDto.class);
        assertEquals("contract-fixture", request.getId());
        var track = request.getData();
        assertEquals(1710000000000L, track.getStartTime().longValue());
        assertEquals(1710000001250L, track.getStopTime().longValue());
        assertEquals(300, track.getBgImageWidth().intValue());
        assertEquals(180, track.getBgImageHeight().intValue());
        assertEquals(60, track.getTemplateImageWidth().intValue());
        assertEquals(180, track.getTemplateImageHeight().intValue());
        assertEquals(2, track.getTrackList().size());
        assertEquals(12.5f, track.getTrackList().get(1).getX());
        assertEquals(0.25f, track.getTrackList().get(1).getY());
        assertEquals(1250f, track.getTrackList().get(1).getT());
    }

    @Test
    void reproducesTheOldIsoDateFailureForBothTimeFields() throws IOException {
        for (var field : new String[]{"startTime", "stopTime"}) {
            var request = fixture();
            var track = (ObjectNode) request.get("data");
            track.put(field, Instant.ofEpochMilli(track.get(field).longValue()).toString());
            var error = assertThrows(InvalidFormatException.class,
                    () -> mapper.treeToValue(request, CaptchaVerifyDto.class));
            assertEquals(Long.class, error.getTargetType());
            assertEquals(field, error.getPath().get(error.getPath().size() - 1).getFieldName());
        }
    }
}
