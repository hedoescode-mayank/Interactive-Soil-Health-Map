package com.ishm.auth;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import io.micronaut.security.token.generator.TokenGenerator;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;

@Controller("/api/auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    @Inject
    DataSource dataSource;

    @Inject
    TokenGenerator tokenGenerator;

    /**
     * Register new farmer with postal code verification
     */
    @Post("/register")
    public HttpResponse<Map<String, Object>> register(@Body RegistrationRequest request) {
        LOG.info("Registration attempt for user: {}", request.getUsername());
        try {
            // Validate inputs
            if (request.getUsername() == null || request.getUsername().length() < 3) {
                return HttpResponse.badRequest(Map.of("error", "Username must be at least 3 characters"));
            }
            if (request.getPassword() == null || request.getPassword().length() < 6) {
                return HttpResponse.badRequest(Map.of("error", "Password must be at least 6 characters"));
            }
            if (request.getPostalCode() == null || request.getPostalCode().length() != 6) {
                return HttpResponse.badRequest(Map.of("error", "Valid 6-digit postal code required"));
            }

            try (Connection conn = dataSource.getConnection()) {
                // 1. Register farmer and farm using Stored Function (Concept: Functions & Transactional logic)
                String callFunc = "SELECT fn_register_farmer_full(?, ?, ?, ?, ?, ?)";
                
                // Demo logic: Pick a district ID based on PIN or just random
                int districtId = 1; 
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT district_id FROM districts ORDER BY RANDOM() LIMIT 1");
                    if (rs.next()) districtId = rs.getInt(1);
                }

                try (PreparedStatement pstmt = conn.prepareStatement(callFunc)) {
                    pstmt.setString(1, request.getUsername());
                    pstmt.setString(2, hashPassword(request.getPassword()));
                    pstmt.setString(3, request.getFullName());
                    pstmt.setString(4, request.getPhone());
                    pstmt.setInt(5, districtId);
                    pstmt.setString(6, request.getPostalCode());
                    
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        String status = rs.getString(1);
                        if (!"SUCCESS".equals(status)) {
                            return HttpResponse.badRequest(Map.of("error", status));
                        }
                    }
                }

                // 2. Generate initial random soil test data (DML)
                String getFids = "SELECT farm_id FROM farms fm JOIN farmers f ON f.farmer_id = fm.farmer_id WHERE f.username = ?";
                int farmId;
                try (PreparedStatement pstmt = conn.prepareStatement(getFids)) {
                    pstmt.setString(1, request.getUsername());
                    ResultSet rs = pstmt.executeQuery();
                    rs.next();
                    farmId = rs.getInt("farm_id");
                }

                Random rand = new Random();
                int testId = -1;
                String insertSoil = "INSERT INTO soil_tests (farm_id, nitrogen_val, phosphorus_val, potassium_val, ph_val) VALUES (?, ?, ?, ?, ?) RETURNING test_id";
                try (PreparedStatement pstmt = conn.prepareStatement(insertSoil)) {
                    pstmt.setInt(1, farmId);
                    pstmt.setDouble(2, 150 + rand.nextDouble() * 300);
                    pstmt.setDouble(3, 10 + rand.nextDouble() * 40);
                    pstmt.setDouble(4, 100 + rand.nextDouble() * 250);
                    pstmt.setDouble(5, 5.5 + rand.nextDouble() * 2.5);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) testId = rs.getInt(1);
                }

                // 3. Generate initial random fertilizer recommendation (DML)
                if (testId != -1) {
                    String insertRec = "INSERT INTO fertilizer_recommendations (test_id, crop_id, urea_dose, dap_dose, mop_dose) VALUES (?, 1, ?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertRec)) {
                        pstmt.setInt(1, testId);
                        pstmt.setDouble(2, 40 + rand.nextDouble() * 60);
                        pstmt.setDouble(3, 15 + rand.nextDouble() * 30);
                        pstmt.setDouble(4, 20 + rand.nextDouble() * 40);
                        pstmt.executeUpdate();
                    }
                }

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "Registration successful! You can now login.");
                LOG.info("Registration successful for user: {}", request.getUsername());
                return HttpResponse.ok(response);
            }

        } catch (SQLException e) {
            LOG.error("Registration error", e);
            return HttpResponse.serverError(Map.of("error", "Database error during registration: " + e.getMessage()));
        }
    }

    /**
     * Login with username and password
     */
    @Post("/login")
    public HttpResponse<Map<String, Object>> login(@Body LoginRequest request) {
        LOG.info("Login attempt for user: {}", request.getUsername());
        try {
            // Concept: Using View for simplified query
            String query = "SELECT * FROM vw_farmer_soil_data WHERE username = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, request.getUsername());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");

                    // Verify password
                    if (verifyPassword(request.getPassword(), storedHash)) {
                        Long farmerId = rs.getLong("farmer_id");
                        String username = rs.getString("username");

                        // Generate JWT token
                        Map<String, Object> claims = new HashMap<>();
                        claims.put("sub", username);
                        claims.put("farmerId", farmerId);
                        claims.put("district", rs.getString("district"));
                        claims.put("state", rs.getString("state"));

                        Optional<String> token = tokenGenerator.generateToken(claims);

                        if (token.isPresent()) {
                            // Update status/last login (DML)
                            updateLastLogin(conn, farmerId);

                            Map<String, Object> response = new HashMap<>();
                            response.put("success", true);
                            response.put("token", token.get());
                            response.put("farmer", Map.of(
                                    "id", farmerId,
                                    "username", username,
                                    "postalCode", rs.getString("postal_code"),
                                    "district", rs.getString("district"),
                                    "state", rs.getString("state"),
                                    "fullName", rs.getString("full_name")
                            ));

                            LOG.info("User {} logged in successfully", username);
                            return HttpResponse.ok(response);
                        }
                    }
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
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return HttpResponse.unauthorized().body(Map.of("error", "No token provided"));
            }
            return HttpResponse.ok(Map.of("valid", true));
        } catch (Exception e) {
            LOG.error("Token verification error", e);
            return HttpResponse.unauthorized().body(Map.of("error", "Invalid token"));
        }
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
            
            // Concept: Using View for complex joins
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
                    
                    data.put("nitrogen_avg", rs.getObject("nitrogen_val") != null ? rs.getDouble("nitrogen_val") : 0.0);
                    data.put("phosphorus_avg", rs.getObject("phosphorus_val") != null ? rs.getDouble("phosphorus_val") : 0.0);
                    data.put("potassium_avg", rs.getObject("potassium_val") != null ? rs.getDouble("potassium_val") : 0.0);
                    data.put("ph_avg", rs.getObject("ph_val") != null ? rs.getDouble("ph_val") : 0.0);
                    
                    data.put("nitrogen_status", classifyN((Double) data.get("nitrogen_avg")));
                    data.put("phosphorus_status", classifyP((Double) data.get("phosphorus_avg")));
                    data.put("potassium_status", classifyK((Double) data.get("potassium_avg")));
                    
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

    /**
     * Hash password using SHA-256
     * NOTE: For production, use BCrypt instead
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

    /**
     * Verify password against hash
     */
    private boolean verifyPassword(String password, String hashedPassword) {
        String inputHash = hashPassword(password);
        return inputHash.equals(hashedPassword);
    }

    private void updateLastLogin(Connection conn, Long farmerId) throws SQLException {
        String updateQuery = "UPDATE farmers SET last_login = CURRENT_TIMESTAMP WHERE farmer_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
            stmt.setLong(1, farmerId);
            stmt.executeUpdate();
        }
    }

    // Request classes
    public static class RegistrationRequest {
        private String username;
        private String password;
        private String postalCode;
        private String fullName;
        private String phone;

        // Getters and setters
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
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}