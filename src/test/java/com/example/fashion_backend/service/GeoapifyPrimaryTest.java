package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.order.DeliveryAddressRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeoapifyPrimaryTest {
    private final GogodukService gogoduk = mock(GogodukService.class);
    private final GeoapifyService geo = mock(GeoapifyService.class);
    private final MemoryFallbackStore store = new MemoryFallbackStore();
    private final ProviderFallback policy = new ProviderFallback("geoapify-first", "geoapify-first");
    private final AddressLookupService addresses = new AddressLookupService(gogoduk, geo, policy, store);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeliveryAddressService.ResolvedAddress destination = new DeliveryAddressService.ResolvedAddress(
            "Address", "id", BigDecimal.ONE, BigDecimal.TEN, null);

    private GogodukService.UpstreamResponse response(int status, String body) {
        return new GogodukService.UpstreamResponse(status, body, null, null);
    }
    private void geoResult() throws Exception {
        when(geo.suggest(anyString())).thenReturn(mapper.readTree("""
                {"results":[{"formatted":"Tràng Tiền","country_code":"vn","result_type":"street","lat":21,"lon":105}]}
                """));
    }
    private void gogodukResult() {
        when(gogoduk.suggest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(response(200, "{\"predictions\":[{\"placeId\":\"g-id\",\"mainText\":\"Address\"}]}"));
        when(gogoduk.resolve(anyString(), anyString(), any()))
                .thenReturn(response(200, "{\"result\":{\"address\":\"Canonical\",\"lat\":21,\"lon\":105}}"));
    }

    @Test void geoSuccessDoesNotCallGogodukAndEmptyResultsAreValid() throws Exception {
        geoResult();
        assertEquals("GEOAPIFY", addresses.suggest("Address", "session").path("source").asText());
        when(geo.suggest(anyString())).thenReturn(mapper.readTree("{\"results\":[]}"));
        assertTrue(addresses.suggest("Other address", "session").path("predictions").isEmpty());
        verifyNoInteractions(gogoduk);
    }

    @ParameterizedTest @ValueSource(ints = {502, 503, 504})
    void geoTechnicalFailureUsesGogoduk(int status) {
        when(geo.suggest(anyString())).thenThrow(new AddressException(status, "ADDRESS_PROVIDER_UNAVAILABLE", "Unavailable"));
        gogodukResult();
        assertEquals("GOGODUK", addresses.suggest("Address", "session").path("source").asText());
        var order = inOrder(geo, gogoduk);
        order.verify(geo).suggest(anyString());
        order.verify(gogoduk).suggest(anyString(), anyString(), anyString(), anyString());
    }

    @Test void malformedGeoResponseUsesGogoduk() throws Exception {
        when(geo.suggest(anyString())).thenReturn(mapper.readTree("{}"));
        gogodukResult();
        assertEquals("GOGODUK", addresses.suggest("Address", "session").path("source").asText());
    }

    @Test void invalidInputDoesNotCallEitherProvider() {
        assertEquals(400, assertThrows(AddressException.class, () -> addresses.suggest("a", "session")).getStatus());
        verifyNoInteractions(geo, gogoduk);
    }

    @Test void snapshotSurvivesProviderFailureAndIgnoresClientCoordinates() {
        gogodukResult();
        var resolved = addresses.resolve("g-id", "GOGODUK", null, "session");
        String token = resolved.path("selectionToken").asText();
        assertFalse(token.isBlank());
        when(gogoduk.resolve(anyString(), anyString(), any())).thenReturn(response(503, "{}"));
        var delivery = new DeliveryAddressService(gogoduk, addresses);
        var request = new DeliveryAddressRequest("Forged", "g-id", BigDecimal.ZERO, BigDecimal.ZERO, "session", "GOGODUK", token);
        for (int i = 0; i < 2; i++) {
            var canonical = delivery.resolve(request);
            assertEquals("Canonical", canonical.formattedAddress());
            assertEquals("GOGODUK", canonical.source());
            assertEquals(0, new BigDecimal("21").compareTo(canonical.latitude()));
        }
        verify(gogoduk, times(1)).resolve(anyString(), anyString(), any());
        assertThrows(AddressException.class, () -> addresses.selection(token, "session", "g-id", "GEOAPIFY"));
        assertThrows(AddressException.class, () -> addresses.selection(token, "other-session", "g-id", "GOGODUK"));
        assertThrows(AddressException.class, () -> addresses.selection(token, "session", "other-id", "GOGODUK"));
        store.now += Duration.ofMinutes(16).toMillis();
        assertEquals("ADDRESS_RESELECT_REQUIRED", assertThrows(AddressException.class, () -> delivery.resolve(request)).getCode());
    }

    @Test void geoSnapshotRemainsCompatibleWithExistingSuggestionTokens() throws Exception {
        geoResult();
        var suggestion = addresses.suggest("Address", "session").path("predictions").get(0);
        String token = suggestion.path("selectionToken").asText();
        assertEquals(token, addresses.resolve(suggestion.path("placeId").asText(), "GEOAPIFY", token, "session")
                .path("selectionToken").asText());
        assertThrows(AddressException.class, () -> addresses.resolve(suggestion.path("placeId").asText(), "GOGODUK", token, "session"));
        verifyNoInteractions(gogoduk);
    }

    @Test void databaseFailureIsNotReportedAsInvalidSelection() {
        FallbackStore broken = mock(FallbackStore.class);
        when(broken.get(anyString())).thenThrow(new IllegalStateException("Database unavailable"));
        var service = new AddressLookupService(gogoduk, geo, policy, broken);
        assertThrows(IllegalStateException.class, () -> service.selection(java.util.UUID.randomUUID().toString(), "session", "id"));
    }

    @Test void routingUsesGeoFirstAndCachesSuccessfulFallback() {
        when(geo.mode()).thenReturn("scooter");
        when(geo.route(any())).thenThrow(new ShippingException(504, "SHIPPING_TIMEOUT", "Timeout"));
        when(gogoduk.directions(anyString(), anyString())).thenReturn(response(200,
                "{\"status\":\"OK\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":6001}}]}]}"));
        var service = new ShippingService(gogoduk, geo, policy, store);
        assertEquals("GOGODUK", service.quote(destination).provider());
        assertEquals(new BigDecimal("6000"), service.quote(destination).shippingFee());
        verify(geo, times(1)).route(any());
        verify(gogoduk, times(1)).directions(anyString(), anyString());
        store.now += Duration.ofMinutes(6).toMillis();
        doReturn(new ShippingService.ShippingQuote(BigDecimal.ONE, BigDecimal.ZERO, "OK", "GEOAPIFY", "scooter")).when(geo).route(any());
        assertEquals("GEOAPIFY", service.quote(destination).provider());
        verify(gogoduk, times(1)).directions(anyString(), anyString());
    }

    @Test void geoNoRouteDoesNotFallback() {
        when(geo.mode()).thenReturn("scooter");
        when(geo.route(any())).thenThrow(new ShippingException(422, "SHIPPING_NO_ROAD_ROUTE", "No route"));
        assertEquals(422, assertThrows(ShippingException.class, () -> new ShippingService(gogoduk, geo, policy, store).quote(destination)).getStatus());
        verifyNoInteractions(gogoduk);
    }

    @Test void circuitsAreSeparateByProviderAndCapability() {
        policy.failure("GEOAPIFY", "address", 60, "suggest", "ADDRESS_RATE_LIMITED");
        assertFalse(policy.allowed("GEOAPIFY", "address"));
        assertTrue(policy.allowed("GOGODUK", "address"));
        assertTrue(policy.allowed("GEOAPIFY", "routing"));
    }

    @Test void interruptionDoesNotTriggerFallback() {
        when(geo.suggest(anyString())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new AddressException(504, "ADDRESS_TIMEOUT", "Interrupted");
        });
        try {
            assertThrows(AddressException.class, () -> addresses.suggest("Address", "session"));
            verifyNoInteractions(gogoduk);
        } finally { Thread.interrupted(); }
    }

    @Test void bothProvidersFailWithoutRequestingReselection() {
        when(geo.suggest(anyString())).thenThrow(new AddressException(503, "ADDRESS_RATE_LIMITED", "Limited", 60));
        when(gogoduk.suggest(anyString(), anyString(), anyString(), anyString())).thenReturn(response(503, "{}"));
        assertEquals("ADDRESS_PROVIDER_UNAVAILABLE", assertThrows(AddressException.class, () -> addresses.suggest("Address", "session")).getCode());
        verify(geo, times(1)).suggest(anyString());
        verify(gogoduk, times(1)).suggest(anyString(), anyString(), anyString(), anyString());
    }

    @Test void deadlineExhaustedByFirstAttemptPreventsFallback() {
        when(geo.suggest(anyString())).thenThrow(new AddressException(504, "ADDRESS_TIMEOUT", "Timeout"));
        try (var deadline = mockStatic(CheckoutDeadline.class)) {
            deadline.when(() -> CheckoutDeadline.remaining(any(Duration.class))).thenReturn(Duration.ofSeconds(1))
                    .thenThrow(new ShippingException(504, "SHIPPING_TIMEOUT", "Deadline exhausted"));
            assertEquals(504, assertThrows(ShippingException.class, () -> addresses.suggest("Address", "session")).getStatus());
            verifyNoInteractions(gogoduk);
        }
    }
}
