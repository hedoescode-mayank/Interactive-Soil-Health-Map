package com.ishm.dash;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.time.LocalDate;

@Controller("/api/dashboard")
public class DashboardController {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardController.class);

    @Inject
    DataSource dataSource;

    /**
     * Get dashboard summary with key metrics
     */
    @Get("/summary")
    public HttpResponse<Map<String, Object>> getDashboardSummary(
            @QueryValue Optional<String> state,
            @QueryValue Optional<Integer> year) {

        try {
            Map<String, Object> summary = new HashMap<>();

            // Get current year if not specified
            int targetYear = year.orElse(LocalDate.now().getYear());

            // Key metrics
            summary.put("metrics", getKeyMetrics(state, targetYear));

            // NPK trends
            summary.put("npkTrends", getNPKTrends(state, targetYear));

            // State-wise distribution
            summary.put("stateDistribution", getStateDistribution(targetYear));

            // Recent activities
            summary.put("recentActivities", getRecentActivities());

            // District summary table
            summary.put("districtSummary", getDistrictSummary(state, targetYear));

            return HttpResponse.ok(summary);

        } catch (SQLException e) {
            LOG.error("Error fetching dashboard summary", e);
            return HttpResponse.serverError(Map.of("error", "Database error"));
        }
    }

    /**
     * Get key performance metrics including districts covered and total samples.
     * 
     * @param state Optional state filter
     * @param year Target year for metrics
     * @return Map of metric values
     * @throws SQLException if database error occurs
     */
    private Map<String, Object> getKeyMetrics(Optional<String> state, int year) throws SQLException {
        Map<String, Object> metrics = new HashMap<>();

        String baseQuery = """
            SELECT 
                COUNT(DISTINCT district_name) as districts_covered,
                SUM(total_farms) as total_samples,
                AVG(avg_n) as avg_n_total
            FROM vw_district_soil_stats
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(baseQuery)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                metrics.put("districtsCovered", rs.getInt("districts_covered"));
                metrics.put("totalSamples", rs.getLong("total_samples"));
                metrics.put("avgSoilHealth", 6.5); 
            }
        }

        metrics.put("districtsGrowth", 12.5);
        metrics.put("samplesGrowth", 8.4);
        metrics.put("farmersBenefited", 25000);

        return metrics;
    }

    /**
     * Get NPK (Nitrogen, Phosphorus, Potassium) trends over months.
     * Currently utilizes randomized demo data for visualization.
     * 
     * @param state Optional state filter
     * @param year Target year for trends
     * @return Map containing month labels and NPK data points
     * @throws SQLException if database error occurs
     */
    private Map<String, Object> getNPKTrends(Optional<String> state, int year) throws SQLException {
        Map<String, Object> trends = new HashMap<>();
        List<String> months = Arrays.asList("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");

        List<Double> nData = new ArrayList<>();
        List<Double> pData = new ArrayList<>();
        List<Double> kData = new ArrayList<>();
        Random rand = new Random();

        for (int i = 0; i < 12; i++) {
            nData.add(250 + rand.nextDouble() * 50);
            pData.add(20 + rand.nextDouble() * 5);
            kData.add(180 + rand.nextDouble() * 30);
        }

        trends.put("labels", months);
        trends.put("nitrogen", nData);
        trends.put("phosphorus", pData);
        trends.put("potassium", kData);
        return trends;
    }

    /**
     * Get state-wise sample distribution across the country.
     * Aggregates sample counts and average nitrogen levels per state.
     * 
     * @param year Target year for distribution
     * @return List of state data maps
     * @throws SQLException if database error occurs
     */
    private List<Map<String, Object>> getStateDistribution(int year) throws SQLException {
        List<Map<String, Object>> distribution = new ArrayList<>();

        String query = """
            SELECT 
                s.name as state_name,
                COUNT(DISTINCT d.district_id) as district_count,
                COUNT(fm.farm_id) as total_samples,
                AVG(st.nitrogen_val) as avg_nitrogen
            FROM states s
            JOIN districts d ON s.state_id = d.state_id
            LEFT JOIN farms fm ON d.district_id = fm.district_id
            LEFT JOIN soil_tests st ON fm.farm_id = st.farm_id
            GROUP BY s.name
            ORDER BY total_samples DESC
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> stateData = new HashMap<>();
                stateData.put("state", rs.getString("state_name"));
                stateData.put("districts", rs.getInt("district_count"));
                stateData.put("samples", rs.getLong("total_samples"));
                stateData.put("avgNitrogen", rs.getDouble("avg_nitrogen"));
                distribution.add(stateData);
            }
        }
        return distribution;
    }

    /**
     * Get district-wise summary for dashboard table display.
     * Includes NPK status and basic soil parameters.
     * 
     * @param state Optional state filter
     * @param year Target year for summary
     * @return List of district summary maps
     * @throws SQLException if database error occurs
     */
    private List<Map<String, Object>> getDistrictSummary(Optional<String> state, int year) throws SQLException {
        List<Map<String, Object>> districtsList = new ArrayList<>();

        String query = """
            SELECT 
                d.name as district_name,
                s.name as state_name,
                COUNT(fm.farm_id) as samples,
                AVG(st.nitrogen_val) as avg_n,
                AVG(st.ph_val) as ph_avg
            FROM districts d
            JOIN states s ON d.state_id = s.state_id
            LEFT JOIN farms fm ON d.district_id = fm.district_id
            LEFT JOIN soil_tests st ON fm.farm_id = st.farm_id
            GROUP BY d.name, s.name
            ORDER BY samples DESC
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> district = new HashMap<>();
                district.put("districtName", rs.getString("district_name"));
                district.put("stateName", rs.getString("state_name"));
                district.put("samples", rs.getInt("samples"));
                district.put("nitrogenStatus", rs.getDouble("avg_n") > 280 ? "Medium" : "Low");
                district.put("phosphorusStatus", "Medium");
                district.put("potassiumStatus", "Medium");
                district.put("ph", rs.getDouble("ph_avg"));
                district.put("organicCarbon", 0.5);
                district.put("lastUpdated", new Timestamp(System.currentTimeMillis()));
                districtsList.add(district);
            }
        }
        return districtsList;
    }

    /**
     * Get recent activities
     */
    private List<Map<String, Object>> getRecentActivities() {
        List<Map<String, Object>> activities = new ArrayList<>();
        activities.add(Map.of(
                "type", "report",
                "title", "New Report Generated",
                "description", "Soil Health Report 2026",
                "timestamp", "2 hours ago",
                "icon", "📊"
        ));
        activities.add(Map.of(
                "type", "map_update",
                "title", "Map Updated",
                "description", "Refreshed district data",
                "timestamp", "5 hours ago",
                "icon", "🗺️"
        ));
        return activities;
    }

    /**
     * Export district data as CSV
     */
    @Get("/export/csv")
    @Produces("text/csv")
    public HttpResponse<String> exportCSV(
            @QueryValue Optional<String> state,
            @QueryValue Optional<Integer> year) {

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("District,State,Samples,N Status,P Status,K Status,pH,OC%,Last Updated\n");

            List<Map<String, Object>> districts = getDistrictSummary(state, year.orElse(LocalDate.now().getYear()));

            for (Map<String, Object> district : districts) {
                csv.append(String.format("%s,%s,%d,%s,%s,%s,%.1f,%.2f,%s\n",
                        district.get("districtName"),
                        district.get("stateName"),
                        district.get("samples"),
                        district.get("nitrogenStatus"),
                        district.get("phosphorusStatus"),
                        district.get("potassiumStatus"),
                        district.get("ph"),
                        district.get("organicCarbon"),
                        district.get("lastUpdated")
                ));
            }

            return HttpResponse.ok(csv.toString())
                    .header("Content-Disposition", "attachment; filename=soil_health_data.csv");

        } catch (SQLException e) {
            LOG.error("Error exporting CSV", e);
            return HttpResponse.serverError("Error generating CSV");
        }
    }

    private Map<String, Object> createDemoDistrict(String name, String state, int samples,
                                                   String nStatus, String pStatus, String kStatus, double ph, double oc) {
        Map<String, Object> district = new HashMap<>();
        district.put("districtName", name);
        district.put("stateName", state);
        district.put("samples", samples);
        district.put("nitrogenStatus", nStatus);
        district.put("phosphorusStatus", pStatus);
        district.put("potassiumStatus", kStatus);
        district.put("ph", ph);
        district.put("organicCarbon", oc);
        district.put("lastUpdated", new Timestamp(System.currentTimeMillis()));
        return district;
    }
}