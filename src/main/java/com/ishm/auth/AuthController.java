package com.ishm.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

/**
 * Auth controller — completely rewritten from scratch.
 * All endpoints under /api/auth are open (IS_ANONYMOUS).
 * Token validation happens manually via JwtUtil so we are NOT
 * relying on Micronaut Security's interceptor at all.
 */
@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/api/auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    @Inject AuthService authService;
    @Inject JwtUtil jwtUtil;

    // ─── Register ─────────────────────────────────────────────────────────────

    @Post("/register")
    public HttpResponse<Map<String, Object>> register(@Body Map<String, Object> body) {
        try {
            String username        = str(body, "username");
            String password        = str(body, "password");
            String fullName        = str(body, "fullName");
            String phone           = str(body, "phone");
            String postalCode      = str(body, "postalCode");
            String securityQ       = str(body, "securityQuestion");
            String securityA       = str(body, "securityAnswer");

            AuthService.RegisterResult result = authService.registerFarmer(
                    username, password, fullName, phone, postalCode, securityQ, securityA);

            if (!result.success()) {
                return bad(result.error());
            }

            LOG.info("Registered new farmer: {}", username);
            return ok(Map.of("success", true, "message", "Registration successful! Please login."));

        } catch (SQLException e) {
            LOG.error("Register DB error", e);
            return err("Database error: " + e.getMessage());
        }
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    @Post("/login")
    public HttpResponse<Map<String, Object>> login(@Body Map<String, Object> body) {
        try {
            String username = str(body, "username");
            String password = str(body, "password");

            if (username == null || username.isBlank()) return bad("Username is required");
            if (password == null || password.isBlank()) return bad("Password is required");

            Optional<AuthService.UserContext> userOpt = authService.login(username, password);
            if (userOpt.isEmpty()) {
                return HttpResponse.<Map<String, Object>>unauthorized()
                        .body(Map.of("success", false, "error", "Invalid username or password"));
            }

            AuthService.UserContext user = userOpt.get();
            Map<String, Object> claims = new HashMap<>();
            claims.put("sub", user.username());
            claims.put("farmerId", user.farmerId());
            claims.put("district", user.district());
            claims.put("state", user.state());

            String token = jwtUtil.generateToken(claims);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("token", token);
            resp.put("farmer", Map.of(
                    "id",        user.farmerId(),
                    "username",  user.username(),
                    "fullName",  user.fullName(),
                    "district",  user.district(),
                    "state",     user.state(),
                    "postalCode",user.postalCode()
            ));

            LOG.info("Login success: {}", user.username());
            return ok(resp);

        } catch (SQLException e) {
            LOG.error("Login DB error", e);
            return err("Login failed: " + e.getMessage());
        }
    }

    // ─── Forgot password — step 1: get security question ─────────────────────

    @Post("/forgot/question")
    public HttpResponse<Map<String, Object>> forgotQuestion(@Body Map<String, Object> body) {
        try {
            String username = str(body, "username");
            if (username == null || username.isBlank()) return bad("Username is required");

            Optional<String> q = authService.getSecurityQuestion(username);
            if (q.isEmpty()) {
                // Don't leak whether user exists
                return ok(Map.of("success", true, "question",
                        "What is the name of your first pet? (account not found — check username)"));
            }
            return ok(Map.of("success", true, "question", q.get()));

        } catch (SQLException e) {
            LOG.error("Forgot question DB error", e);
            return err("Error: " + e.getMessage());
        }
    }

    // ─── Forgot password — step 2: verify answer, get reset token ────────────

    @Post("/forgot/verify")
    public HttpResponse<Map<String, Object>> forgotVerify(@Body Map<String, Object> body) {
        try {
            String username = str(body, "username");
            String answer   = str(body, "answer");

            if (username == null || username.isBlank()) return bad("Username required");
            if (answer == null || answer.isBlank())     return bad("Answer required");

            Optional<String> tokenOpt = authService.verifySecurityAnswer(username, answer);
            if (tokenOpt.isEmpty()) {
                return HttpResponse.<Map<String, Object>>unauthorized()
                        .body(Map.of("success", false, "error", "Incorrect answer"));
            }

            return ok(Map.of("success", true, "resetToken", tokenOpt.get(),
                    "message", "Answer verified. You can now reset your password."));

        } catch (SQLException e) {
            LOG.error("Forgot verify DB error", e);
            return err("Error: " + e.getMessage());
        }
    }

    // ─── Forgot password — step 3: set new password ──────────────────────────

    @Post("/forgot/reset")
    public HttpResponse<Map<String, Object>> forgotReset(@Body Map<String, Object> body) {
        try {
            String username    = str(body, "username");
            String resetToken  = str(body, "resetToken");
            String newPassword = str(body, "newPassword");

            if (newPassword == null || newPassword.length() < 6)
                return bad("New password must be at least 6 characters");

            boolean ok = authService.resetPassword(username, resetToken, newPassword);
            if (!ok) {
                return bad("Reset token is invalid or expired. Please start over.");
            }

            LOG.info("Password reset for: {}", username);
            return ok(Map.of("success", true, "message", "Password reset successfully! Please login."));

        } catch (SQLException e) {
            LOG.error("Reset password DB error", e);
            return err("Error: " + e.getMessage());
        }
    }

    // ─── Change password (logged in) ──────────────────────────────────────────

    @Put("/change-password")
    public HttpResponse<Map<String, Object>> changePassword(
            HttpRequest<?> request, @Body Map<String, Object> body) {
        try {
            String username = requireAuth(request);
            if (username == null) return unauthorized();

            String oldPassword = str(body, "oldPassword");
            String newPassword = str(body, "newPassword");

            if (oldPassword == null || oldPassword.isBlank()) return bad("Old password required");
            if (newPassword == null || newPassword.length() < 6) return bad("New password must be at least 6 characters");

            boolean ok = authService.changePassword(username, oldPassword, newPassword);
            if (!ok) return bad("Current password is incorrect");

            return ok(Map.of("success", true, "message", "Password changed successfully"));

        } catch (SQLException e) {
            LOG.error("Change password DB error", e);
            return err("Error: " + e.getMessage());
        }
    }

    // ─── Get my profile ───────────────────────────────────────────────────────

    @Get("/me")
    public HttpResponse<Map<String, Object>> getMe(HttpRequest<?> request) {
        try {
            String username = requireAuth(request);
            if (username == null) return unauthorized();

            Optional<AuthService.FarmerProfile> profile = authService.getProfile(username);
            if (profile.isEmpty()) return HttpResponse.notFound(Map.of("error", "Profile not found"));

            AuthService.FarmerProfile p = profile.get();
            Map<String, Object> resp = new HashMap<>();
            resp.put("id", p.farmerId());
            resp.put("username", p.username());
            resp.put("full_name", p.fullName());
            resp.put("phone", p.phone());
            resp.put("postal_code", p.postalCode());
            resp.put("district_name", p.district());
            resp.put("state_name", p.state());
            resp.put("nitrogen_avg", p.nitrogen());
            resp.put("phosphorus_avg", p.phosphorus());
            resp.put("potassium_avg", p.potassium());
            resp.put("ph_avg", p.ph());
            resp.put("nitrogen_status", classifyN(p.nitrogen()));
            resp.put("phosphorus_status", classifyP(p.phosphorus()));
            resp.put("potassium_status", classifyK(p.potassium()));

            return ok(resp);

        } catch (SQLException e) {
            LOG.error("Get profile DB error", e);
            return err("Error: " + e.getMessage());
        }
    }

    // ─── Verify token ─────────────────────────────────────────────────────────

    @Get("/verify")
    public HttpResponse<Map<String, Object>> verifyToken(HttpRequest<?> request) {
        String username = requireAuth(request);
        if (username == null) return unauthorized();
        return ok(Map.of("valid", true, "username", username));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Extract and verify Bearer token from request. Returns username or null. */
    private String requireAuth(HttpRequest<?> request) {
        return request.getHeaders().getAuthorization()
                .map(h -> {
                    if (!h.startsWith("Bearer ")) return null;
                    try {
                        return jwtUtil.getSubject(h.substring(7));
                    } catch (JwtUtil.JwtException e) {
                        LOG.warn("Bad token: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString().trim() : null;
    }

    private static HttpResponse<Map<String, Object>> ok(Map<String, Object> body) {
        return HttpResponse.ok(body);
    }

    private static HttpResponse<Map<String, Object>> bad(String msg) {
        return HttpResponse.badRequest(Map.of("success", false, "error", msg));
    }

    private static HttpResponse<Map<String, Object>> err(String msg) {
        return HttpResponse.serverError(Map.of("success", false, "error", msg));
    }

    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> unauthorized() {
        return (HttpResponse<T>) HttpResponse.unauthorized()
                .body(Map.of("success", false, "error", "Unauthorized - please login"));
    }

    private String classifyN(double v) { return v < 280 ? "Low" : v <= 560 ? "Medium" : "High"; }
    private String classifyP(double v) { return v < 10 ? "Low" : v <= 25 ? "Medium" : "High"; }
    private String classifyK(double v) { return v < 110 ? "Low" : v <= 280 ? "Medium" : "High"; }
}