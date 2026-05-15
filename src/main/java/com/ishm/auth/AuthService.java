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

@Singleton
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    @Inject
    DataSource dataSource;

    public String registerFarmer(AuthController.RegistrationRequest request) throws SQLException {
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false); // Start transaction

            // 1. Register farmer and farm using Stored Function
            String callFunc = "SELECT fn_register_farmer_full(?, ?, ?, ?, ?, ?)";
            
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
                        conn.rollback();
                        return status;
                    }
                }
            }

            // 2. Generate initial random soil test data
            String getFids = "SELECT farm_id FROM farms fm JOIN farmers f ON f.farmer_id = fm.farmer_id WHERE f.username = ?";
            int farmId;
            try (PreparedStatement pstmt = conn.prepareStatement(getFids)) {
                pstmt.setString(1, request.getUsername());
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    farmId = rs.getInt("farm_id");
                } else {
                    throw new SQLException("Farm not created for user");
                }
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

            // 3. Generate initial random fertilizer recommendation
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

            conn.commit(); // Commit transaction
            return "SUCCESS";

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    LOG.error("Error rolling back transaction", ex);
                }
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    LOG.error("Error closing connection", e);
                }
            }
        }
    }

    public Optional<UserContext> authenticate(String username, String password) throws SQLException {
        String query = "SELECT * FROM vw_farmer_soil_data WHERE username = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String storedHash = rs.getString("password_hash");

                if (BCrypt.checkpw(password, storedHash)) {
                    updateLastLogin(conn, rs.getLong("farmer_id"));
                    return Optional.of(new UserContext(
                            rs.getLong("farmer_id"),
                            rs.getString("username"),
                            rs.getString("district"),
                            rs.getString("state"),
                            rs.getString("postal_code"),
                            rs.getString("full_name")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private void updateLastLogin(Connection conn, Long farmerId) throws SQLException {
        String updateQuery = "UPDATE farmers SET last_login = CURRENT_TIMESTAMP WHERE farmer_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
            stmt.setLong(1, farmerId);
            stmt.executeUpdate();
        }
    }

    public boolean updatePassword(String username, String oldPassword, String newPassword) throws SQLException {
        // To be implemented
        return false;
    }

    public String hashPassword(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    public static class UserContext {
        public final Long farmerId;
        public final String username;
        public final String district;
        public final String state;
        public final String postalCode;
        public final String fullName;

        public UserContext(Long farmerId, String username, String district, String state, String postalCode, String fullName) {
            this.farmerId = farmerId;
            this.username = username;
            this.district = district;
            this.state = state;
            this.postalCode = postalCode;
            this.fullName = fullName;
        }
    }
}
