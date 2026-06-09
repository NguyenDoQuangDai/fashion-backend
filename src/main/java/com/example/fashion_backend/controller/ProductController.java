package com.example.fashion_backend.controller;

import com.example.fashion_backend.model.Product;
import com.example.fashion_backend.service.ProductService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/products")
    public List<Product> list(@RequestParam(value = "page", required = false) Integer page,
                              @RequestParam(value = "size", required = false) Integer size,
                              @RequestParam(value = "gender", required = false) String gender,
                              @RequestParam(value = "category", required = false) String category,
                              @RequestParam(value = "tag", required = false) String tag,
                              @RequestParam(value = "type", required = false) String type) {
        return productService.list(page, size, gender, category, tag, type);
    }

    @GetMapping("/products/{id}")
    public Product get(@PathVariable String id) {
        return productService.findById(id);
    }

    @GetMapping("/search")
    public List<Product> search(@RequestParam(value = "q", required = false) String q,
                                @RequestParam(value = "priceMin", required = false) BigDecimal priceMin,
                                @RequestParam(value = "priceMax", required = false) BigDecimal priceMax,
                                @RequestParam(value = "gender", required = false) String gender,
                                @RequestParam(value = "category", required = false) String category,
                                @RequestParam(value = "tag", required = false) String tag,
                                @RequestParam(value = "type", required = false) String type) {
        return productService.search(q, priceMin, priceMax, gender, category, tag, type);
    }

    @GetMapping("/products/suggested")
    public List<Product> suggested(@RequestParam(value = "size", required = false, defaultValue = "8") Integer size) {
        return productService.suggested(size);
    }

    @GetMapping("/products/{id}/similar")
    public List<Product> similar(@PathVariable String id,
                                 @RequestParam(value = "size", required = false, defaultValue = "6") Integer size) {
        return productService.similarByTags(id, size);
    }
}
