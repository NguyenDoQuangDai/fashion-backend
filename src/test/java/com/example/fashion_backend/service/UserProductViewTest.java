package com.example.fashion_backend.service;

import com.example.fashion_backend.repository.*;
import com.example.fashion_backend.dto.user.UserProfileUpdateRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProductViewTest {
    final UserRepository users = mock(UserRepository.class);
    final ProductRepository products = mock(ProductRepository.class);
    final UserProductViewRepository views = mock(UserProductViewRepository.class);
    final UserProfileService service = new UserProfileService(users, products, views);

    @Test void viewsUseServerTimeAndThirtyMinuteDeduplicationWindow() {
        var user = UUID.randomUUID();
        when(products.existsById("p1")).thenReturn(true);
        OffsetDateTime before = OffsetDateTime.now();
        service.recordProductView(user, "p1");
        var now = ArgumentCaptor.forClass(OffsetDateTime.class);
        var cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(views).recordView(eq(user), eq("p1"), now.capture(), cutoff.capture());
        assertFalse(now.getValue().isBefore(before));
        assertEquals(now.getValue().minusMinutes(30), cutoff.getValue());
    }

    @Test void nonexistentProductsCannotBeRecordedAndInvalidBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.recordProductView(UUID.randomUUID(), "missing"));
        verifyNoInteractions(views);
        UUID user = UUID.randomUUID();
        when(users.findById(user)).thenReturn(Optional.of(new com.example.fashion_backend.entity.UserEntity()));
        var request = new UserProfileUpdateRequest(); request.setPriceMin(BigDecimal.valueOf(-1));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(user, request));
        verify(users, never()).save(any());
    }
}
