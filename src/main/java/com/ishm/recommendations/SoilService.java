package com.ishm.recommendations;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Singleton
public class SoilService {

    private static final Logger LOG = LoggerFactory.getLogger(SoilService.class);

    @Inject
    DataSource dataSource;

    public Map<String, Object> calculateRecommendations(RecommendationController.RecommendationRequest request) throws SQLException {
        // Get or estimate NPK values
        double nitrogen = request.getNitrogen() != null ? request.getNitrogen() : estimateNutrient(request, "nitrogen");
        double phosphorus = request.getPhosphorus() != null ? request.getPhosphorus() : estimateNutrient(request, "phosphorus");
        double potassium = request.getPotassium() != null ? request.getPotassium() : estimateNutrient(request, "potassium");

        // Calculate nutrient status
        String nStatus = classifyNutrientLevel(nitrogen, "N");
        String pStatus = classifyNutrientLevel(phosphorus, "P");
        String kStatus = classifyNutrientLevel(potassium, "K");

        // Get crop nutrient requirements
        CropRequirements cropReq = getCropRequirements(request.getCrop());

        // Calculate fertilizer doses
        Map<String, Object> recommendations = new HashMap<>();
        recommendations.put("nitrogenStatus", nStatus);
        recommendations.put("phosphorusStatus", pStatus);
        recommendations.put("potassiumStatus", kStatus);

        recommendations.put("ureaDose", calculateUreaDose(nitrogen, cropReq.nitrogenReq));
        recommendations.put("dapDose", calculateDAPDose(phosphorus, cropReq.phosphorusReq));
        recommendations.put("mopDose", calculateMOPDose(potassium, cropReq.potassiumReq));
        recommendations.put("sspDose", calculateSSPDose(phosphorus, cropReq.phosphorusReq));

        if (request.getPh() != null && request.getPh() < 6.0) {
            recommendations.put("limeRequired", true);
            recommendations.put("limeDose", calculateLimeDose(request.getPh()));
        }

        // Application schedule
        Map<String, String> schedule = new HashMap<>();
        schedule.put("basal", generateBasalDose(recommendations));
        schedule.put("firstTopdress", generateFirstTopdress(request.getCrop()));
        schedule.put("secondTopdress", generateSecondTopdress(request.getCrop()));
        recommendations.put("schedule", schedule);

        recommendations.put("tips", generateTips(nStatus, pStatus, kStatus, request.getCrop()));

        return recommendations;
    }

