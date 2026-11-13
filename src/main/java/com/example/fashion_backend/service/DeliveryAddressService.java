package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.order.DeliveryAddressRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class DeliveryAddressService {
    private final GogodukService gogodukService;
    private final AddressLookupService addresses;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeliveryAddressService(GogodukService gogodukService) {
        this.gogodukService = gogodukService;
        this.addresses = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryAddressService(GogodukService gogodukService, AddressLookupService addresses) {
        this.gogodukService = gogodukService;
        this.addresses = addresses;
    }

    public ResolvedAddress resolve(DeliveryAddressRequest request) {
        if (request != null && (request.selectionToken() != null || "GEOAPIFY".equals(request.source()))) {
            String source = request.source() == null ? "GOGODUK" : request.source();
            if (!java.util.Set.of("GEOAPIFY", "GOGODUK").contains(source) || addresses == null)
                throw new AddressException(400, "ADDRESS_INVALID_SOURCE", "Nguồn địa chỉ không hợp lệ.");
            JsonNode selected = addresses.selection(request.selectionToken(), request.sessionToken(), request.providerPlaceId(), source);
            return new ResolvedAddress(selected.path("address").asText(), selected.path("placeId").asText(),
                    coordinate(selected.path("lat")), coordinate(selected.path("lon")), text(selected, "city"), source);
        }
        if (request != null && request.source() != null && !"GOGODUK".equals(request.source()))
            throw new AddressException(400, "ADDRESS_INVALID_SOURCE", "Nguồn địa chỉ không hợp lệ.");
        if (request == null || !validText(request.formattedAddress(), 2000)
                || !validText(request.providerPlaceId(), 200)
                || !validCoordinate(request.latitude(), 90) || !validCoordinate(request.longitude(), 180)
                || (request.sessionToken() != null && request.sessionToken().length() > 200)) {
            throw new IllegalArgumentException("Vui lòng chọn địa chỉ giao hàng có tọa độ hợp lệ từ gợi ý.");
        }

        if (addresses != null) {
            JsonNode result = addresses.resolve(request.providerPlaceId().trim(), "GOGODUK", null, request.sessionToken());
            return new ResolvedAddress(result.path("address").asText(), result.path("placeId").asText(),
                    coordinate(result.path("lat")).setScale(7, RoundingMode.HALF_UP),
                    coordinate(result.path("lon")).setScale(7, RoundingMode.HALF_UP), text(result,"city"));
        }
        var upstream = gogodukService.resolve(request.providerPlaceId().trim(), "vi", request.sessionToken());
        if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
            throw new AddressResolutionException("Không thể xác thực địa chỉ với GoGoDuk. Vui lòng thử lại hoặc chọn địa chỉ khác.");
        }
        try {
            JsonNode result = mapper.readTree(upstream.body()).path("result");
            String address = text(result, "address");
            String placeId = text(result, "placeId");
            // Some Resolve responses omit the ID; the requested ID identifies this result.
            if (placeId == null) placeId = request.providerPlaceId().trim();
            BigDecimal latitude = coordinate(result.get("lat"));
            BigDecimal longitude = coordinate(result.get("lon"));
            if (!validText(address, 2000) || !validText(placeId, 200)
                    || !validCoordinate(latitude, 90) || !validCoordinate(longitude, 180)) {
                throw new IllegalArgumentException("Invalid Resolve result");
            }
            return new ResolvedAddress(address, placeId,
                    latitude.setScale(7, RoundingMode.HALF_UP), longitude.setScale(7, RoundingMode.HALF_UP), text(result, "city"));
        } catch (Exception exception) {
            throw new AddressResolutionException("GoGoDuk chưa trả địa chỉ có tọa độ hợp lệ. Vui lòng chọn lại địa chỉ hoặc thử lại sau.");
        }
    }

    private static boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private static boolean validCoordinate(BigDecimal value, int limit) {
        return value != null && value.abs().compareTo(BigDecimal.valueOf(limit)) <= 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static BigDecimal coordinate(JsonNode value) {
        if (value == null || value.isNull() || (!value.isNumber() && !value.isTextual())) return null;
        String raw = value.asText().trim();
        return raw.isEmpty() ? null : new BigDecimal(raw);
    }

    public static boolean validResolved(String address, String id, BigDecimal latitude, BigDecimal longitude) {
        return validText(address, 2000) && validText(id, 200) && validCoordinate(latitude, 90) && validCoordinate(longitude, 180);
    }

    public record ResolvedAddress(String formattedAddress, String placeId, BigDecimal latitude, BigDecimal longitude, String city, String source) {
        public ResolvedAddress(String address, String id, BigDecimal lat, BigDecimal lon, String city) {
            this(address, id, lat, lon, city, "GOGODUK");
        }
    }

    public static class AddressResolutionException extends RuntimeException {
        public AddressResolutionException(String message) { super(message); }
    }
}
