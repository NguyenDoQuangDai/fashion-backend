package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.order.DeliveryAddressRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeoapifyFallbackTest {
    private final GogodukService primary=mock(GogodukService.class);
    private final GeoapifyService geo=mock(GeoapifyService.class);
    private final MemoryFallbackStore store=new MemoryFallbackStore();
    private final ObjectMapper mapper=new ObjectMapper();
    private final ProviderFallback modes=new ProviderFallback("auto","auto");
    private final AddressLookupService lookup=new AddressLookupService(primary,geo,modes,store);
    private static final String SESSION="client-session";
    private static final String RESULTS="""
        {"results":[{"formatted":"12 Tràng Tiền, Hà Nội","country_code":"vn","result_type":"building","lat":21.0245,"lon":105.855,"city":"Hà Nội"}]}
        """;
    private GogodukService.UpstreamResponse response(int status,String body) { return new GogodukService.UpstreamResponse(status,body,null,null); }
    private void failPrimary(int status) throws Exception {
        when(primary.suggest(anyString(),anyString(),anyString(),anyString())).thenReturn(response(status,"{}"));
        when(geo.suggest(anyString())).thenReturn(mapper.readTree(RESULTS));
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,500,503,504})
    void fallsBackAndNeverTrustsClientCoordinates(int status) throws Exception {
        failPrimary(status);
        var suggestion=lookup.suggest("Tràng Tiền",SESSION).path("predictions").get(0);
        var request=new DeliveryAddressRequest("Forged",suggestion.path("placeId").asText(),BigDecimal.ZERO,BigDecimal.ZERO,
                SESSION,"GEOAPIFY",suggestion.path("selectionToken").asText());
        var result=new DeliveryAddressService(primary,lookup).resolve(request);
        assertEquals("GEOAPIFY",result.source()); assertEquals("12 Tràng Tiền, Hà Nội",result.formattedAddress());
        assertEquals(0,new BigDecimal("21.0245").compareTo(result.latitude()));
        verify(primary,never()).resolve(anyString(),anyString(),any());
    }
    @Test void cachesQueriesButIssuesDifferentTokensForEachSession() throws Exception {
        failPrimary(503);
        var first=lookup.suggest("Tràng Tiền",SESSION).path("predictions").get(0);
        var second=lookup.suggest("Tràng Tiền","other-client").path("predictions").get(0);
        assertNotEquals(first.path("selectionToken").asText(),second.path("selectionToken").asText());
        var ex=assertThrows(AddressException.class,()->lookup.selection(first.path("selectionToken").asText(),"other-client",first.path("placeId").asText()));
        assertEquals("ADDRESS_RESELECT_REQUIRED",ex.getCode());
        verify(geo,times(1)).suggest(anyString());
    }
    @Test void rejectsExpiredTokenAndMismatchedPlaceReference() throws Exception {
        failPrimary(503);
        var suggestion=lookup.suggest("Tràng Tiền",SESSION).path("predictions").get(0);
        String token=suggestion.path("selectionToken").asText(),id=suggestion.path("placeId").asText();
        assertThrows(AddressException.class,()->lookup.selection(token,SESSION,"wrong"));
        store.now+=Duration.ofMinutes(16).toMillis();
        assertThrows(AddressException.class,()->lookup.selection(token,SESSION,id));
    }
    @Test void primaryEmptyResultsAreNotAnOutage() {
        when(primary.suggest(anyString(),anyString(),anyString(),anyString())).thenReturn(response(200,"{\"predictions\":[]}"));
        assertTrue(lookup.suggest("Tràng Tiền",SESSION).path("predictions").isEmpty()); verifyNoInteractions(geo);
    }
    @Test void malformedPrimarySchemaUsesFallback() throws Exception {
        failPrimary(200);
        assertEquals("GEOAPIFY",lookup.suggest("Tràng Tiền",SESSION).path("source").asText());
    }
    @Test void filtersAdministrativeCentersAndResultsOutsideVietnam() throws Exception {
        failPrimary(503);
        when(geo.suggest(anyString())).thenReturn(mapper.readTree("""
            {"results":[{"formatted":"Hà Nội","country_code":"vn","result_type":"city","lat":21,"lon":105},
                        {"formatted":"Other","country_code":"us","result_type":"building","lat":21,"lon":105}]}
            """));
        assertTrue(lookup.suggest("Tràng Tiền",SESSION).path("predictions").isEmpty());
    }
    @Test void geoOnlyNeverCallsPrimary() throws Exception {
        when(geo.suggest(anyString())).thenReturn(mapper.readTree(RESULTS));
        var service=new AddressLookupService(primary,geo,new ProviderFallback("geoapify-only","geoapify-only"),store);
        service.suggest("Tràng Tiền",SESSION);verifyNoInteractions(primary);
    }
    @Test void primaryOnlyNeverCallsGeo() {
        when(primary.suggest(anyString(),anyString(),anyString(),anyString())).thenReturn(response(503,"{}"));
        var service=new AddressLookupService(primary,geo,new ProviderFallback("gogoduk-only","gogoduk-only"),store);
        assertThrows(AddressException.class,()->service.suggest("Tràng Tiền",SESSION));verifyNoInteractions(geo);
    }
    @Test void oldPlaceIdFailurePreservesTransientErrorWithoutSilentGeocoding() {
        when(primary.resolve(anyString(),anyString(),any())).thenReturn(response(503,"{}"));
        var error=assertThrows(AddressException.class,()->lookup.resolve("old-id","GOGODUK",null,SESSION));
        assertEquals("ADDRESS_PROVIDER_UNAVAILABLE",error.getCode()); verifyNoInteractions(geo);
    }
    @Test void circuitOpensAfterThreeFailuresAndAllowsOneRecoveryProbe() {
        class TestClock extends Clock {
            long millis;
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return Instant.ofEpochMilli(millis);}
        }
        TestClock clock=new TestClock();ProviderFallback policy=new ProviderFallback("auto","auto",clock);
        for(int i=0;i<3;i++)policy.failure("routing",null);
        assertFalse(policy.primaryAllowed("routing")); assertTrue(policy.primaryAllowed("address"));
        clock.millis=61000;assertTrue(policy.primaryAllowed("routing"));assertFalse(policy.primaryAllowed("routing"));
        policy.success("routing");assertTrue(policy.primaryAllowed("routing"));
    }
    @Test void routingFallbackIsPinnedAcrossPrimaryRecoveryAndCacheExpires() {
        var destination=new DeliveryAddressService.ResolvedAddress("Address","id",new BigDecimal("21"),new BigDecimal("105"),null);
        when(geo.mode()).thenReturn("scooter");
        when(primary.directions(anyString(),anyString())).thenReturn(response(503,"{}"));
        when(geo.route(any())).thenReturn(new ShippingService.ShippingQuote(new BigDecimal("6000.01"),new BigDecimal("6000"),"OK","GEOAPIFY","scooter"));
        var shipping=new ShippingService(primary,geo,modes,store);
        assertEquals("GEOAPIFY",shipping.quote(destination).provider());
        when(primary.directions(anyString(),anyString())).thenReturn(response(200,"{\"status\":\"OK\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":5000}}]}]}"));
        assertEquals("GEOAPIFY",shipping.quote(destination).provider());
        verify(geo,times(1)).route(any());verify(primary,times(1)).directions(anyString(),anyString());
        store.now+=Duration.ofMinutes(6).toMillis();assertEquals("GOGODUK",shipping.quote(destination).provider());
    }
    @Test void noRoadRouteDoesNotTriggerFallback() {
        when(geo.mode()).thenReturn("scooter");when(primary.directions(anyString(),anyString())).thenReturn(response(200,"{\"status\":\"ZERO_RESULTS\"}"));
        var service=new ShippingService(primary,geo,modes,store);
        var error=assertThrows(ShippingException.class,()->service.quote(new DeliveryAddressService.ResolvedAddress("A","id",BigDecimal.ONE,BigDecimal.ONE,null)));
        assertEquals(422,error.getStatus());verify(geo,never()).route(any());
    }
}
