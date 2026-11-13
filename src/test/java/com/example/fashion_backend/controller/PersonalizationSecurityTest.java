package com.example.fashion_backend.controller;

import com.example.fashion_backend.entity.UserEntity;
import com.example.fashion_backend.security.*;
import com.example.fashion_backend.service.*;
import io.jsonwebtoken.Claims;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PersonalizationSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig.class)
    static class Fixture {
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean UserDetailsServiceImpl users() { return mock(UserDetailsServiceImpl.class); }
        @Bean JwtAuthenticationFilter jwtFilter(JwtService jwt, UserDetailsServiceImpl users) {
            return new JwtAuthenticationFilter(jwt, users);
        }
        @Bean RecommendationService recommendations() { return mock(RecommendationService.class); }
        @Bean UserProfileService profiles() { return mock(UserProfileService.class); }
        @Bean RecommendationController recommendationController(RecommendationService s) { return new RecommendationController(s); }
        @Bean UserProfileController profileController(UserProfileService s) { return new UserProfileController(s); }
    }

    @Test void realSecurityChainAllowsGuestRecommendationsButRejectsGuestTracking() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Fixture.class); context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            mvc.perform(get("/api/recommendations?size=12")).andExpect(status().isOk());
            mvc.perform(post("/api/users/me/product-views").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"productId\":\"p1\"}")).andExpect(status().isForbidden());
            verifyNoInteractions(context.getBean(UserProfileService.class));

            UUID id = UUID.randomUUID(); var user = new UserEntity(); user.setId(id);
            var claims = mock(Claims.class); when(claims.getSubject()).thenReturn(id.toString());
            when(context.getBean(JwtService.class).parseClaims("test-token")).thenReturn(claims);
            when(context.getBean(UserDetailsServiceImpl.class).loadUserById(id)).thenReturn(new UserPrincipal(user));
            mvc.perform(post("/api/users/me/product-views").header("Authorization", "Bearer test-token")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":\"p1\"}"))
                    .andExpect(status().isNoContent());
            verify(context.getBean(UserProfileService.class)).recordProductView(id, "p1");
        }
    }
}
