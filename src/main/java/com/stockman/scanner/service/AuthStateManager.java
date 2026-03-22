package com.stockman.scanner.service;

import com.stockman.scanner.event.SessionExpiryEvent;
import com.stockman.scanner.model.SignalEnums.AuthState;
import com.stockman.service.ZerodhaService;
import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the lifecycle state of the Zerodha session for the scanner subsystem.
 *
 * <p>Thread-safe state transitions via {@link AtomicReference}. Publishes
 * {@link SessionExpiryEvent} when the session degrades.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AuthStateManager {

    private final AtomicReference<AuthState> state = new AtomicReference<>(AuthState.UNAUTHENTICATED);
    private final ApplicationEventPublisher eventPublisher;

    /** Returns the current auth state. */
    public AuthState getState() {
        return state.get();
    }

    /** Returns {@code true} if the current state is {@link AuthState#ACTIVE}. */
    public boolean isActive() {
        return state.get() == AuthState.ACTIVE;
    }

    /**
     * Transitions to {@code newState}, logging and firing events only on actual changes.
     *
     * <p>Transitions to {@link AuthState#DEGRADED} publish a {@link SessionExpiryEvent}.
     */
    public void transitionTo(AuthState newState) {
        AuthState old = state.getAndSet(newState);
        if (old != newState) {
            log.info("Auth state: {} -> {}", old, newState);
            if (newState == AuthState.DEGRADED) {
                eventPublisher.publishEvent(new SessionExpiryEvent(this, "Session expired or invalidated"));
            }
        }
    }

    /**
     * Validates the current session by checking whether ZerodhaService has an active
     * {@link KiteConnect} instance. Updates state accordingly.
     *
     * @return {@code true} if a valid active session exists
     */
    public boolean validateSession(ZerodhaService zerodhaService) {
        KiteConnect kite = zerodhaService.getActiveKiteConnect();
        if (kite == null) {
            transitionTo(AuthState.UNAUTHENTICATED);
            return false;
        }
        transitionTo(AuthState.ACTIVE);
        return true;
    }
}
