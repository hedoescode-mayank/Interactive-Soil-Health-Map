package com.ishm.auth;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import io.micronaut.security.token.generator.TokenGenerator;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Controller("/api/auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    @Inject
    AuthService authService;

    @Inject
    TokenGenerator tokenGenerator;

    @Inject
    DataSource dataSource;

    /**
     * Register new farmer with postal code verification
     */
    @Post("/register")
    public HttpResponse<Map<String, Object>> register(@Body @Valid RegistrationRequest request) {
        LOG.info("Registration attempt for user: {}", request.getUsername());
        try {
            String status = authService.registerFarmer(request);
            if (!"SUCCESS".equals(status)) {
                return HttpResponse.badRequest(Map.of("error", status));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Registration successful! You can now login.");
            LOG.info("Registration successful for user: {}", request.getUsername());
            return HttpResponse.ok(response);

        } catch (SQLException e) {
            LOG.error("Registration error", e);
            return HttpResponse.serverError(Map.of("error", "Database error during registration: " + e.getMessage()));
        }
    }

    /**
     * Login with username and password
     */
    @Post("/login")
    public HttpResponse<Map<String, Object>> login(@Body @Valid LoginRequest request) {
        LOG.info("Login attempt for user: {}", request.getUsername());
        try {
            Optional<AuthService.UserContext> userOpt = authService.authenticate(request.getUsername(), request.getPassword());

            if (userOpt.isPresent()) {
                AuthService.UserContext user = userOpt.get();

                // Generate JWT token
                Map<String, Object> claims = new HashMap<>();
                claims.put("sub", user.username);
                claims.put("farmerId", user.farmerId);
                claims.put("district", user.district);
                claims.put("state", user.state);

                Optional<String> token = tokenGenerator.generateToken(claims);

                if (token.isPresent()) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("token", token.get());
                    response.put("farmer", Map.of(
                            "id", user.farmerId,
                            "username", user.username,
                            "postalCode", user.postalCode,
                            "district", user.district,
                            "state", user.state,
                            "fullName", user.fullName
                    ));

                    LOG.info("User {} logged in successfully", user.username);
                    return HttpResponse.ok(response);
                }
            }

            LOG.warn("Login failed for user: {}", request.getUsername());
            return HttpResponse.unauthorized().body(Map.of("error", "Invalid username or password"));

        } catch (SQLException e) {
            LOG.error("Login error", e);
            return HttpResponse.serverError(Map.of("error", "Login failed: " + e.getMessage()));
        }
    }

    /**
     * Verify token and get farmer details
     */
    @Get("/verify")
    public HttpResponse<Map<String, Object>> verifyToken(@Header("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return HttpResponse.unauthorized().body(Map.of("error", "No token provided"));
        }
        return HttpResponse.ok(Map.of("valid", true));
    }

    /**
     * Get specific profile and soil data for logged-in farmer
     */
    @Get("/me")
    public HttpResponse<Map<String, Object>> getMe(java.security.Principal principal) {
        try {
            if (principal == null || principal.getName() == null) {
                return HttpResponse.unauthorized();
            }
            
            String query = "SELECT * FROM vw_farmer_soil_data WHERE username = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, principal.getName());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("id", rs.getLong("farmer_id"));
                    data.put("username", rs.getString("username"));
                    data.put("district_name", rs.getString("district"));
                    data.put("state_name", rs.getString("state"));
                    data.put("postal_code", rs.getString("postal_code"));
                    data.put("full_name", rs.getString("full_name"));
                    
                    double n = rs.getObject("nitrogen_val") != null ? rs.getDouble("nitrogen_val") : 0.0;
                    double p = rs.getObject("phosphorus_val") != null ? rs.getDouble("phosphorus_val") : 0.0;
                    double k = rs.getObject("potassium_val") != null ? rs.getDouble("potassium_val") : 0.0;
                    
                    data.put("nitrogen_avg", n);
                    data.put("phosphorus_avg", p);
                    data.put("potassium_avg", k);
                    data.put("ph_avg", rs.getObject("ph_val") != null ? rs.getDouble("ph_val") : 0.0);
                    
                    data.put("nitrogen_status", classifyN(n));
                    data.put("phosphorus_status", classifyP(p));
                    data.put("potassium_status", classifyK(k));
                    
                    return HttpResponse.ok(data);
                }
            }
            return HttpResponse.notFound(Map.of("error", "Farmer not found"));
        } catch (Exception e) {
            LOG.error("Error fetching farmer data", e);
            return HttpResponse.serverError(Map.of("error", "Database error: " + e.getMessage()));
        }
    }
    
    private String classifyN(double val) { return val < 280 ? "Low" : (val <= 560 ? "Medium" : "High"); }
    private String classifyP(double val) { return val < 10 ? "Low" : (val <= 25 ? "Medium" : "High"); }
    private String classifyK(double val) { return val < 110 ? "Low" : (val <= 280 ? "Medium" : "High"); }

    // Request classes
    public static class RegistrationRequest {
        @NotBlank @Size(min = 3, max = 50)
        private String username;
        
        @NotBlank @Size(min = 6)
        private String password;
        
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Postal code must be 6 digits")
        private String postalCode;
        
        @NotBlank
        private String fullName;
        
        @Pattern(regexp = "^[0-9]{10}$", message = "Phone must be 10 digits")
        private String phone;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getPostalCode() { return postalCode; }
        public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }

    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}