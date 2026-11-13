package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.order.DeliveryAddressRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryAddressServiceTest {
    private final GogodukService upstream = mock(GogodukService.class);
    private final DeliveryAddressService service = new DeliveryAddressService(upstream);

    private DeliveryAddressRequest request() {
        return new DeliveryAddressRequest("Client address", "place-1", new BigDecimal("1"), new BigDecimal("2"), "session");
    }

    @Test void usesServerCoordinatesAndAddressInsteadOfClientValues() {
        respond("{\"result\":{\"address\":\"Ngõ 12, Hà Nội\",\"lat\":21.0288654,\"lon\":105.7819884,\"placeId\":\"resolved-id\"}}");
        var result = service.resolve(request());
        assertEquals("Ngõ 12, Hà Nội", result.formattedAddress());
        assertEquals("resolved-id", result.placeId());
        assertEquals(new BigDecimal("21.0288654"), result.latitude());
        assertEquals(new BigDecimal("105.7819884"), result.longitude());
        assertNull(result.city());
        verify(upstream).resolve("place-1", "vi", "session");
    }

    @Test void allowsNumericStringsAndMissingResponseId() {
        respond("{\"result\":{\"address\":\"Hà Nội\",\"lat\":\"21.12345678\",\"lon\":\"105.1\",\"city\":\"Hà Nội\"}}");
        var result = service.resolve(request());
        assertEquals("place-1", result.placeId());
        assertEquals(new BigDecimal("21.1234568"), result.latitude());
        assertEquals("Hà Nội", result.city());
    }

    @Test void rejectsMissingOrOutOfRangeClientSelectionBeforeCallingProvider() {
        assertThrows(IllegalArgumentException.class, () -> service.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(new DeliveryAddressRequest("a", "", BigDecimal.ZERO, BigDecimal.ZERO, null)));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(new DeliveryAddressRequest("a", "id", null, BigDecimal.ZERO, null)));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(new DeliveryAddressRequest("a", "id", new BigDecimal("90.1"), BigDecimal.ZERO, null)));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(new DeliveryAddressRequest("a", "id", BigDecimal.ZERO, new BigDecimal("-181"), null)));
        verifyNoInteractions(upstream);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"NaN\"", "true", "{}", "91", "-91", "\"Infinity\""})
    void rejectsInvalidProviderLatitude(String latitude) {
        respond("{\"result\":{\"address\":\"Hà Nội\",\"lat\":" + latitude + ",\"lon\":105}}");
        assertThrows(DeliveryAddressService.AddressResolutionException.class, () -> service.resolve(request()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "not json", "{\"result\":{\"address\":\"\",\"lat\":21,\"lon\":105}}", "{\"result\":{\"address\":\"Hanoi\",\"lat\":21,\"lon\":181}}"})
    void rejectsMissingOrMalformedProviderResult(String body) {
        respond(body);
        assertThrows(DeliveryAddressService.AddressResolutionException.class, () -> service.resolve(request()));
    }

    @ParameterizedTest @ValueSource(ints = {429, 503, 504, 404})
    void reportsProviderFailure(int status) {
        when(upstream.resolve(anyString(), anyString(), any())).thenReturn(new GogodukService.UpstreamResponse(status, "{}", null, null));
        assertThrows(DeliveryAddressService.AddressResolutionException.class, () -> service.resolve(request()));
    }

    private void respond(String body) {
        when(upstream.resolve(anyString(), anyString(), any())).thenReturn(new GogodukService.UpstreamResponse(200, body, null, null));
    }
}
