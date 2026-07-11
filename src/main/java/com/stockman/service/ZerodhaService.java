package com.stockman.service;

import com.stockman.config.ZerodhaConfig;
import com.stockman.model.AuthSession;
import com.stockman.model.Holding;
import com.stockman.model.Position;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ZerodhaService {

    private final ZerodhaConfig zerodhaConfig;
    private final ConcurrentHashMap<String, KiteConnect> kiteConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthSession> sessions = new ConcurrentHashMap<>();
    /** Always points to the most recently authenticated KiteConnect instance. */
    private volatile KiteConnect lastAuthenticated;

    public ZerodhaService(ZerodhaConfig zerodhaConfig) {
        this.zerodhaConfig = zerodhaConfig;
    }

    public String getLoginUrl() {
        return zerodhaConfig.getLoginUrl();
    }

    public AuthSession authenticate(String requestToken, String sessionId) {
        try {
            KiteConnect kiteConnect = new KiteConnect(zerodhaConfig.getApiKey());
            User user = kiteConnect.generateSession(requestToken, zerodhaConfig.getApiSecret());
            
            kiteConnect.setAccessToken(user.accessToken);
            kiteConnect.setPublicToken(user.publicToken);
            
            AuthSession session = AuthSession.builder()
                    .userId(user.userId)
                    .userName(user.userName)
                    .email(user.email)
                    .accessToken(user.accessToken)
                    .publicToken(user.publicToken)
                    .refreshToken(user.refreshToken)
                    .isAuthenticated(true)
                    .expiresAt(System.currentTimeMillis() + (24 * 60 * 60 * 1000)) // 24 hours
                    .build();
            
            kiteConnections.put(sessionId, kiteConnect);
            sessions.put(sessionId, session);
            lastAuthenticated = kiteConnect;
            
            log.info("Successfully authenticated user: {}", user.userId);
            return session;
            
        } catch (KiteException | IOException e) {
            log.error("Authentication failed: {}", e.getMessage());
            return AuthSession.builder()
                    .isAuthenticated(false)
                    .build();
        }
    }

    public AuthSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public List<Holding> getHoldings(String sessionId) {
        KiteConnect kite = kiteConnections.get(sessionId);
        if (kite == null) {
            log.warn("No active connection for session: {}", sessionId);
            return new ArrayList<>();
        }

        try {
            List<com.zerodhatech.models.Holding> zerodhaHoldings = kite.getHoldings();
            return zerodhaHoldings.stream()
                    .map(this::convertHolding)
                    .toList();
        } catch (KiteException | IOException e) {
            log.error("Failed to fetch holdings: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<Position> getPositions(String sessionId) {
        KiteConnect kite = kiteConnections.get(sessionId);
        if (kite == null) {
            log.warn("No active connection for session: {}", sessionId);
            return new ArrayList<>();
        }

        try {
            var zerodhaPositions = kite.getPositions();
            List<Position> positions = new ArrayList<>();
            
            if (zerodhaPositions.get("net") != null) {
                for (var pos : zerodhaPositions.get("net")) {
                    positions.add(convertPosition(pos));
                }
            }
            return positions;
        } catch (KiteException | IOException e) {
            log.error("Failed to fetch positions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Returns the most recently authenticated KiteConnect instance, or null if no session exists.
     * The volatile field ensures the scanner always sees the latest authentication without
     * the non-deterministic ordering of ConcurrentHashMap.values().stream().findFirst().
     * Used by InstrumentRegistry and TickerService for background operations.
     */
    public KiteConnect getActiveKiteConnect() {
        return lastAuthenticated;
    }

    public void logout(String sessionId) {
        KiteConnect kite = kiteConnections.remove(sessionId);
        sessions.remove(sessionId);
        if (kite != null) {
            try {
                kite.logout();
                log.info("User logged out successfully");
            } catch (KiteException | IOException e) {
                log.error("Error during logout: {}", e.getMessage());
            }
        }
    }

    public KiteConnect getActiveKiteConnect() {
        return kiteConnections.values().stream()
                .findFirst()
                .orElse(null);
    }

    private Holding convertHolding(com.zerodhatech.models.Holding h) {
        BigDecimal avgPrice = BigDecimal.valueOf(h.averagePrice);
        BigDecimal lastPrice = BigDecimal.valueOf(h.lastPrice);
        BigDecimal closePrice = lastPrice; // Zerodha SDK doesn't expose closePrice in Holding model
        int qty = h.quantity;
        
        BigDecimal investedValue = avgPrice.multiply(BigDecimal.valueOf(qty));
        BigDecimal currentValue = lastPrice.multiply(BigDecimal.valueOf(qty));
        BigDecimal pnl = currentValue.subtract(investedValue);
        BigDecimal pnlPct = investedValue.compareTo(BigDecimal.ZERO) != 0 
                ? pnl.multiply(BigDecimal.valueOf(100)).divide(investedValue, 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        
        BigDecimal dayChange = lastPrice.subtract(closePrice).multiply(BigDecimal.valueOf(qty));
        BigDecimal dayChangePct = closePrice.compareTo(BigDecimal.ZERO) != 0
                ? lastPrice.subtract(closePrice).multiply(BigDecimal.valueOf(100)).divide(closePrice, 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return Holding.builder()
                .tradingSymbol(h.tradingSymbol)
                .exchange(h.exchange)
                .isin(h.isin)
                .quantity(qty)
                .averagePrice(avgPrice)
                .lastPrice(lastPrice)
                .closePrice(closePrice)
                .pnl(pnl)
                .pnlPercentage(pnlPct)
                .dayChange(dayChange)
                .dayChangePercentage(dayChangePct)
                .investedValue(investedValue)
                .currentValue(currentValue)
                .build();
    }

    private Position convertPosition(com.zerodhatech.models.Position p) {
        return Position.builder()
                .tradingSymbol(p.tradingSymbol)
                .exchange(p.exchange)
                .product(p.product)
                .quantity(p.netQuantity)
                .overnightQuantity(p.overnightQuantity)
                .multiplier(p.multiplier != null ? p.multiplier.intValue() : 1)
                .averagePrice(BigDecimal.valueOf(p.averagePrice))
                .closePrice(BigDecimal.valueOf(p.closePrice))
                .lastPrice(BigDecimal.valueOf(p.lastPrice))
                .value(BigDecimal.valueOf(p.value))
                .pnl(BigDecimal.valueOf(p.pnl))
                .m2m(BigDecimal.valueOf(p.m2m))
                .unrealised(BigDecimal.valueOf(p.unrealised))
                .realised(BigDecimal.valueOf(p.realised))
                .buyQuantity(p.buyQuantity)
                .sellQuantity(p.sellQuantity)
                .buyPrice(BigDecimal.valueOf(p.buyPrice))
                .sellPrice(BigDecimal.valueOf(p.sellPrice))
                .buyValue(BigDecimal.valueOf(p.buyValue))
                .sellValue(BigDecimal.valueOf(p.sellValue))
                .build();
    }
}
