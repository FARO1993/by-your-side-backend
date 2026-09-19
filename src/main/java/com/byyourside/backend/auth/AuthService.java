package com.byyourside.backend.auth;

import com.byyourside.backend.auth.dto.AuthResponse;
import com.byyourside.backend.auth.dto.LoginRequest;
import com.byyourside.backend.auth.dto.RegisterRequest;
import com.byyourside.backend.security.JwtService;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.UserRole;
import com.byyourside.backend.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        User user = User.builder()
                .username(generateUniqueUsername(request.displayName()))
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName())
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        UserPrincipal principal = new UserPrincipal(user);
        String token = jwtService.generateToken(principal);

        return new AuthResponse(token, user.getUsername(), user.getRole().name());
    }

    public AuthResponse login(LoginRequest request) {
        // Se resuelve el email a un username interno ANTES de autenticar --
        // asi el resto de la cadena de Spring Security (CustomUserDetailsService,
        // JwtAuthenticationFilter, WebSocket) sigue funcionando exactamente
        // igual que siempre, basada en username, sin tocar nada mas.
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(user.getUsername(), request.password())
        );

        UserPrincipal principal = new UserPrincipal(user);
        String token = jwtService.generateToken(principal);

        return new AuthResponse(token, user.getUsername(), user.getRole().name());
    }

    // Genera un username interno a partir del nombre a mostrar -- el usuario
    // nunca lo elige ni lo ve como campo de formulario, pero sigue existiendo
    // adentro (login sigue siendo por username a nivel de Spring Security,
    // WebSocket lo usa como identidad de sesion, @handle en el perfil, etc).
    private String generateUniqueUsername(String displayName) {
        String base = slugify(displayName);
        if (base.isBlank()) {
            base = "usuario";
        }

        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    private String slugify(String value) {
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = withoutAccents.toLowerCase().replaceAll("[^a-z0-9]+", "");
        return slug.length() > 24 ? slug.substring(0, 24) : slug;
    }
}