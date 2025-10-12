package com.example.shiftv1.auth;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public AuthController(UserRepository userRepository, 
                         PasswordEncoder passwordEncoder,
                         AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        try {
            logger.info("ユーザー登録を開始します: {}", request.username());

            // ユーザー名の重複チェック
            if (userRepository.existsByUsername(request.username())) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "ユーザー名が既に使用されています");
                return ResponseEntity.badRequest().body(response);
            }

            // メールアドレスの重複チェック
            if (request.email() != null && userRepository.existsByEmail(request.email())) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "メールアドレスが既に使用されています");
                return ResponseEntity.badRequest().body(response);
            }

            // 新しいユーザーを作成
            User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.email(),
                User.Role.USER
            );

            User savedUser = userRepository.save(user);
            
            logger.info("ユーザー登録が完了しました: {}", savedUser.getUsername());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "ユーザー登録が完了しました");
            response.put("userId", savedUser.getId());
            response.put("username", savedUser.getUsername());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            logger.error("ユーザー登録でエラーが発生しました", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "ユーザー登録中にエラーが発生しました");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        try {
            logger.info("ログインを開始します: {}", request.username());

            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            
            User user = userRepository.findByUsername(request.username()).orElseThrow();
            user.setLastLogin(LocalDateTime.now());
            userRepository.save(user);

            logger.info("ログインが完了しました: {}", user.getUsername());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "ログインが完了しました");
            response.put("username", user.getUsername());
            response.put("role", user.getRole().name());
            response.put("lastLogin", user.getLastLogin());
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("ログインでエラーが発生しました", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "ユーザー名またはパスワードが正しくありません");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        try {
            SecurityContextHolder.clearContext();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "ログアウトが完了しました");
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("ログアウトでエラーが発生しました", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "ログアウト中にエラーが発生しました");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            if (authentication == null || !authentication.isAuthenticated() || 
                "anonymousUser".equals(authentication.getPrincipal())) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "認証されていません");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            User user = userRepository.findByUsername(authentication.getName()).orElseThrow();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("username", user.getUsername());
            response.put("email", user.getEmail());
            response.put("role", user.getRole().name());
            response.put("createdAt", user.getCreatedAt());
            response.put("lastLogin", user.getLastLogin());
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("現在のユーザー情報取得でエラーが発生しました", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "ユーザー情報の取得中にエラーが発生しました");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    public record RegisterRequest(
            String username,
            String password,
            String email
    ) {}

    public record LoginRequest(
            String username,
            String password
    ) {}
}

