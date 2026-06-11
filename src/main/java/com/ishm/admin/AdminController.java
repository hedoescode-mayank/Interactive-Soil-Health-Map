package com.ishm.admin;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

/**
 * REST controller for admin operations.
 * Provides endpoints for dashboard stats, farmer management,
 * soil data management, reports, and audit logs.
 * 
 * All endpoints require a valid admin JWT token (verified on frontend).
 */
@Controller("/api/admin")
public class AdminController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    @Inject
    AdminService adminService;

    // ==========================================
    // DASHBOARD STATS
    // ==========================================

    /**
     * Get live dashboard statistics.
     * Returns: totalFarmers, totalSoilSamples, totalSHCGenerated, districtsCovered, etc.
     */
    @Get("/stats")
    public HttpResponse<Map<String, Object>> getDashboardStats() {
        try {
            Map<String, Object> stats = adminService.getDashboardStats();
            stats.put("success", true);
            return HttpResponse.ok(stats);
        } catch (SQLException e) {
            LOG.error("Error fetching dashboard stats", e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to fetch dashboard statistics"));
        }
    }

    // ==========================================
    // FARMER MANAGEMENT
    // ==========================================

    /**
     * List all farmers with pagination and optional search.
     * Query params: page (default 1), size (default 20), search (optional)
     */
    @Get("/farmers")
    public HttpResponse<Map<String, Object>> listFarmers(
            @QueryValue Optional<Integer> page,
            @QueryValue Optional<Integer> size,
            @QueryValue Optional<String> search) {
        try {
            LOG.info("Request to list farmers: page={}, size={}, search={}", 
                    page.orElse(1), size.orElse(20), search.orElse("none"));
            Map<String, Object> result = adminService.listFarmers(
                    page.orElse(1),
                    size.orElse(20),
                    search.orElse(null));
            result.put("success", true);
            return HttpResponse.ok(result);
        } catch (SQLException e) {
            LOG.error("Error listing farmers", e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to fetch farmers"));
        }
    }

    /**
     * Get a single farmer's full details including soil test history.
     */
    @Get("/farmers/{id}")
    public HttpResponse<Map<String, Object>> getFarmer(@PathVariable int id) {
        try {
            Optional<Map<String, Object>> farmer = adminService.getFarmerById(id);
            if (farmer.isPresent()) {
                Map<String, Object> result = new HashMap<>(farmer.get());
                result.put("success", true);
                return HttpResponse.ok(result);
            }
            return HttpResponse.notFound(Map.of("success", false, "error", "Farmer not found"));
        } catch (SQLException e) {
            LOG.error("Error fetching farmer {}", id, e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to fetch farmer details"));
        }
    }

    /**
     * Update farmer details.
     * Body: { fullName, phone, isActive }
     */
    @Put("/farmers/{id}")
    public HttpResponse<Map<String, Object>> updateFarmer(@PathVariable int id, @Body Map<String, Object> body) {
        try {
            LOG.info("Request to update farmer {}: {}", id, body);
            String fullName = body.containsKey("fullName") ? (String) body.get("fullName") : null;
            String phone = body.containsKey("phone") ? (String) body.get("phone") : null;
            Boolean isActive = body.containsKey("isActive") ? (Boolean) body.get("isActive") : null;

            boolean success = adminService.updateFarmer(id, fullName, phone, isActive);
            if (success) {
                LOG.info("Successfully updated farmer {}", id);
                return HttpResponse.ok(Map.of("success", true, "message", "Farmer updated successfully"));
            }
            return HttpResponse.badRequest(Map.of("success", false, "error", "No changes made or farmer not found"));
        } catch (SQLException e) {
            LOG.error("Error updating farmer {}", id, e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to update farmer"));
        }
    }

    /**
     * Deactivate (soft delete) a farmer.
     */
    @Delete("/farmers/{id}")
    public HttpResponse<Map<String, Object>> deactivateFarmer(@PathVariable int id) {
        try {
            LOG.info("Request to deactivate farmer {}", id);
            boolean success = adminService.deactivateFarmer(id);
            if (success) {
                LOG.info("Successfully deactivated farmer {}", id);
                return HttpResponse.ok(Map.of("success", true, "message", "Farmer deactivated successfully"));
            }
            return HttpResponse.notFound(Map.of("success", false, "error", "Farmer not found"));
        } catch (SQLException e) {
            LOG.error("Error deactivating farmer {}", id, e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to deactivate farmer"));
        }
    }

    // ==========================================
    // SOIL DATA MANAGEMENT
    // ==========================================

    /**
     * List soil test data with optional filters.
     * Query params: page, size, district, state
     */
    @Get("/soil-data")
    public HttpResponse<Map<String, Object>> listSoilData(
            @QueryValue Optional<Integer> page,
            @QueryValue Optional<Integer> size,
            @QueryValue Optional<String> district,
            @QueryValue Optional<String> state) {
        try {
            Map<String, Object> result = adminService.listSoilData(
                    page.orElse(1),
                    size.orElse(20),
                    district.orElse(null),
                    state.orElse(null));
            result.put("success", true);
            return HttpResponse.ok(result);
        } catch (SQLException e) {
            LOG.error("Error listing soil data", e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to fetch soil data"));
        }
    }

    /**
     * Add a new soil test record.
     * Body: { farmId, nitrogen, phosphorus, potassium, ph, organicCarbon }
     */
    @Post("/soil-data")
    public HttpResponse<Map<String, Object>> addSoilTest(@Body Map<String, Object> body) {
        try {
            int farmId = ((Number) body.get("farmId")).intValue();
            double nitrogen = ((Number) body.get("nitrogen")).doubleValue();
            double phosphorus = ((Number) body.get("phosphorus")).doubleValue();
            double potassium = ((Number) body.get("potassium")).doubleValue();
            double ph = ((Number) body.get("ph")).doubleValue();
            double organicCarbon = body.containsKey("organicCarbon")
                    ? ((Number) body.get("organicCarbon")).doubleValue()
                    : 0.5;

            Map<String, Object> result = adminService.addSoilTest(farmId, nitrogen, phosphorus, potassium, ph, organicCarbon);
            result.put("success", true);
            result.put("message", "Soil test record added successfully");
            return HttpResponse.created(result);
        } catch (Exception e) {
            LOG.error("Error adding soil test", e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to add soil test: " + e.getMessage()));
        }
    }

    /**
     * Delete a soil test record.
     */
    @Delete("/soil-data/{id}")
    public HttpResponse<Map<String, Object>> deleteSoilTest(@PathVariable int id) {
        try {
            boolean success = adminService.deleteSoilTest(id);
            if (success) {
                return HttpResponse.ok(Map.of("success", true, "message", "Soil test record deleted"));
            }
            return HttpResponse.notFound(Map.of("success", false, "error", "Soil test record not found"));
        } catch (SQLException e) {
            LOG.error("Error deleting soil test {}", id, e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to delete soil test"));
        }
    }

    // ==========================================
    // REPORTS
    // ==========================================

    /**
     * Get aggregated reports summary including district stats,
     * NPK distribution, and crop recommendation stats.
     */
    @Get("/reports/summary")
    public HttpResponse<Map<String, Object>> getReportsSummary() {
        try {
            Map<String, Object> report = adminService.getReportsSummary();
            report.put("success", true);
            return HttpResponse.ok(report);
        } catch (SQLException e) {
            LOG.error("Error fetching reports summary", e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to generate reports"));
        }
    }

    // ==========================================
    // AUDIT LOGS
    // ==========================================

    /**
     * Get audit log entries with pagination.
     */
    @Get("/audit-logs")
    public HttpResponse<Map<String, Object>> getAuditLogs(
            @QueryValue Optional<Integer> page,
            @QueryValue Optional<Integer> size) {
        try {
            Map<String, Object> result = adminService.getAuditLogs(
                    page.orElse(1),
                    size.orElse(50));
            result.put("success", true);
            return HttpResponse.ok(result);
        } catch (SQLException e) {
            LOG.error("Error fetching audit logs", e);
            return HttpResponse.serverError(Map.of("success", false, "error", "Failed to fetch audit logs"));
        }
    }
}
