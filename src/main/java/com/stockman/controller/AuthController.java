package com.stockman.controller;

import com.stockman.model.AuthSession;
import com.stockman.service.ZerodhaService;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final ZerodhaService zerodhaService;

    @GetMapping("/login")
    public ResponseEntity<Map<String, String>> getLoginUrl() {
        String loginUrl = zerodhaService.getLoginUrl();
        return ResponseEntity.ok(Map.of("loginUrl", loginUrl));
    }

    @GetMapping("/callback")
    public ResponseEntity<AuthSession> handleCallback(
            @RequestParam("request_token") String requestToken,
            HttpSession session) {
        
        log.info("Received callback with request token");
        AuthSession authSession = zerodhaService.authenticate(requestToken, session.getId());
        
        if (authSession.isAuthenticated()) {
            session.setAttribute("auth", authSession);
            return ResponseEntity.ok(authSession);
        } else {
            return ResponseEntity.status(401).body(authSession);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAuthStatus(HttpSession session) {
        AuthSession authSession = (AuthSession) session.getAttribute("auth");
        
        if (authSession != null && authSession.isAuthenticated() && !authSession.isExpired()) {
            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "userId", authSession.getUserId(),
                    "userName", authSession.getUserName()
            ));
        }
        
        return ResponseEntity.ok(Map.of("authenticated", false));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpSession session) {
        zerodhaService.logout(session.getId());
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    // Demo mode for testing without Zerodha credentials
    @PostMapping("/demo")
    public ResponseEntity<AuthSession> enableDemoMode(HttpSession session) {
        AuthSession demoSession = AuthSession.builder()
                .userId("DEMO_USER")
                .userName("Demo User")
                .email("demo@stockman.app")
                .isAuthenticated(true)
                .expiresAt(System.currentTimeMillis() + (24 * 60 * 60 * 1000))
                .build();
        
        session.setAttribute("auth", demoSession);
        session.setAttribute("demoMode", true);
        
        log.info("Demo mode enabled");
        return ResponseEntity.ok(demoSession);
    }
}
