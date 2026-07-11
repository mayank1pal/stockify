package com.stockman.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Set;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketBrokerConfig implements WebSocketMessageBrokerConfigurer {

    private static final Set<String> ALLOWED_DESTINATIONS = Set.of(
            "/topic/signals",
            "/topic/ai-enrichment",
            "/topic/scanner-status"
    );

    private final AlertConfig alertConfig;

    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        if (alertConfig.getWebsocket().isEnabled()) {
            registry.addEndpoint("/ws")
                    .setAllowedOriginPatterns(allowedOrigins.split(","))
                    .withSockJS();
        }
    }

    /**
     * Deny all client-initiated SEND commands; only allow subscriptions to
     * the explicitly allowed destinations. All other messages pass through
     * (e.g. CONNECT, DISCONNECT, HEARTBEAT).
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

                // Deny any client-initiated SEND
                if (accessor.getCommand() == StompCommand.SEND) {
                    return null;
                }

                // Restrict subscriptions to known destinations only
                if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
                    String dest = accessor.getDestination();
                    if (dest == null || !ALLOWED_DESTINATIONS.contains(dest)) {
                        return null;
                    }
                }

                return message;
            }
        });
    }
}
