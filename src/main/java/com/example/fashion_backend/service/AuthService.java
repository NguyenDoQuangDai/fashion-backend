package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.auth.AuthResponse;
import com.example.fashion_backend.dto.auth.ForgotRequest;
import com.example.fashion_backend.dto.auth.GoogleAuthRequest;
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
import com.example.fashion_backend.entity.UserIdentityEntity;
import com.example.fashion_backend.repository.EmailVerificationTokenRepository;
import com.example.fashion_backend.repository.PasswordResetTokenRepository;
import com.example.fashion_backend.repository.RefreshTokenRepository;
import com.example.fashion_backend.repository.RoleRepository;
import com.example.fashion_backend.repository.UserRepository;
import com.example.fashion_backend.repository.UserIdentityRepository;
import com.example.fashion_backend.security.JwtService;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
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
    private final UserIdentityRepository userIdentityRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final CartService cartService;
    private final GoogleIdTokenService googleIdTokenService;
    private final long refreshDays;
    private final String frontendBaseUrl;

    public AuthService(
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            MailService mailService,
            CartService cartService,
            GoogleIdTokenService googleIdTokenService,
            @Value("${app.jwt.refresh-days:7}") long refreshDays,
            @Value("${app.backend-base-url:http://localhost:8080}") String backendBaseUrl,
            @Value("${app.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.cartService = cartService;
        this.googleIdTokenService = googleIdTokenService;
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
        String customerName = (user.getFullName() != null && !user.getFullName().isBlank()) ? user.getFullName() : "Quý khách";

        String registerSubject = "✨ Chào mừng bạn đến với 5SMan - Xác thực tài khoản của bạn";
        String registerBody = """
        <!DOCTYPE html>
        <html>
        <body style="margin:0; padding:0; background-color:#f4f5f7; font-family:'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;">
          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background-color:#f4f5f7; padding:40px 0;">
            <tr>
              <td align="center">
                <table role="presentation" width="600" cellspacing="0" cellpadding="0" style="background-color:#ffffff; border-radius:12px; overflow:hidden; box-shadow:0 10px 25px rgba(0,0,0,0.08);">
                  <tr>
                    <td style="background-color:#0f172a; padding:30px; text-align:center;">
                      <h1 style="color:#ffffff; margin:0; font-size:26px; letter-spacing:3px; font-weight:800;">5SMAN</h1>
                      <p style="color:#94a3b8; margin:5px 0 0 0; font-size:12px; text-transform:uppercase; letter-spacing:1px;">Gentlemen's Fashion Style</p>
                    </td>
                  </tr>
                  <tr>
                    <td style="padding:40px 30px; color:#334155;">
                      <h2 style="margin-top:0; color:#0f172a; font-size:20px; text-align:center;">Xác thực tài khoản</h2>
                      <p style="font-size:15px; line-height:1.6;">Xin chào <strong>%s</strong>,</p>
                      <p style="font-size:15px; line-height:1.6;">Cảm ơn bạn đã đăng ký thành viên tại <strong>5SMan</strong>! Vui lòng bấm vào nút bên dưới để kích hoạt tài khoản của bạn và trải nghiệm mua sắm ngay lập tức.</p>
                      
                      <div style="text-align:center; margin:35px 0;">
                        <a href="%s" style="background-color:#0f172a; color:#ffffff; padding:14px 32px; border-radius:8px; text-decoration:none; font-weight:bold; font-size:15px; display:inline-block; box-shadow:0 4px 12px rgba(15,23,42,0.25);">XÁC THỰC TÀI KHOẢN</a>
                      </div>
                      
                      <p style="font-size:13px; color:#64748b; background-color:#f8fafc; padding:15px; border-radius:8px; border-left:4px solid #0f172a; line-height:1.5;">
                        ⚠️ <strong>Lưu ý:</strong> Đường dẫn này chỉ có hiệu lực trong vòng <strong>1 giờ</strong>.<br>
                        Nếu không bấm được nút trên, hãy sao chép liên kết này dán vào trình duyệt:<br>
                        <a href="%s" style="color:#2563eb; word-break:break-all;">%s</a>
                      </p>
                    </td>
                  </tr>
                  <tr>
                    <td style="background-color:#f8fafc; padding:20px; text-align:center; font-size:12px; color:#94a3b8; border-top:1px solid #e2e8f0;">
                      &copy; 5SMan Fashion Store. All rights reserved.
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """.formatted(customerName, verifyLink, verifyLink, verifyLink);

        mailService.send(user.getEmail(), registerSubject, registerBody);

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
        if (user.getPasswordHash() == null) {
          throw new IllegalArgumentException("Password login is not enabled for this account");
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
      public AuthResponse googleLogin(GoogleAuthRequest request) {
        GoogleIdToken.Payload payload = googleIdTokenService.verify(request.getCredential());
        String provider = "GOOGLE";
        UserIdentityEntity identity = userIdentityRepository
            .findByProviderAndProviderSubject(provider, payload.getSubject())
            .orElse(null);

        UserEntity user;
        if (identity != null) {
          user = identity.getUser();
        } else {
          String email = payload.getEmail().trim().toLowerCase();
          user = userRepository.findByEmail(email).orElse(null);
          if (user == null) {
            RoleEntity userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(createRole("USER")));
            user = new UserEntity();
            user.setEmail(email);
            user.setPasswordHash(null);
                Object nameClaim = payload.get("name");
                user.setFullName(nameClaim == null ? null : nameClaim.toString());
            user.setEnabled(true);
            user.setCreatedAt(OffsetDateTime.now());
            user.setUpdatedAt(OffsetDateTime.now());
            user.setRoles(List.of(userRole));
            user = userRepository.save(user);
          }

          identity = new UserIdentityEntity();
          identity.setUser(user);
          identity.setProvider(provider);
          identity.setProviderSubject(payload.getSubject());
          identity.setProviderEmail(email);
          identity.setCreatedAt(OffsetDateTime.now());
          identity.setUpdatedAt(OffsetDateTime.now());
          userIdentityRepository.save(identity);
        }

        if (user.getEnabled() == null || !user.getEnabled()) {
          throw new IllegalArgumentException("Account disabled");
        }
        if (request.getGuestCartId() != null && !request.getGuestCartId().isBlank()) {
          cartService.mergeGuestCartToUser(user.getId(), request.getGuestCartId());
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
        String customerName = (user.getFullName() != null && !user.getFullName().isBlank()) ? user.getFullName() : "Quý khách";

        String forgotSubject = "🔒 [5SMan] Yêu cầu đặt lại mật khẩu của bạn";
        String forgotBody = """
        <!DOCTYPE html>
        <html>
        <body style="margin:0; padding:0; background-color:#f4f5f7; font-family:'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;">
          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background-color:#f4f5f7; padding:40px 0;">
            <tr>
              <td align="center">
                <table role="presentation" width="600" cellspacing="0" cellpadding="0" style="background-color:#ffffff; border-radius:12px; overflow:hidden; box-shadow:0 10px 25px rgba(0,0,0,0.08);">
                  <tr>
                    <td style="background-color:#0f172a; padding:30px; text-align:center;">
                      <h1 style="color:#ffffff; margin:0; font-size:26px; letter-spacing:3px; font-weight:800;">5SMAN</h1>
                    </td>
                  </tr>
                  <tr>
                    <td style="padding:40px 30px; color:#334155;">
                      <h2 style="margin-top:0; color:#0f172a; font-size:20px; text-align:center;">Đặt lại mật khẩu</h2>
                      <p style="font-size:15px; line-height:1.6;">Xin chào <strong>%s</strong>,</p>
                      <p style="font-size:15px; line-height:1.6;">Chúng tôi đã nhận được yêu cầu thiết lập lại mật khẩu cho tài khoản 5SMan của bạn.</p>
                      
                      <div style="text-align:center; margin:35px 0;">
                        <a href="%s" style="background-color:#dc2626; color:#ffffff; padding:14px 32px; border-radius:8px; text-decoration:none; font-weight:bold; font-size:15px; display:inline-block; box-shadow:0 4px 12px rgba(220,38,38,0.25);">ĐẶT LẠI MẬT KHẨU</a>
                      </div>
                      
                      <p style="font-size:13px; color:#64748b; background-color:#f8fafc; padding:15px; border-radius:8px; border-left:4px solid #dc2626; line-height:1.5;">
                        🛡️ <strong>Bảo mật:</strong> Đường dẫn có hiệu lực trong <strong>1 giờ</strong>. Nếu bạn không yêu cầu đặt lại mật khẩu, xin hãy bỏ qua email này.
                      </p>
                    </td>
                  </tr>
                  <tr>
                    <td style="background-color:#f8fafc; padding:20px; text-align:center; font-size:12px; color:#94a3b8; border-top:1px solid #e2e8f0;">
                      &copy; 5SMan Fashion Store. All rights reserved.
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
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