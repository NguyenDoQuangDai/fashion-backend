package com.example.fashion_backend.controller;

import com.example.fashion_backend.config.GlobalExceptionHandler;
import com.example.fashion_backend.entity.UserEntity;
import com.example.fashion_backend.security.UserPrincipal;
import com.example.fashion_backend.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PersonalizationHttpTest {
    @Test void userIdentityComesFromPrincipalAndResponseRemainsAnArray() throws Exception {
        var recommendations = mock(RecommendationService.class);
        var profiles = mock(UserProfileService.class);
        var user = new UserEntity(); user.setId(UUID.randomUUID());
        var principal = new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null, List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(recommendations), new UserProfileController(profiles))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/recommendations?size=12&userId=someone-else").principal(principal))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(recommendations).recommend(12, null, null, null, null, List.of(), user.getId());
        mvc.perform(get("/api/recommendations?size=8")).andExpect(status().isOk());
        verify(recommendations).recommend(8, null, null, null, null, List.of(), null);
        mvc.perform(post("/api/users/me/product-views").principal(principal).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"p1\",\"userId\":\"someone-else\"}"))
                .andExpect(status().isNoContent());
        verify(profiles).recordProductView(user.getId(), "p1");
        mvc.perform(post("/api/users/me/product-views").principal(principal).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(profiles, times(1)).recordProductView(any(), any());
    }

    @Test void cartControllerPassesPrincipalOwnerInsteadOfTrustingClientId() throws Exception {
        var carts = mock(CartService.class);
        var user = new UserEntity(); user.setId(UUID.randomUUID());
        var principal = new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null, List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new CartController(carts)).build();
        mvc.perform(get("/api/cart?clientId=other-guest").principal(principal)).andExpect(status().isOk());
        verify(carts).getCartItems(user.getId(), "other-guest");
        mvc.perform(get("/api/cart?clientId=browser")).andExpect(status().isOk());
        verify(carts).getCartItems(null, "browser");
    }
}
