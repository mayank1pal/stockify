package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSession {
    private String userId;
    private String userName;
    private String email;
    private String accessToken;
    private String publicToken;
    private String refreshToken;
    private boolean isAuthenticated;
    private long expiresAt;
    
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
