package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.search.ImageSearchDtos.*;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.repository.ProductRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageSearchServiceTest {
    private ProductEntity product(String id, String image, String colorId) {
        var product = new ProductEntity(); product.setId(id); product.setTitle(id); product.setSlug(id);
        product.setCoverImage("https://cdn.test/default.png");
        var color = new ProductColorVariantEntity(); color.setProduct(product); color.setColorId(colorId);
        var gallery = new ProductColorImageEntity(); gallery.setId(23L); gallery.setColorVariant(color); gallery.setImageUrl(image);
        color.setImages(Set.of(gallery)); color.setSizes(Set.of()); product.setColors(List.of(color));
        return product;
    }
    @Test void mappingChecksProductColorAndImageOwnership() {
        var resolver = new ImageMappingResolver();
        var a = product("A", "https://cdn.test/a-blue.png", "blue");
        var b = product("B", "https://cdn.test/b-blue.png", "blue");
        var winner = new AiImage("key", "https://cdn.test/a-blue.png", "blue");
        assertEquals(23L, resolver.resolve(a, winner, "req", "v").imageId());
        assertNull(resolver.resolve(b, winner, "req", "v"));
        assertNull(resolver.resolve(a, new AiImage("key", winner.sourceUrl(), "red"), "req", "v"));
        assertNull(resolver.resolve(a, new AiImage("key", "file:///local.png", "blue"), "req", "v"));
        assertEquals("https://cdn.test/default.png", a.getCoverImage());
    }
    @Test void databaseOrderCannotSwapRankingOrWinningImagesAndDeletedProductsAreExcluded() throws Exception {
        var repository = mock(ProductRepository.class); var moda = mock(ModaClient.class);
        var a = product("A", "https://cdn.test/a.png", "red");
        var b = product("B", "https://cdn.test/b.png", "red");
        when(repository.findAllById(any())).thenReturn(List.of(b, a));
        when(moda.search(any(), anyString(), anyString())).thenReturn(new AiResponse("moda", "v", 3, List.of(
                new AiItem("A", .9, 1, new AiImage("a", "https://cdn.test/a.png", "red")),
                new AiItem("missing", .8, 2, null),
                new AiItem("B", .7, 3, new AiImage("b", "https://cdn.test/stale.png", "red")))));
        var service = new ImageSearchService(moda, repository, new ProductService(repository), new ImageMappingResolver(), .55, 24);
        var result = service.search(png(), "req");
        assertEquals(List.of("A", "B"), result.items().stream().map(i -> i.product().getId()).toList());
        assertEquals("https://cdn.test/a.png", result.items().get(0).matchedImage().url());
        assertNull(result.items().get(1).matchedImage());
        assertEquals(.7, result.items().get(1).similarity());
        assertEquals(3, result.items().get(1).rank());
        verify(moda, times(1)).search(any(), anyString(), anyString());
    }
    @Test void filtersBelowThresholdIncludesBoundaryAndCapsAfterDeletedProducts() throws Exception {
        var repository = mock(ProductRepository.class); var moda = mock(ModaClient.class);
        var a = product("A", "https://cdn.test/a.png", "red");
        var b = product("B", "https://cdn.test/b.png", "red");
        when(repository.findAllById(any())).thenReturn(List.of(b, a));
        when(moda.search(any(), anyString(), anyString())).thenReturn(new AiResponse("moda", "v", 4, List.of(
                new AiItem("missing", .9, 1, null), new AiItem("A", .7, 2, null),
                new AiItem("B", .55, 3, null), new AiItem("low", .549999, 4, null))));
        var service = new ImageSearchService(moda, repository, new ProductService(repository), new ImageMappingResolver(), .55, 2);
        var result = service.search(png(), "req");
        assertEquals(List.of("A", "B"), result.items().stream().map(i -> i.product().getId()).toList());
        assertEquals(4, result.indexedProductCount());
        assertEquals(2, result.returnedProductCount());
        verify(repository).findAllById(List.of("missing", "A", "B"));
        var capped = new ImageSearchService(moda, repository, new ProductService(repository), new ImageMappingResolver(), .55, 1)
                .search(png(), "req");
        assertEquals(List.of("A"), capped.items().stream().map(i -> i.product().getId()).toList());
    }
    @Test void noMatchesReturnsEmptyWithoutCatalogFallback() throws Exception {
        var repository = mock(ProductRepository.class); var moda = mock(ModaClient.class);
        when(moda.search(any(), anyString(), anyString())).thenReturn(new AiResponse("moda", "v", 1,
                List.of(new AiItem("low", .54, 1, null))));
        var result = new ImageSearchService(moda, repository, new ProductService(repository), new ImageMappingResolver(), .55, 24)
                .search(png(), "req");
        assertTrue(result.items().isEmpty());
        assertEquals(0, result.returnedProductCount());
        verifyNoInteractions(repository);
    }
    @Test void rejectsCorruptMislabeledAndOversizeImagesBeforeAi() throws Exception {
        assertNotNull(ImageSearchService.validate(png()));
        assertEquals(400, assertThrows(ImageSearchException.class, () -> ImageSearchService.validate(
                new MockMultipartFile("image", "q.png", "image/png", new byte[]{1, 2}))).getStatus());
        assertEquals(415, assertThrows(ImageSearchException.class, () -> ImageSearchService.validate(
                new MockMultipartFile("image", "q.svg", "image/svg+xml", new byte[]{1}))).getStatus());
        assertEquals(413, assertThrows(ImageSearchException.class, () -> ImageSearchService.validate(
                new MockMultipartFile("image", "q.png", "image/png", new byte[5 * 1024 * 1024 + 1]))).getStatus());
    }
    @Test void rejectsIncompleteDuplicateAndUnorderedRankings() {
        for (var response : List.of(
                new AiResponse("moda", "v", 2, List.of(new AiItem("A", .9, 1, null))),
                new AiResponse("moda", "v", 2, List.of(new AiItem("A", .9, 1, null), new AiItem("A", .8, 2, null))),
                new AiResponse("moda", "v", 2, List.of(new AiItem("A", .1, 1, null), new AiItem("B", .8, 2, null))))) {
            assertEquals(502, assertThrows(ImageSearchException.class, () -> ImageSearchService.validateRanking(response)).getStatus());
        }
    }
    private MockMultipartFile png() throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("image", "q.png", "image/png", bytes.toByteArray());
    }
}
