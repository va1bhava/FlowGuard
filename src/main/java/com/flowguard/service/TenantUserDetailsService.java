package com.flowguard.service;

import com.flowguard.Entity.Tenant;
import com.flowguard.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantUserDetailsService implements UserDetailsService {

    private final TenantRepository tenantRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Tenant tenant = tenantRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Email not found: " + email));

        return org.springframework.security.core.userdetails.User
                .withUsername(tenant.getEmail())
                .password(tenant.getPasswordHash())
                .roles("TENANT")
                .build();
    }
}