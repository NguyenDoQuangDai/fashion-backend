package com.example.fashion_backend.security;

import com.example.fashion_backend.entity.RoleEntity;
import com.example.fashion_backend.entity.UserEntity;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class UserPrincipal implements UserDetails {
    private final UserEntity user;
    private final List<GrantedAuthority> authorities;

    public UserPrincipal(UserEntity user) {
        this.user = user;
        this.authorities = user.getRoles() == null ? List.of() : user.getRoles().stream()
                .map(RoleEntity::getName)
                .map(this::toAuthority)
                .collect(Collectors.toList());
    }

    private SimpleGrantedAuthority toAuthority(String roleName) {
        String name = roleName == null ? "" : roleName.trim();
        if (!name.startsWith("ROLE_")) {
            name = "ROLE_" + name;
        }
        return new SimpleGrantedAuthority(name);
    }

    public UserEntity getUser() { return user; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }

    @Override
    public String getPassword() { return user.getPasswordHash(); }

    @Override
    public String getUsername() { return user.getEmail(); }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return user.getEnabled() != null && user.getEnabled(); }
}
