package com.ishm.admin;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Service layer for admin operations including authentication,
 * dashboard statistics, farmer CRUD, soil data management, and audit logs.
 */
@Singleton
public class AdminService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminService.class);

    @Inject
    DataSource dataSource;

    // ==========================================
    // ADMIN AUTHENTICATION
    // ==========================================

    /**
     * Authenticate an admin user against the admins table.
     * @return Optional containing admin context if authentication succeeds
     */
    public Optional<AdminContext> authenticateAdmin(String username, String password) throws SQLException {
        String query = "SELECT admin_id, username, password_hash, full_name, email, role, is_active FROM admins WHERE username = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                if (!rs.getBoolean("is_active")) {
                    LOG.warn("Admin login attempt for deactivated account: {}", username);
                    return Optional.empty();
                }

                String storedHash = rs.getString("password_hash");
                if (BCrypt.checkpw(password, storedHash)) {
                    // Update last login
                    updateAdminLastLogin(conn, rs.getInt("admin_id"));

                    return Optional.of(new AdminContext(
                            rs.getInt("admin_id"),
                            rs.getString("username"),
                            rs.getString("full_name"),
                            rs.getString("email"),
                            rs.getString("role")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private void updateAdminLastLogin(Connection conn, int adminId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE admins SET last_login = CURRENT_TIMESTAMP WHERE admin_id = ?")) {
            stmt.setInt(1, adminId);
            stmt.executeUpdate();
        }
    }

    // ==========================================
    // DASHBOARD STATISTICS
    // ==========================================

    /**
     * Get live dashboard statistics from the database.
     */
    public Map<String, Object> getDashboardStats() throws SQLException {
        Map<String, Object> stats = new HashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            // Farmer count
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM farmers WHERE is_active = true")) {
                rs.next();
                stats.put("totalFarmers", rs.getInt("cnt"));
            }

            // Soil samples count
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM soil_tests")) {
                rs.next();
                stats.put("totalSoilSamples", rs.getInt("cnt"));
            }

            // SHC (Soil Health Cards) generated = fertilizer recommendations count
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM fertilizer_recommendations")) {
                rs.next();
                stats.put("totalSHCGenerated", rs.getInt("cnt"));
            }

            // Districts covered
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM districts")) {
                rs.next();
                stats.put("districtsCovered", rs.getInt("cnt"));
            }

            // Recent registrations (last 30 days)
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) as cnt FROM farmers WHERE created_at >= CURRENT_DATE - INTERVAL '30 days'")) {
                rs.next();
                stats.put("recentRegistrations", rs.getInt("cnt"));
            }

            // Active farmers (logged in last 30 days)
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) as cnt FROM farmers WHERE last_login >= CURRENT_DATE - INTERVAL '30 days'")) {
                rs.next();
                stats.put("activeFarmers", rs.getInt("cnt"));
            }

            // States count
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM states")) {
                rs.next();
                stats.put("totalStates", rs.getInt("cnt"));
            }

            // Average NPK values
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT ROUND(AVG(nitrogen_val)::numeric, 2) as avg_n, " +
                         "ROUND(AVG(phosphorus_val)::numeric, 2) as avg_p, " +
                         "ROUND(AVG(potassium_val)::numeric, 2) as avg_k, " +
                         "ROUND(AVG(ph_val)::numeric, 2) as avg_ph " +
                         "FROM soil_tests")) {
                if (rs.next()) {
                    stats.put("avgNitrogen", rs.getDouble("avg_n"));
                    stats.put("avgPhosphorus", rs.getDouble("avg_p"));
                    stats.put("avgPotassium", rs.getDouble("avg_k"));
                    stats.put("avgPH", rs.getDouble("avg_ph"));
                }
            }
        }

        return stats;
    }

    // ==========================================
    // FARMER MANAGEMENT (CRUD)
    // ==========================================

    /**
     * List all farmers with pagination and search.
     */
    public Map<String, Object> listFarmers(int page, int size, String search) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> farmers = new ArrayList<>();

        int offset = (page - 1) * size;

        String countQuery;
        String dataQuery;

        if (search != null && !search.isBlank()) {
            countQuery = "SELECT COUNT(*) as cnt FROM farmers f " +
                         "JOIN farms fm ON f.farmer_id = fm.farmer_id " +
                         "JOIN districts d ON fm.district_id = d.district_id " +
                         "JOIN states s ON d.state_id = s.state_id " +
                         "WHERE f.username ILIKE ? OR f.full_name ILIKE ? OR d.name ILIKE ?";
            dataQuery = "SELECT f.farmer_id, f.username, f.full_name, f.phone, f.is_active, f.created_at, f.last_login, " +
                        "fm.postal_code, d.name as district, s.name as state " +
                        "FROM farmers f " +
                        "JOIN farms fm ON f.farmer_id = fm.farmer_id " +
                        "JOIN districts d ON fm.district_id = d.district_id " +
                        "JOIN states s ON d.state_id = s.state_id " +
                        "WHERE f.username ILIKE ? OR f.full_name ILIKE ? OR d.name ILIKE ? " +
                        "ORDER BY f.farmer_id DESC LIMIT ? OFFSET ?";
        } else {
            countQuery = "SELECT COUNT(*) as cnt FROM farmers";
            dataQuery = "SELECT f.farmer_id, f.username, f.full_name, f.phone, f.is_active, f.created_at, f.last_login, " +
                        "fm.postal_code, d.name as district, s.name as state " +
                        "FROM farmers f " +
                        "LEFT JOIN farms fm ON f.farmer_id = fm.farmer_id " +
                        "LEFT JOIN districts d ON fm.district_id = d.district_id " +
                        "LEFT JOIN states s ON d.state_id = s.state_id " +
                        "ORDER BY f.farmer_id DESC LIMIT ? OFFSET ?";
        }

        try (Connection conn = dataSource.getConnection()) {
            // Get total count
            try (PreparedStatement stmt = conn.prepareStatement(countQuery)) {
                if (search != null && !search.isBlank()) {
                    String pattern = "%" + search + "%";
                    stmt.setString(1, pattern);
                    stmt.setString(2, pattern);
                    stmt.setString(3, pattern);
                }
                ResultSet rs = stmt.executeQuery();
                rs.next();
                result.put("total", rs.getInt("cnt"));
            }

            // Get data
            try (PreparedStatement stmt = conn.prepareStatement(dataQuery)) {
                int idx = 1;
                if (search != null && !search.isBlank()) {
                    String pattern = "%" + search + "%";
                    stmt.setString(idx++, pattern);
                    stmt.setString(idx++, pattern);
                    stmt.setString(idx++, pattern);
                }
                stmt.setInt(idx++, size);
                stmt.setInt(idx, offset);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> farmer = new HashMap<>();
                    farmer.put("id", rs.getInt("farmer_id"));
                    farmer.put("username", rs.getString("username"));
                    farmer.put("fullName", rs.getString("full_name"));
                    farmer.put("phone", rs.getString("phone"));
                    farmer.put("isActive", rs.getBoolean("is_active"));
                    farmer.put("createdAt", rs.getTimestamp("created_at"));
                    farmer.put("lastLogin", rs.getTimestamp("last_login"));
                    farmer.put("postalCode", rs.getString("postal_code"));
                    farmer.put("district", rs.getString("district"));
                    farmer.put("state", rs.getString("state"));
                    farmers.add(farmer);
                }
            }
        }

        result.put("farmers", farmers);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) (int) result.get("total") / size));
        return result;
    }

    /**
     * Get a single farmer's full details including soil test data.
     */
    public Optional<Map<String, Object>> getFarmerById(int farmerId) throws SQLException {
        String query = "SELECT f.farmer_id, f.username, f.full_name, f.phone, f.is_active, f.created_at, f.last_login, " +
                       "fm.postal_code, fm.area_hectares, d.name as district, s.name as state " +
                       "FROM farmers f " +
                       "LEFT JOIN farms fm ON f.farmer_id = fm.farmer_id " +
                       "LEFT JOIN districts d ON fm.district_id = d.district_id " +
                       "LEFT JOIN states s ON d.state_id = s.state_id " +
                       "WHERE f.farmer_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setInt(1, farmerId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                Map<String, Object> farmer = new HashMap<>();
                farmer.put("id", rs.getInt("farmer_id"));
                farmer.put("username", rs.getString("username"));
                farmer.put("fullName", rs.getString("full_name"));
                farmer.put("phone", rs.getString("phone"));
                farmer.put("isActive", rs.getBoolean("is_active"));
                farmer.put("createdAt", rs.getTimestamp("created_at"));
                farmer.put("lastLogin", rs.getTimestamp("last_login"));
                farmer.put("postalCode", rs.getString("postal_code"));
                farmer.put("areaHectares", rs.getDouble("area_hectares"));
                farmer.put("district", rs.getString("district"));
                farmer.put("state", rs.getString("state"));

                // Get soil tests for this farmer
                List<Map<String, Object>> soilTests = new ArrayList<>();
                String soilQuery = "SELECT st.test_id, st.test_date, st.nitrogen_val, st.phosphorus_val, " +
                                   "st.potassium_val, st.ph_val, st.organic_carbon " +
                                   "FROM soil_tests st JOIN farms fm ON st.farm_id = fm.farm_id " +
                                   "WHERE fm.farmer_id = ? ORDER BY st.test_date DESC";
                try (PreparedStatement stmtSoil = conn.prepareStatement(soilQuery)) {
                    stmtSoil.setInt(1, farmerId);
                    ResultSet rsSoil = stmtSoil.executeQuery();
                    while (rsSoil.next()) {
                        Map<String, Object> test = new HashMap<>();
                        test.put("testId", rsSoil.getInt("test_id"));
                        test.put("testDate", rsSoil.getDate("test_date"));
                        test.put("nitrogen", rsSoil.getDouble("nitrogen_val"));
                        test.put("phosphorus", rsSoil.getDouble("phosphorus_val"));
                        test.put("potassium", rsSoil.getDouble("potassium_val"));
                        test.put("ph", rsSoil.getDouble("ph_val"));
                        test.put("organicCarbon", rsSoil.getDouble("organic_carbon"));
                        soilTests.add(test);
                    }
                }
                farmer.put("soilTests", soilTests);

                return Optional.of(farmer);
            }
        }
        return Optional.empty();
    }

    /**
     * Update farmer details (full name, phone, active status).
     */
    public boolean updateFarmer(int farmerId, String fullName, String phone, Boolean isActive) throws SQLException {
        StringBuilder query = new StringBuilder("UPDATE farmers SET ");
        List<Object> params = new ArrayList<>();

        if (fullName != null) {
            query.append("full_name = ?, ");
            params.add(fullName);
        }
        if (phone != null) {
            query.append("phone = ?, ");
            params.add(phone);
        }
        if (isActive != null) {
            query.append("is_active = ?, ");
            params.add(isActive);
        }

        if (params.isEmpty()) return false;

        // Remove trailing comma and space
        query.setLength(query.length() - 2);
        query.append(" WHERE farmer_id = ?");
        params.add(farmerId);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    stmt.setString(i + 1, (String) param);
                } else if (param instanceof Boolean) {
                    stmt.setBoolean(i + 1, (Boolean) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                }
            }
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Deactivate (soft delete) a farmer.
     */
    public boolean deactivateFarmer(int farmerId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE farmers SET is_active = false WHERE farmer_id = ?")) {
            stmt.setInt(1, farmerId);
            int rows = stmt.executeUpdate();

            // Log the action
            if (rows > 0) {
                logAudit(conn, "farmers", "DEACTIVATE", "Farmer deactivated: ID " + farmerId);
            }
            return rows > 0;
        }
    }

    // ==========================================
    // SOIL DATA MANAGEMENT
    // ==========================================

    /**
     * List soil test data with optional filters.
     */
    public Map<String, Object> listSoilData(int page, int size, String district, String state) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> tests = new ArrayList<>();
        int offset = (page - 1) * size;

        StringBuilder queryBuilder = new StringBuilder(
                "SELECT st.test_id, st.test_date, st.nitrogen_val, st.phosphorus_val, st.potassium_val, " +
                "st.ph_val, st.organic_carbon, f.full_name as farmer_name, f.username, " +
                "d.name as district, s.name as state " +
                "FROM soil_tests st " +
                "JOIN farms fm ON st.farm_id = fm.farm_id " +
                "JOIN farmers f ON fm.farmer_id = f.farmer_id " +
                "JOIN districts d ON fm.district_id = d.district_id " +
                "JOIN states s ON d.state_id = s.state_id "
        );

        StringBuilder countBuilder = new StringBuilder(
                "SELECT COUNT(*) as cnt FROM soil_tests st " +
                "JOIN farms fm ON st.farm_id = fm.farm_id " +
                "JOIN farmers f ON fm.farmer_id = f.farmer_id " +
                "JOIN districts d ON fm.district_id = d.district_id " +
                "JOIN states s ON d.state_id = s.state_id "
        );

        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();

        if (district != null && !district.isBlank()) {
            conditions.add("d.name ILIKE ?");
            params.add("%" + district + "%");
        }
        if (state != null && !state.isBlank()) {
            conditions.add("s.name ILIKE ?");
            params.add("%" + state + "%");
        }

        if (!conditions.isEmpty()) {
            String where = " WHERE " + String.join(" AND ", conditions);
            queryBuilder.append(where);
            countBuilder.append(where);
        }

        queryBuilder.append(" ORDER BY st.test_date DESC LIMIT ? OFFSET ?");

        try (Connection conn = dataSource.getConnection()) {
            // Count
            try (PreparedStatement stmt = conn.prepareStatement(countBuilder.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setString(i + 1, (String) params.get(i));
                }
                ResultSet rs = stmt.executeQuery();
                rs.next();
                result.put("total", rs.getInt("cnt"));
            }

            // Data
            try (PreparedStatement stmt = conn.prepareStatement(queryBuilder.toString())) {
                int idx = 1;
                for (Object p : params) {
                    stmt.setString(idx++, (String) p);
                }
                stmt.setInt(idx++, size);
                stmt.setInt(idx, offset);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> test = new HashMap<>();
                    test.put("testId", rs.getInt("test_id"));
                    test.put("testDate", rs.getDate("test_date"));
                    test.put("nitrogen", rs.getDouble("nitrogen_val"));
                    test.put("phosphorus", rs.getDouble("phosphorus_val"));
                    test.put("potassium", rs.getDouble("potassium_val"));
                    test.put("ph", rs.getDouble("ph_val"));
                    test.put("organicCarbon", rs.getDouble("organic_carbon"));
                    test.put("farmerName", rs.getString("farmer_name"));
                    test.put("username", rs.getString("username"));
                    test.put("district", rs.getString("district"));
                    test.put("state", rs.getString("state"));
                    tests.add(test);
                }
            }
        }

        result.put("data", tests);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) (int) result.get("total") / size));
        return result;
    }

    /**
     * Add a new soil test record for a farm.
     */
    public Map<String, Object> addSoilTest(int farmId, double nitrogen, double phosphorus,
                                            double potassium, double ph, double organicCarbon) throws SQLException {
        String query = "INSERT INTO soil_tests (farm_id, nitrogen_val, phosphorus_val, potassium_val, ph_val, organic_carbon) " +
                       "VALUES (?, ?, ?, ?, ?, ?) RETURNING test_id, test_date";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setInt(1, farmId);
            stmt.setDouble(2, nitrogen);
            stmt.setDouble(3, phosphorus);
            stmt.setDouble(4, potassium);
            stmt.setDouble(5, ph);
            stmt.setDouble(6, organicCarbon);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Map<String, Object> result = new HashMap<>();
                result.put("testId", rs.getInt("test_id"));
                result.put("testDate", rs.getDate("test_date"));
                logAudit(conn, "soil_tests", "INSERT", "New soil test added for farm: " + farmId);
                return result;
            }
        }
        return Map.of();
    }

    /**
     * Delete a soil test record.
     */
    public boolean deleteSoilTest(int testId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM soil_tests WHERE test_id = ?")) {
            stmt.setInt(1, testId);
            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logAudit(conn, "soil_tests", "DELETE", "Soil test deleted: ID " + testId);
            }
            return rows > 0;
        }
    }

    // ==========================================
    // REPORTS
    // ==========================================

    /**
     * Get aggregated reports summary.
     */
    public Map<String, Object> getReportsSummary() throws SQLException {
        Map<String, Object> report = new HashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            // District-wise averages
            List<Map<String, Object>> districtStats = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT district_name, total_farms, avg_n, avg_p, avg_k, avg_ph, avg_oc " +
                         "FROM vw_district_soil_stats ORDER BY total_farms DESC")) {
                while (rs.next()) {
                    Map<String, Object> d = new HashMap<>();
                    d.put("district", rs.getString("district_name"));
                    d.put("totalFarms", rs.getInt("total_farms"));
                    d.put("avgNitrogen", rs.getDouble("avg_n"));
                    d.put("avgPhosphorus", rs.getDouble("avg_p"));
                    d.put("avgPotassium", rs.getDouble("avg_k"));
                    d.put("avgPH", rs.getDouble("avg_ph"));
                    d.put("avgOrganicCarbon", rs.getDouble("avg_oc"));
                    districtStats.add(d);
                }
            }
            report.put("districtStats", districtStats);

            // NPK status distribution
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT " +
                         "SUM(CASE WHEN nitrogen_val < 280 THEN 1 ELSE 0 END) as n_low, " +
                         "SUM(CASE WHEN nitrogen_val BETWEEN 280 AND 560 THEN 1 ELSE 0 END) as n_medium, " +
                         "SUM(CASE WHEN nitrogen_val > 560 THEN 1 ELSE 0 END) as n_high, " +
                         "SUM(CASE WHEN phosphorus_val < 10 THEN 1 ELSE 0 END) as p_low, " +
                         "SUM(CASE WHEN phosphorus_val BETWEEN 10 AND 25 THEN 1 ELSE 0 END) as p_medium, " +
                         "SUM(CASE WHEN phosphorus_val > 25 THEN 1 ELSE 0 END) as p_high, " +
                         "SUM(CASE WHEN potassium_val < 110 THEN 1 ELSE 0 END) as k_low, " +
                         "SUM(CASE WHEN potassium_val BETWEEN 110 AND 280 THEN 1 ELSE 0 END) as k_medium, " +
                         "SUM(CASE WHEN potassium_val > 280 THEN 1 ELSE 0 END) as k_high " +
                         "FROM soil_tests")) {
                if (rs.next()) {
                    report.put("nitrogenDistribution", Map.of(
                            "low", rs.getInt("n_low"),
                            "medium", rs.getInt("n_medium"),
                            "high", rs.getInt("n_high")));
                    report.put("phosphorusDistribution", Map.of(
                            "low", rs.getInt("p_low"),
                            "medium", rs.getInt("p_medium"),
                            "high", rs.getInt("p_high")));
                    report.put("potassiumDistribution", Map.of(
                            "low", rs.getInt("k_low"),
                            "medium", rs.getInt("k_medium"),
                            "high", rs.getInt("k_high")));
                }
            }

            // Crop recommendation stats
            List<Map<String, Object>> cropStats = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT c.crop_name, COUNT(fr.rec_id) as recommendation_count, " +
                         "ROUND(AVG(fr.urea_dose)::numeric, 2) as avg_urea, " +
                         "ROUND(AVG(fr.dap_dose)::numeric, 2) as avg_dap, " +
                         "ROUND(AVG(fr.mop_dose)::numeric, 2) as avg_mop " +
                         "FROM fertilizer_recommendations fr " +
                         "JOIN crops c ON fr.crop_id = c.crop_id " +
                         "GROUP BY c.crop_name ORDER BY recommendation_count DESC")) {
                while (rs.next()) {
                    Map<String, Object> crop = new HashMap<>();
                    crop.put("cropName", rs.getString("crop_name"));
                    crop.put("recommendationCount", rs.getInt("recommendation_count"));
                    crop.put("avgUrea", rs.getDouble("avg_urea"));
                    crop.put("avgDAP", rs.getDouble("avg_dap"));
                    crop.put("avgMOP", rs.getDouble("avg_mop"));
                    cropStats.add(crop);
                }
            }
            report.put("cropStats", cropStats);
        }

        return report;
    }

    // ==========================================
    // AUDIT LOGS
    // ==========================================

    /**
     * Get audit log entries with pagination.
     */
    public Map<String, Object> getAuditLogs(int page, int size) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> logs = new ArrayList<>();
        int offset = (page - 1) * size;

        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM audit_logs")) {
                rs.next();
                result.put("total", rs.getInt("cnt"));
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT log_id, table_name, action, details, changed_by, changed_on " +
                    "FROM audit_logs ORDER BY changed_on DESC LIMIT ? OFFSET ?")) {
                stmt.setInt(1, size);
                stmt.setInt(2, offset);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> log = new HashMap<>();
                    log.put("id", rs.getInt("log_id"));
                    log.put("tableName", rs.getString("table_name"));
                    log.put("action", rs.getString("action"));
                    log.put("details", rs.getString("details"));
                    log.put("changedBy", rs.getString("changed_by"));
                    log.put("changedOn", rs.getTimestamp("changed_on"));
                    logs.add(log);
                }
            }
        }

        result.put("logs", logs);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * Log an audit entry.
     */
    private void logAudit(Connection conn, String tableName, String action, String details) {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO audit_logs (table_name, action, details) VALUES (?, ?, ?)")) {
            stmt.setString(1, tableName);
            stmt.setString(2, action);
            stmt.setString(3, details);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write audit log: {}", e.getMessage());
        }
    }

    // ==========================================
    // ADMIN CONTEXT (DTO)
    // ==========================================

    public static class AdminContext {
        public final int adminId;
        public final String username;
        public final String fullName;
        public final String email;
        public final String role;

        public AdminContext(int adminId, String username, String fullName, String email, String role) {
            this.adminId = adminId;
            this.username = username;
            this.fullName = fullName;
            this.email = email;
            this.role = role;
        }
    }
}
