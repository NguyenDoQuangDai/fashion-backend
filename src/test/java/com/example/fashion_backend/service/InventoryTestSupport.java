package com.example.fashion_backend.service;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.repository.ProductRepository;
import java.util.*;
import static org.mockito.Mockito.*;
public final class InventoryTestSupport {
 public static void bind(OrderService service, ProductRepository products) {
  InventoryService inv=mock(InventoryService.class);
  Map<Long,ProductSizeVariantEntity> sizes=new TreeMap<>();
  doAnswer(call -> {
   Collection<String> ids=call.getArgument(0);
   for(String id:ids) products.findById(id).ifPresent(p -> p.getColors().forEach(c -> c.getSizes().forEach(v -> {
    if(v.getId()==null) v.setId((long)sizes.size()+1); sizes.put(v.getId(),v);
   })));
   return null;
  }).when(inv).lockProducts(any());
  when(inv.lockSizes(any())).thenAnswer(call -> new TreeMap<>(sizes));
  doAnswer(call -> { ProductSizeVariantEntity v=call.getArgument(0); v.setInventory(v.getInventory()+(Integer)call.getArgument(3)); return null; }).when(inv).change(any(),anyString(),anyString(),anyInt());
  org.springframework.test.util.ReflectionTestUtils.setField(service,"inventory",inv);
 }
}
