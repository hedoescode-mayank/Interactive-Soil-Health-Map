package com.ishm.map;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Controller("/api/map")
public class MapController {

    private static final Logger LOG = LoggerFactory.getLogger(MapController.class);

    @Inject
    DataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get all districts with soil health data as GeoJSON
     */
    @Get("/districts")
    public HttpResponse<Map<String, Object>> getDistricts(
            @QueryValue Optional<String> state) {

        try {
            Map<String, Object> geoJson = getDistrictsFromDatabase(state);
            return HttpResponse.ok(geoJson);

        } catch (Exception e) {
            LOG.error("Error fetching districts", e);
            return HttpResponse.serverError(Map.of("error", "Failed to fetch district data: " + e.getMessage()));
        }
    }

    /**
     * Get districts from local database
     */
    private Map<String, Object> getDistrictsFromDatabase(Optional<String> state) throws SQLException {
        String query = """
            SELECT 
                d.district_id,
                d.name as district_name,
                s.name as state_name,
                ST_AsGeoJSON(d.geom) as geometry,
                v.avg_n, v.avg_p, v.avg_k, v.avg_oc, v.avg_ph,
                v.total_farms as samples_analyzed
            FROM districts d
            JOIN states s ON d.state_id = s.state_id
            LEFT JOIN vw_district_soil_stats v ON d.name = v.district_name
        """;

        if (state.isPresent() && !state.get().isEmpty()) {
            query += " WHERE s.name = ?";
        }

        List<Map<String, Object>> features = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            if (state.isPresent() && !state.get().isEmpty()) {
                stmt.setString(1, state.get());
            }

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> feature = new HashMap<>();
                feature.put("type", "Feature");

                String geomJson = rs.getString("geometry");
                if (geomJson != null) {
                    try {
                        JsonNode geomNode = objectMapper.readTree(geomJson);
                        feature.put("geometry", objectMapper.convertValue(geomNode, Map.class));
                    } catch (Exception e) {
                        LOG.warn("Failed to parse geometry for district: " + rs.getString("district_name"));
                    }
                } else {
                    feature.put("geometry", null);
                }

                Map<String, Object> properties = new HashMap<>();
                properties.put("district_id", rs.getLong("district_id"));
                properties.put("district_name", rs.getString("district_name"));
                properties.put("state_name", rs.getString("state_name"));
                
                double n = rs.getDouble("avg_n");
                double p = rs.getDouble("avg_p");
                double k = rs.getDouble("avg_k");
                double oc = rs.getDouble("avg_oc");
                double ph = rs.getDouble("avg_ph");
                
                properties.put("nitrogen_avg", n);
                properties.put("nitrogen_status", calculateStatus(n, 280, 560));
                
                properties.put("phosphorus_avg", p);
                properties.put("phosphorus_status", calculateStatus(p, 10, 25));
                
                properties.put("potassium_avg", k);
                properties.put("potassium_status", calculateStatus(k, 110, 280));
                
                properties.put("oc_avg", oc);
                properties.put("oc_status", oc > 0.75 ? "High" : (oc > 0.5 ? "Medium" : "Low"));
                
                properties.put("ph_avg", ph);
                properties.put("ph_status", (ph >= 6.5 && ph <= 7.5) ? "Neutral" : (ph < 6.5 ? "Acidic" : "Alkaline"));
                
                properties.put("samples_analyzed", rs.getInt("samples_analyzed"));

                feature.put("properties", properties);
                features.add(feature);
            }
        }

        Map<String, Object> geoJson = new HashMap<>();
        geoJson.put("type", "FeatureCollection");
        geoJson.put("features", features);
        geoJson.put("metadata", Map.of(
            "timestamp", System.currentTimeMillis(),
            "count", features.size()
        ));
        return geoJson;
    }

    private String calculateStatus(double val, double low, double high) {
        if (val <= 0) return "Unknown";
        if (val < low) return "Low";
        if (val < high) return "Medium";
        return "High";
    }

    /**
     * Get state-level statistics
     */
    @Get("/stats/{state}")
    public HttpResponse<Map<String, Object>> getStateStats(@PathVariable String state) {
        try {
            String query = """
                SELECT 
                    COUNT(DISTINCT d.district_id) as district_count,
                    AVG(v.avg_n) as avg_n,
                    AVG(v.avg_p) as avg_p,
                    AVG(v.avg_k) as avg_k,
                    AVG(v.avg_oc) as avg_oc,
                    AVG(v.avg_ph) as avg_ph,
                    SUM(v.total_farms) as total_samples,
                    COUNT(*) FILTER (WHERE v.avg_n < 280) as low_n_districts,
                    COUNT(*) FILTER (WHERE v.avg_n >= 280 AND v.avg_n < 560) as med_n_districts,
                    COUNT(*) FILTER (WHERE v.avg_n >= 560) as high_n_districts
                FROM districts d
                JOIN states s ON d.state_id = s.state_id
                LEFT JOIN vw_district_soil_stats v ON d.name = v.district_name
                WHERE s.name = ?
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, state);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("state", state);
                    stats.put("district_count", rs.getInt("district_count"));
                    stats.put("total_samples", rs.getLong("total_samples"));
                    
                    Map<String, Object> averages = new HashMap<>();
                    averages.put("nitrogen", rs.getDouble("avg_n"));
                    averages.put("phosphorus", rs.getDouble("avg_p"));
                    averages.put("potassium", rs.getDouble("avg_k"));
                    averages.put("oc", rs.getDouble("avg_oc"));
                    averages.put("ph", rs.getDouble("avg_ph"));
                    stats.put("averages", averages);
                    
                    Map<String, Object> distribution = new HashMap<>();
                    distribution.put("low", rs.getInt("low_n_districts"));
                    distribution.put("medium", rs.getInt("med_n_districts"));
                    distribution.put("high", rs.getInt("high_n_districts"));
                    stats.put("nitrogen_distribution", distribution);
                    
                    return HttpResponse.ok(stats);
                }
                return HttpResponse.notFound();
            }
        } catch (SQLException e) {
            LOG.error("Error fetching state stats", e);
            return HttpResponse.serverError(Map.of("error", "Database error: " + e.getMessage()));
        }
    }

    /**
     * Get all states list
     */
    @Get("/states")
    public HttpResponse<List<Map<String, Object>>> getStates() {
        try {
            String query = "SELECT name, state_id FROM states ORDER BY name";
            List<Map<String, Object>> states = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {

                while (rs.next()) {
                    Map<String, Object> state = new HashMap<>();
                    state.put("name", rs.getString("name"));
                    state.put("id", rs.getInt("state_id"));
                    states.add(state);
                }
            }
            return HttpResponse.ok(states);
        } catch (SQLException e) {
            LOG.error("Error fetching states", e);
            return HttpResponse.serverError(Collections.emptyList());
        }
    }
}