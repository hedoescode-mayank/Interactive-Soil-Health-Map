package com.ishm.auth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * All authentication + registration logic in one clean service.
 * Completely rewritten from scratch — no Micronaut Security dependency.
 */
@Singleton
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    @Inject DataSource dataSource;

    // ─── Registration ─────────────────────────────────────────────────────────

    public RegisterResult registerFarmer(String username, String password,
                                          String fullName, String phone,
                                          String postalCode,
                                          String securityQuestion, String securityAnswer)
            throws SQLException {

        // Basic validation
        username = username == null ? "" : username.trim();
        fullName = fullName == null ? "" : fullName.trim();
        postalCode = postalCode == null ? "" : postalCode.trim();

        if (username.length() < 3)   return RegisterResult.fail("Username must be at least 3 characters");
        if (username.length() > 50)  return RegisterResult.fail("Username too long (max 50)");
        if (!username.matches("[a-zA-Z0-9_]+")) return RegisterResult.fail("Username can only contain letters, digits and underscores");
        if (password == null || password.length() < 6) return RegisterResult.fail("Password must be at least 6 characters");
        if (fullName.isEmpty())       return RegisterResult.fail("Full name is required");
        if (!postalCode.matches("\\d{6}")) return RegisterResult.fail("Postal code must be exactly 6 digits");
        if (phone != null && !phone.isBlank() && !phone.matches("\\d{10}")) return RegisterResult.fail("Phone must be exactly 10 digits");
        if (securityQuestion == null || securityQuestion.isBlank()) return RegisterResult.fail("Security question is required for password reset");
        if (securityAnswer == null || securityAnswer.isBlank()) return RegisterResult.fail("Security answer is required for password reset");

        String finalPhone = (phone == null || phone.isBlank()) ? null : phone.trim();
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt(12));
        String hashedAnswer = BCrypt.hashpw(securityAnswer.toLowerCase().trim(), BCrypt.gensalt(10));

        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            // Check username uniqueness
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM farmers WHERE LOWER(username) = LOWER(?)")) {
                ps.setString(1, username);
                if (ps.executeQuery().next()) {
                    conn.rollback();
                    return RegisterResult.fail("Username already taken");
                }
            }

            // Pick a random district for the farm
            int districtId = 1;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT district_id FROM districts ORDER BY RANDOM() LIMIT 1")) {
                if (rs.next()) districtId = rs.getInt(1);
            }

            // Insert farmer with security Q&A
            long farmerId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO farmers (username, password_hash, full_name, phone, security_question, security_answer_hash) " +
                    "VALUES (?, ?, ?, ?, ?, ?) RETURNING farmer_id")) {
                ps.setString(1, username);
                ps.setString(2, hashedPassword);
                ps.setString(3, fullName);
                if (finalPhone != null) ps.setString(4, finalPhone); else ps.setNull(4, Types.VARCHAR);
                ps.setString(5, securityQuestion);
                ps.setString(6, hashedAnswer);
                ResultSet rs = ps.executeQuery();
                rs.next();
                farmerId = rs.getLong(1);
            }

            // Insert farm
            int farmId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO farms (farmer_id, district_id, postal_code) VALUES (?, ?, ?) RETURNING farm_id")) {
                ps.setLong(1, farmerId);
                ps.setInt(2, districtId);
                ps.setString(3, postalCode);
                ResultSet rs = ps.executeQuery();
                rs.next();
                farmId = rs.getInt(1);
            }

            // Initial soil test data
            Random rand = new Random();
            int testId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO soil_tests (farm_id, nitrogen_val, phosphorus_val, potassium_val, ph_val) " +
                    "VALUES (?, ?, ?, ?, ?) RETURNING test_id")) {
                ps.setInt(1, farmId);
                ps.setDouble(2, 150 + rand.nextDouble() * 300);
                ps.setDouble(3, 10 + rand.nextDouble() * 40);
                ps.setDouble(4, 100 + rand.nextDouble() * 250);
                ps.setDouble(5, 5.5 + rand.nextDouble() * 2.5);
                ResultSet rs = ps.executeQuery();
                rs.next();
                testId = rs.getInt(1);
            }

            // Fertilizer recommendation
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO fertilizer_recommendations (test_id, crop_id, urea_dose, dap_dose, mop_dose) " +
                    "VALUES (?, 1, ?, ?, ?)")) {
                ps.setInt(1, testId);
                ps.setDouble(2, 40 + rand.nextDouble() * 60);
                ps.setDouble(3, 15 + rand.nextDouble() * 30);
                ps.setDouble(4, 20 + rand.nextDouble() * 40);
                ps.executeUpdate();
            }

            conn.commit();
            LOG.info("Farmer registered: {}", username);
            return RegisterResult.ok();

        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            LOG.error("Registration error for {}: {}", username, e.getMessage(), e);
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                return RegisterResult.fail("Username already taken");
            }
            throw e instanceof SQLException ? (SQLException) e : new SQLException(e);
        } finally {
            if (conn != null) { try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {} }
        }
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    public Optional<UserContext> login(String username, String password) throws SQLException {
        if (username == null || password == null) return Optional.empty();

        String sql = "SELECT f.farmer_id, f.username, f.password_hash, f.full_name, " +
                     "       fm.postal_code, d.name AS district, s.name AS state " +
                     "FROM farmers f " +
                     "JOIN farms fm ON fm.farmer_id = f.farmer_id " +
                     "JOIN districts d ON d.district_id = fm.district_id " +
                     "JOIN states s ON s.state_id = d.state_id " +
                     "WHERE LOWER(f.username) = LOWER(?) AND f.is_active = true " +
                     "LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.trim());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();

            String storedHash = rs.getString("password_hash");
            if (!BCrypt.checkpw(password, storedHash)) {
                LOG.warn("Bad password for user: {}", username);
                return Optional.empty();
            }

            // Update last_login
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE farmers SET last_login = CURRENT_TIMESTAMP WHERE farmer_id = ?")) {
                upd.setLong(1, rs.getLong("farmer_id"));
                upd.executeUpdate();
            }

            return Optional.of(new UserContext(
                    rs.getLong("farmer_id"),
                    rs.getString("username"),
                    rs.getString("full_name"),
                    rs.getString("district"),
                    rs.getString("state"),
                    rs.getString("postal_code")
            ));
        }
    }

    // ─── Forgot Password (security question flow) ─────────────────────────────

    /** Returns the security question for a username, or empty if not found. */
    public Optional<String> getSecurityQuestion(String username) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT security_question FROM farmers WHERE LOWER(username) = LOWER(?) AND is_active = true")) {
            ps.setString(1, username.trim());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String q = rs.getString("security_question");
                return q != null ? Optional.of(q) : Optional.empty();
            }
            return Optional.empty();
        }
    }

    /**
     * Verify security answer, then generate a one-time reset token (UUID).
     * Returns the reset token on success, empty on wrong answer.
     */
    public Optional<String> verifySecurityAnswer(String username, String answer) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String hash;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT security_answer_hash FROM farmers WHERE LOWER(username) = LOWER(?) AND is_active = true")) {
                ps.setString(1, username.trim());
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) return Optional.empty();
                hash = rs.getString("security_answer_hash");
            }
            if (hash == null || !BCrypt.checkpw(answer.toLowerCase().trim(), hash)) {
                return Optional.empty();
            }

            // Generate reset token valid 15 min
            String token = UUID.randomUUID().toString().replace("-", "");
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE farmers SET reset_token = ?, reset_token_expires = NOW() + INTERVAL '15 minutes' " +
                    "WHERE LOWER(username) = LOWER(?)")) {
                ps.setString(1, token);
                ps.setString(2, username.trim());
                ps.executeUpdate();
            }
            return Optional.of(token);
        }
    }

    /**
     * Reset password using the one-time reset token.
     * Returns true on success.
     */
    public boolean resetPassword(String username, String resetToken, String newPassword) throws SQLException {
        if (newPassword == null || newPassword.length() < 6) return false;

        try (Connection conn = dataSource.getConnection()) {
            // Verify token exists and is not expired
            boolean valid;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM farmers WHERE LOWER(username) = LOWER(?) " +
                    "AND reset_token = ? AND reset_token_expires > NOW()")) {
                ps.setString(1, username.trim());
                ps.setString(2, resetToken);
                valid = ps.executeQuery().next();
            }
            if (!valid) return false;

            String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE farmers SET password_hash = ?, reset_token = NULL, reset_token_expires = NULL " +
                    "WHERE LOWER(username) = LOWER(?)")) {
                ps.setString(1, newHash);
                ps.setString(2, username.trim());
                return ps.executeUpdate() > 0;
            }
        }
    }

    // ─── Change password (while logged in) ───────────────────────────────────

    public boolean changePassword(String username, String oldPassword, String newPassword) throws SQLException {
        if (newPassword == null || newPassword.length() < 6) return false;

        try (Connection conn = dataSource.getConnection()) {
            String storedHash;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT password_hash FROM farmers WHERE LOWER(username) = LOWER(?)")) {
                ps.setString(1, username);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) return false;
                storedHash = rs.getString("password_hash");
            }
            if (!BCrypt.checkpw(oldPassword, storedHash)) return false;

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE farmers SET password_hash = ? WHERE LOWER(username) = LOWER(?)")) {
                ps.setString(1, BCrypt.hashpw(newPassword, BCrypt.gensalt(12)));
                ps.setString(2, username);
                return ps.executeUpdate() > 0;
            }
        }
    }

    // ─── Get farmer profile ───────────────────────────────────────────────────

    public Optional<FarmerProfile> getProfile(String username) throws SQLException {
        String sql = "SELECT f.farmer_id, f.username, f.full_name, f.phone, f.created_at, " +
                     "       fm.postal_code, d.name AS district, s.name AS state, " +
                     "       st.nitrogen_val, st.phosphorus_val, st.potassium_val, st.ph_val " +
                     "FROM farmers f " +
                     "JOIN farms fm ON fm.farmer_id = f.farmer_id " +
                     "JOIN districts d ON d.district_id = fm.district_id " +
                     "JOIN states s ON s.state_id = d.state_id " +
                     "LEFT JOIN LATERAL (" +
                     "  SELECT nitrogen_val, phosphorus_val, potassium_val, ph_val " +
                     "  FROM soil_tests WHERE farm_id = fm.farm_id " +
                     "  ORDER BY test_date DESC, test_id DESC LIMIT 1" +
                     ") st ON true " +
                     "WHERE LOWER(f.username) = LOWER(?) LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();

            return Optional.of(new FarmerProfile(
                    rs.getLong("farmer_id"),
                    rs.getString("username"),
                    rs.getString("full_name"),
                    rs.getString("phone"),
                    rs.getString("postal_code"),
                    rs.getString("district"),
                    rs.getString("state"),
                    rs.getObject("nitrogen_val") != null ? rs.getDouble("nitrogen_val") : 0,
                    rs.getObject("phosphorus_val") != null ? rs.getDouble("phosphorus_val") : 0,
                    rs.getObject("potassium_val") != null ? rs.getDouble("potassium_val") : 0,
                    rs.getObject("ph_val") != null ? rs.getDouble("ph_val") : 0
            ));
        }
    }

    // ─── Result types ─────────────────────────────────────────────────────────

    public record UserContext(long farmerId, String username, String fullName,
                              String district, String state, String postalCode) {}

    public record FarmerProfile(long farmerId, String username, String fullName, String phone,
                                String postalCode, String district, String state,
                                double nitrogen, double phosphorus, double potassium, double ph) {}

    public record RegisterResult(boolean success, String error) {
        static RegisterResult ok() { return new RegisterResult(true, null); }
        static RegisterResult fail(String msg) { return new RegisterResult(false, msg); }
    }
}
