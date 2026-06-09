package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.auth.AuthResponse;
import com.example.fashion_backend.dto.auth.ForgotRequest;
import com.example.fashion_backend.dto.auth.LoginRequest;
import com.example.fashion_backend.dto.auth.MessageResponse;
import com.example.fashion_backend.dto.auth.RefreshRequest;
import com.example.fashion_backend.dto.auth.RegisterRequest;
import com.example.fashion_backend.dto.auth.ResetRequest;
import com.example.fashion_backend.dto.auth.UserResponse;
import com.example.fashion_backend.entity.EmailVerificationTokenEntity;
import com.example.fashion_backend.entity.PasswordResetTokenEntity;
import com.example.fashion_backend.entity.RefreshTokenEntity;
import com.example.fashion_backend.entity.RoleEntity;
import com.example.fashion_backend.entity.UserEntity;
import com.example.fashion_backend.repository.EmailVerificationTokenRepository;
import com.example.fashion_backend.repository.PasswordResetTokenRepository;
import com.example.fashion_backend.repository.RefreshTokenRepository;
import com.example.fashion_backend.repository.RoleRepository;
import com.example.fashion_backend.repository.UserRepository;
import com.example.fashion_backend.security.JwtService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final CartService cartService;
    private final long refreshDays;
    private final String frontendBaseUrl;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            MailService mailService,
            CartService cartService,
            @Value("${app.jwt.refresh-days:7}") long refreshDays,
            @Value("${app.backend-base-url:http://localhost:8080}") String backendBaseUrl,
            @Value("${app.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.cartService = cartService;
        this.refreshDays = refreshDays;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists");
        }

        RoleEntity userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(createRole("USER")));

        UserEntity user = new UserEntity();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getFullName());
        user.setPhone(request.getPhone());
        user.setEnabled(false);
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setRoles(List.of(userRole));
        user = userRepository.save(user);

        if (request.getClientId() != null && !request.getClientId().isBlank()) {
            cartService.mergeGuestCartToUser(user.getId(), request.getClientId());
        }

        EmailVerificationTokenEntity verifyToken = new EmailVerificationTokenEntity();
        verifyToken.setUser(user);
        verifyToken.setToken(UUID.randomUUID().toString());
        verifyToken.setExpiresAt(OffsetDateTime.now().plusHours(1));
        verifyToken.setUsed(false);
        verifyToken.setCreatedAt(OffsetDateTime.now());
        emailVerificationTokenRepository.save(verifyToken);

        String verifyLink = frontendBaseUrl + "/verify?token=" + verifyToken.getToken();

        String customerName = (user.getFullName() != null && !user.getFullName().isBlank()) 
        ? user.getFullName() 
        : "Quý khách";

        String registerSubject = "✨ Chào mừng bạn đến với 5SMan - Xác thực tài khoản của bạn";
        String registerBody = """
        Xin chào %s,
        
        Cảm ơn bạn đã đăng ký thành viên tại 5SMan! 
        Để kích hoạt tài khoản và bắt đầu trải nghiệm không gian mua sắm thời trang của chúng tôi, bạn vui lòng nhấn vào liên kết xác thực bên dưới:
        
        🔗 %s
        
        (Nếu không thể click trực tiếp, bạn hãy sao chép liên kết trên và dán vào thanh địa chỉ của trình duyệt)
        
        ⚠️ Lưu ý: Vì lý do bảo mật, liên kết này chỉ có hiệu lực trong vòng 2 giờ.
        Nếu bạn không thực hiện yêu cầu này, xin vui lòng bỏ qua email.
        
        Trân trọng,
        Đội ngũ 5SMan.
        """.formatted(customerName, verifyLink);

        mailService.send(user.getEmail(), registerSubject, registerBody);

        //MessageResponse response = new MessageResponse("Please verify your email to activate account");
        AuthResponse authResponse = new AuthResponse();
        authResponse.setAccessToken(null);
        authResponse.setRefreshToken(null);
        authResponse.setUser(toUserResponse(user));
        return authResponse;
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (user.getEnabled() == null || !user.getEnabled()) {
            throw new IllegalArgumentException("Account not verified");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        if (request.getClientId() != null && !request.getClientId().isBlank()) {
            cartService.mergeGuestCartToUser(user.getId(), request.getClientId());
        }
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = createRefreshToken(user);
        return toAuthResponse(user, accessToken, refreshToken);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshTokenEntity token = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));
        if (Boolean.TRUE.equals(token.getRevoked())) {
            throw new IllegalArgumentException("Refresh token revoked");
        }
        if (token.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Refresh token expired");
        }
        token.setRevoked(true);
        refreshTokenRepository.save(token);

        UserEntity user = token.getUser();
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = createRefreshToken(user);
        return toAuthResponse(user, accessToken, refreshToken);
    }

    @Transactional
    public MessageResponse logout(RefreshRequest request) {
        refreshTokenRepository.findByToken(request.getRefreshToken())
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
        return new MessageResponse("Logged out");
    }

    @Transactional
    public MessageResponse forgot(ForgotRequest request) {
        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Email not found"));

        PasswordResetTokenEntity token = new PasswordResetTokenEntity();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(OffsetDateTime.now().plusHours(2));
        token.setUsed(false);
        token.setCreatedAt(OffsetDateTime.now());
        passwordResetTokenRepository.save(token);

        String resetLink = frontendBaseUrl + "/reset?token=" + token.getToken();
        
        // Lấy tên khách hàng, nếu không có thì gọi là "Quý khách"
        String customerName = (user.getFullName() != null && !user.getFullName().isBlank()) 
        ? user.getFullName() 
        : "Quý khách";

        String forgotSubject = "🔒 [5SMan] Yêu cầu đặt lại mật khẩu của bạn";
        String forgotBody = """
        Xin chào %s,
        
        Chúng tôi đã nhận được yêu cầu đặt lại mật khẩu cho tài khoản 5SMan gắn liền với email này. 
        Bạn vui lòng nhấn vào liên kết dưới đây để tiến hành thiết lập mật khẩu mới:
        
        🔗 %s
        
        ⚠️ Lưu ý: Đường dẫn này chỉ có hiệu lực trong vòng 1 giờ.
        
        🛡️ Khuyến cáo bảo mật: Nếu bạn không thực hiện yêu cầu đặt lại mật khẩu này, tài khoản của bạn vẫn an toàn và bạn hoàn toàn có thể bỏ qua email này. Không chia sẻ liên kết này với bất kỳ ai.
        
        Trân trọng,
        Đội ngũ bảo mật 5SMan.
            """.formatted(customerName, resetLink);

        mailService.send(user.getEmail(), forgotSubject, forgotBody);

        return new MessageResponse("Reset email sent");
    }

    @Transactional
    public MessageResponse reset(ResetRequest request) {
        PasswordResetTokenEntity token = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid reset token"));
        if (Boolean.TRUE.equals(token.getUsed())) {
            throw new IllegalArgumentException("Reset token used");
        }
        if (token.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Reset token expired");
        }
        UserEntity user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        token.setUsed(true);
        passwordResetTokenRepository.save(token);
        return new MessageResponse("Password updated");
    }

    @Transactional
    public MessageResponse verifyEmail(String tokenValue) {
        EmailVerificationTokenEntity token = emailVerificationTokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid verification token"));
        if (Boolean.TRUE.equals(token.getUsed())) {
            throw new IllegalArgumentException("Verification token used");
        }
        if (token.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Verification token expired");
        }
        UserEntity user = token.getUser();
        user.setEnabled(true);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        token.setUsed(true);
        emailVerificationTokenRepository.save(token);
        return new MessageResponse("Account verified");
    }

    private RoleEntity createRole(String name) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        return role;
    }

    private String createRefreshToken(UserEntity user) {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(OffsetDateTime.now().plusDays(refreshDays));
        token.setRevoked(false);
        token.setCreatedAt(OffsetDateTime.now());
        refreshTokenRepository.save(token);
        return token.getToken();
    }

    private AuthResponse toAuthResponse(UserEntity user, String accessToken, String refreshToken) {
        AuthResponse response = new AuthResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setUser(toUserResponse(user));
        return response;
    }

    private UserResponse toUserResponse(UserEntity user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId().toString());
        response.setEmail(user.getEmail());
        response.setFullName(user.getFullName());
        response.setRoles(user.getRoles() == null ? List.of() : user.getRoles().stream()
                .map(RoleEntity::getName)
                .toList());
        return response;
    }
}
