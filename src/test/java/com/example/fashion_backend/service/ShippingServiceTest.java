package com.example.fashion_backend.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShippingServiceTest {
    private final GogodukService provider = mock(GogodukService.class);
    private final ShippingService service = new ShippingService(provider);
    private final DeliveryAddressService.ResolvedAddress destination = new DeliveryAddressService.ResolvedAddress(
            "Address", "id", new BigDecimal("21.0288654"), new BigDecimal("105.7819884"), null);

    @ParameterizedTest @CsvSource({"0,0", "4999.999,0", "5000,0", "5000.01,3000", "5001,3000", "6000,3000",
            "6000.01,6000", "6001,6000", "21000,48000", "21000.01,50000", "21001,50000", "500000,50000"})
    void calculatesAgreedFeeWithoutRoundingDistanceFirst(String meters, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(ShippingService.calculateFee(new BigDecimal(meters))));
    }

    @Test void sumsLegsOfFirstRouteAndUsesResolvedDestination() {
        respond(200, "{\"status\":\"OK\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":3000}},{\"distance\":{\"value\":3000.01}}]},{\"legs\":[{\"distance\":{\"value\":1}}]}]}");
        var result = service.quote(destination);
        assertEquals(new BigDecimal("6000.01"), result.distanceMeters());
        assertEquals(new BigDecimal("6000"), result.shippingFee());
        verify(provider).directions("20.9986611,105.805369", "21.0288654,105.7819884");
    }

    @Test void estimatedRouteCanBeQuotedButIsMarkedAsEstimated() {
        respond(200, "{\"status\":\"ESTIMATED\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":6000.01}}]}]}");
        var quote = service.quote(destination);
        assertEquals("ESTIMATED", quote.routeStatus());
        assertEquals(new BigDecimal("6000"), quote.shippingFee());
    }

    @Test void zeroResultsHasNoRoute() {
        respond(200, "{\"status\":\"ZERO_RESULTS\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":100}}]}]}");
        var error = assertThrows(ShippingException.class, () -> service.quote(destination));
        assertEquals(422, error.getStatus()); assertEquals("SHIPPING_NO_ROAD_ROUTE", error.getCode());
    }

    @ParameterizedTest @ValueSource(strings = {"null", "\"\"", "\"NaN\"", "-1", "{}", "true", "1000000001"})
    void rejectsInvalidDistance(String distance) {
        respond(200, "{\"status\":\"OK\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":" + distance + "}}]}]}");
        assertEquals(502, assertThrows(ShippingException.class, () -> service.quote(destination)).getStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "null", "not JSON", "{\"status\":\"OK\",\"routes\":[{\"legs\":[]}]}"})
    void rejectsMalformedResponses(String body) {
        respond(200, body);
        assertEquals(502, assertThrows(ShippingException.class, () -> service.quote(destination)).getStatus());
    }

    @Test void noRoutesIsAnActionableError() {
        respond(200, "{\"status\":\"OK\",\"routes\":[]}");
        assertEquals(422, assertThrows(ShippingException.class, () -> service.quote(destination)).getStatus());
    }

    @ParameterizedTest @CsvSource({"401,503,SHIPPING_PROVIDER_NOT_CONFIGURED", "403,503,SHIPPING_PROVIDER_NOT_CONFIGURED",
            "503,503,SHIPPING_PROVIDER_UNAVAILABLE", "504,504,SHIPPING_TIMEOUT", "429,503,SHIPPING_RATE_LIMITED"})
    void classifiesProviderFailures(int upstream, int http, String code) {
        respond(upstream, "{}");
        var error = assertThrows(ShippingException.class, () -> service.quote(destination));
        assertEquals(http, error.getStatus()); assertEquals(code, error.getCode());
        if (upstream == 429) assertEquals(30, error.getRetryAfter());
    }

    private void respond(int status, String body) {
        when(provider.directions(anyString(), anyString())).thenReturn(new GogodukService.UpstreamResponse(status, body, null, "30"));
    }
}
