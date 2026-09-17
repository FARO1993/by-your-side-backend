package com.byyourside.backend.websocket;

import com.byyourside.backend.security.CustomUserDetailsService;
import com.byyourside.backend.security.JwtService;
import com.byyourside.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

// Autentica la conexion STOMP en el frame CONNECT, no en el handshake HTTP
// del WebSocket -- un WebSocket nativo no permite headers custom en el
// handshake, asi que el token JWT viaja como header STOMP ("Authorization")
// dentro del primer frame CONNECT, que el cliente si puede setear libremente.
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                throw new MessagingException("Missing or malformed Authorization header on CONNECT");
            }

            String token = authHeader.substring(BEARER_PREFIX.length());
            String username = jwtService.extractUsername(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (!jwtService.isTokenValid(token, (UserPrincipal) userDetails)) {
                throw new MessagingException("Invalid or expired token");
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            accessor.setUser(authentication);
        }

        return message;
    }
}