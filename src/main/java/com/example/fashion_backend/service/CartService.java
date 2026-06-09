package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.CartEntity;
import com.example.fashion_backend.entity.CartItemEntity;
import com.example.fashion_backend.entity.ProductColorVariantEntity;
import com.example.fashion_backend.entity.ProductEntity;
import com.example.fashion_backend.entity.ProductSizeVariantEntity;
import com.example.fashion_backend.model.CartItem;
import com.example.fashion_backend.repository.CartRepository;
import com.example.fashion_backend.repository.ProductRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    public CartService(CartRepository cartRepository, ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<CartItem> getCartItems(String clientId) {
        String guestId = normalizeClientId(clientId);
        return cartRepository.findByGuestId(guestId)
                .map(this::mapToDto)
                .orElseGet(ArrayList::new);
    }

    @Transactional
    public void addToCart(String clientId, CartItem item) {
        String guestId = normalizeClientId(clientId);
        CartEntity cart = cartRepository.findByGuestId(guestId).orElseGet(() -> createCart(guestId));
        List<CartItemEntity> items = cart.getItems();
        if (items == null) {
            items = new ArrayList<>();
            cart.setItems(items);
        }
        VariantRef variantRef = resolveVariant(item);
        Optional<CartItemEntity> existing = items.stream()
                .filter(it -> it.getProductId().equals(item.getProductId())
                        && Objects.equals(it.getColorVariantId(), variantRef.colorVariantId)
                        && Objects.equals(it.getSizeVariantId(), variantRef.sizeVariantId))
                .findFirst();
        if (existing.isPresent()) {
            CartItemEntity entity = existing.get();
            int newQty = entity.getQuantity() + item.getQuantity();
            entity.setQuantity(newQty);
        } else {
            CartItemEntity entity = new CartItemEntity();
            entity.setCart(cart);
            entity.setProductId(item.getProductId());
            entity.setColorVariantId(variantRef.colorVariantId);
            entity.setSizeVariantId(variantRef.sizeVariantId);
            entity.setQuantity(item.getQuantity());
            items.add(entity);
        }
        cartRepository.save(cart);
    }

    @Transactional
    public void updateQuantity(String clientId, CartItem item) {
        String guestId = normalizeClientId(clientId);
        CartEntity cart = cartRepository.findByGuestId(guestId).orElseGet(() -> createCart(guestId));
        List<CartItemEntity> items = cart.getItems();
        if (items == null) {
            items = new ArrayList<>();
            cart.setItems(items);
        }
        VariantRef variantRef = resolveVariant(item);
        Optional<CartItemEntity> existing = items.stream()
                .filter(it -> it.getProductId().equals(item.getProductId())
                        && Objects.equals(it.getColorVariantId(), variantRef.colorVariantId)
                        && Objects.equals(it.getSizeVariantId(), variantRef.sizeVariantId))
                .findFirst();
        if (existing.isPresent()) {
            if (item.getQuantity() <= 0) {
                items.remove(existing.get());
            } else {
                existing.get().setQuantity(item.getQuantity());
            }
        } else if (item.getQuantity() > 0) {
            CartItemEntity entity = new CartItemEntity();
            entity.setCart(cart);
            entity.setProductId(item.getProductId());
            entity.setQuantity(item.getQuantity());
            items.add(entity);
        }
        cartRepository.save(cart);
    }

    @Transactional
    public void removeItem(String clientId, String productId) {
        String guestId = normalizeClientId(clientId);
        cartRepository.findByGuestId(guestId).ifPresent(cart -> {
            if (cart.getItems() != null) {
                cart.getItems().removeIf(item -> item.getProductId().equals(productId));
            }
            cartRepository.save(cart);
        });
    }

    @Transactional
    public void mergeGuestCartToUser(UUID userId, String clientId) {
        String guestId = normalizeClientId(clientId);
        Optional<CartEntity> guestCartOpt = cartRepository.findByGuestId(guestId);
        if (guestCartOpt.isEmpty()) {
            return;
        }

        CartEntity guestCart = guestCartOpt.get();
        CartEntity userCart = cartRepository.findByUserId(userId).orElseGet(() -> createUserCart(userId));
        if (userCart.getItems() == null) {
            userCart.setItems(new ArrayList<>());
        }
        if (guestCart.getItems() != null) {
            for (CartItemEntity guestItem : guestCart.getItems()) {
                Optional<CartItemEntity> existing = userCart.getItems().stream()
                        .filter(it -> it.getProductId().equals(guestItem.getProductId())
                                && Objects.equals(it.getColorVariantId(), guestItem.getColorVariantId())
                                && Objects.equals(it.getSizeVariantId(), guestItem.getSizeVariantId()))
                        .findFirst();
                if (existing.isPresent()) {
                    CartItemEntity entity = existing.get();
                    int newQty = entity.getQuantity() + guestItem.getQuantity();
                    entity.setQuantity(newQty);
                } else {
                    CartItemEntity newItem = new CartItemEntity();
                    newItem.setCart(userCart);
                    newItem.setProductId(guestItem.getProductId());
                    newItem.setColorVariantId(guestItem.getColorVariantId());
                    newItem.setSizeVariantId(guestItem.getSizeVariantId());
                    newItem.setQuantity(guestItem.getQuantity());
                    userCart.getItems().add(newItem);
                }
            }
        }

        cartRepository.save(userCart);
        cartRepository.delete(guestCart);
    }

    private CartEntity createCart(String guestId) {
        CartEntity cart = new CartEntity();
        cart.setGuestId(guestId);
        cart.setCreatedAt(OffsetDateTime.now());
        return cart;
    }

    private CartEntity createUserCart(UUID userId) {
        CartEntity cart = new CartEntity();
        cart.setUser(new com.example.fashion_backend.entity.UserEntity() {{ setId(userId); }});
        cart.setCreatedAt(OffsetDateTime.now());
        return cart;
    }

    private List<CartItem> mapToDto(CartEntity cart) {
        if (cart.getItems() == null) return new ArrayList<>();
        List<CartItem> items = new ArrayList<>();
        for (CartItemEntity entity : cart.getItems()) {
            CartItem dto = new CartItem();
            dto.setProductId(entity.getProductId());
            dto.setQuantity(entity.getQuantity() == null ? 0 : entity.getQuantity());
            fillVariantInfo(dto, entity);
            items.add(dto);
        }
        return items;
    }

    private VariantRef resolveVariant(CartItem item) {
        VariantRef ref = new VariantRef();
        if (item == null || item.getProductId() == null) return ref;
        Optional<ProductEntity> productOpt = productRepository.findById(item.getProductId());
        if (productOpt.isEmpty()) return ref;
        ProductEntity product = productOpt.get();
        ProductColorVariantEntity colorVariant = findColorVariant(product, item.getColorId(), item.getColorName());
        ProductSizeVariantEntity sizeVariant = findSizeVariant(colorVariant, item.getSizeId(), item.getSizeName());
        ref.colorVariantId = colorVariant == null ? null : colorVariant.getId();
        ref.sizeVariantId = sizeVariant == null ? null : sizeVariant.getId();
        return ref;
    }

    private void fillVariantInfo(CartItem dto, CartItemEntity entity) {
        if (entity.getColorVariantId() == null || entity.getSizeVariantId() == null) return;
        productRepository.findById(entity.getProductId()).ifPresent(product -> {
            ProductColorVariantEntity colorVariant = null;
            ProductSizeVariantEntity sizeVariant = null;
            if (product.getColors() != null) {
                for (ProductColorVariantEntity color : product.getColors()) {
                    if (Objects.equals(color.getId(), entity.getColorVariantId())) {
                        colorVariant = color;
                        break;
                    }
                }
                if (colorVariant != null && colorVariant.getSizes() != null) {
                    for (ProductSizeVariantEntity size : colorVariant.getSizes()) {
                        if (Objects.equals(size.getId(), entity.getSizeVariantId())) {
                            sizeVariant = size;
                            break;
                        }
                    }
                }
            }
            dto.setColorId(colorVariant == null ? null : colorVariant.getColorId());
            dto.setColorName(colorVariant == null ? null : colorVariant.getColorName());
            dto.setSizeId(sizeVariant == null ? null : sizeVariant.getSizeId());
            dto.setSizeName(sizeVariant == null ? null : sizeVariant.getSizeName());
        });
    }

    private ProductColorVariantEntity findColorVariant(ProductEntity product, String colorId, String colorName) {
        if (product == null || product.getColors() == null) return null;
        for (ProductColorVariantEntity color : product.getColors()) {
            if (colorId != null && Objects.equals(colorId, color.getColorId())) return color;
        }
        for (ProductColorVariantEntity color : product.getColors()) {
            if (colorName != null && Objects.equals(colorName, color.getColorName())) return color;
        }
        return null;
    }

    private ProductSizeVariantEntity findSizeVariant(ProductColorVariantEntity colorVariant, String sizeId, String sizeName) {
        if (colorVariant == null || colorVariant.getSizes() == null) return null;
        for (ProductSizeVariantEntity size : colorVariant.getSizes()) {
            if (sizeId != null && Objects.equals(sizeId, size.getSizeId())) return size;
        }
        for (ProductSizeVariantEntity size : colorVariant.getSizes()) {
            if (sizeName != null && Objects.equals(sizeName, size.getSizeName())) return size;
        }
        return null;
    }

    private static class VariantRef {
        private Long colorVariantId;
        private Long sizeVariantId;
    }

    private String normalizeClientId(String clientId) {
        return (clientId == null || clientId.isBlank()) ? "default" : clientId;
    }
}