    public List<Map<String, Object>> getCrops() throws SQLException {
        String query = "SELECT crop_name, season FROM crops ORDER BY crop_name";
        List<Map<String, Object>> crops = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                Map<String, Object> crop = new HashMap<>();
                crop.put("name", rs.getString("crop_name"));
                crop.put("season", rs.getString("season"));
                crop.put("type", "Field Crop");
                crops.add(crop);
            }
        }
        return crops;
    }

    public String classifyNutrientLevel(double value, String nutrient) {
        switch (nutrient) {
            case "N":
                if (value < 280) return "Low";
                else if (value <= 560) return "Medium";
                else return "High";
            case "P":
                if (value < 10) return "Low";
                else if (value <= 25) return "Medium";
                else return "High";
            case "K":
                if (value < 110) return "Low";
                else if (value <= 280) return "Medium";
                else return "High";
            default:
                return "Unknown";
        }
    }

    private double estimateNutrient(RecommendationController.RecommendationRequest request, String nutrient) {
        if (request.getDistrict() != null) {
            try {
                String col = switch(nutrient) {
                    case "nitrogen" -> "avg_n";
                    default -> "avg_ph";
                };
                String query = "SELECT " + col + " FROM vw_district_soil_stats WHERE district_name = ?";

                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(query)) {

                    stmt.setString(1, request.getDistrict());
                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        return rs.getDouble(1);
                    }
                }
            } catch (SQLException e) {
                LOG.warn("Could not fetch district data", e);
            }
        }

        switch (nutrient) {
            case "nitrogen": return 250;
            case "phosphorus": return 15;
            case "potassium": return 150;
            default: return 0;
        }
    }

    private CropRequirements getCropRequirements(String crop) {
        Map<String, CropRequirements> requirements = Map.of(
                "wheat", new CropRequirements(120, 60, 60),
                "rice", new CropRequirements(150, 60, 60),
                "maize", new CropRequirements(120, 60, 40),
                "cotton", new CropRequirements(160, 80, 60),
                "sugarcane", new CropRequirements(300, 100, 150),
                "mustard", new CropRequirements(80, 40, 40),
                "tomato", new CropRequirements(150, 80, 100),
                "potato", new CropRequirements(180, 80, 120)
        );

        return requirements.getOrDefault(crop, new CropRequirements(100, 50, 50));
    }

    private double calculateUreaDose(double currentN, double requiredN) {
        double deficit = Math.max(0, requiredN - (currentN * 0.5));
        return Math.round((deficit / 0.46) * 10) / 10.0;
    }

    private double calculateDAPDose(double currentP, double requiredP) {
        double deficit = Math.max(0, requiredP - (currentP * 0.5));
        return Math.round((deficit / 0.46) * 10) / 10.0;
    }

    private double calculateMOPDose(double currentK, double requiredK) {
        double deficit = Math.max(0, requiredK - (currentK * 0.5));
        return Math.round((deficit / 0.60) * 10) / 10.0;
    }

    private double calculateSSPDose(double currentP, double requiredP) {
        double deficit = Math.max(0, requiredP - (currentP * 0.5));
        return Math.round((deficit / 0.16) * 10) / 10.0;
    }

    private double calculateLimeDose(double ph) {
        if (ph < 5.5) return 2000;
        else if (ph < 6.0) return 1000;
        else return 500;
    }

    private String generateBasalDose(Map<String, Object> recommendations) {
        return String.format("Apply 50%% of N (%s kg Urea), full P (%s kg DAP) and K (%s kg MOP) at sowing",
                recommendations.get("ureaDose"),
                recommendations.get("dapDose"),
                recommendations.get("mopDose"));
    }

    private String generateFirstTopdress(String crop) {
        Map<String, String> schedules = Map.of(
                "wheat", "30-35 days after sowing",
                "rice", "20-25 days after transplanting",
                "maize", "25-30 days after sowing",
                "cotton", "40-45 days after sowing",
                "sugarcane", "45-50 days after planting"
        );
        return schedules.getOrDefault(crop, "30 days after sowing/planting");
    }

    private String generateSecondTopdress(String crop) {
        Map<String, String> schedules = Map.of(
                "wheat", "60-65 days after sowing",
                "rice", "45-50 days after transplanting",
                "maize", "50-55 days after sowing",
                "cotton", "70-75 days after sowing",
                "sugarcane", "90-100 days after planting"
        );
        return schedules.getOrDefault(crop, "60 days after sowing/planting");
    }

    private List<String> generateTips(String nStatus, String pStatus, String kStatus, String crop) {
        List<String> tips = new ArrayList<>();
        tips.add("Apply fertilizers when soil has adequate moisture");
        tips.add("Avoid fertilizer application during heavy rain");
        if ("Low".equals(nStatus)) tips.add("Consider adding organic manure to improve nitrogen content");
        if ("Low".equals(pStatus)) tips.add("Phosphorus deficiency may delay maturity - monitor crop closely");
        if ("Low".equals(kStatus)) tips.add("Potassium deficiency may affect disease resistance");
        tips.add("Conduct soil testing every 2-3 years for best results");
        return tips;
    }

    private static class CropRequirements {
        double nitrogenReq;
        double phosphorusReq;
        double potassiumReq;

        CropRequirements(double n, double p, double k) {
            this.nitrogenReq = n;
            this.phosphorusReq = p;
            this.potassiumReq = k;
        }
    }
}
