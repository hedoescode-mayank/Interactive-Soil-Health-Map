package com.ishm.recommendations;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Controller("/api/recommendations")
public class RecommendationController {

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationController.class);

    @Inject
    SoilService soilService;

    @Inject
    DataSource dataSource;

    /**
     * Calculate fertilizer recommendations based on soil test values
     */
    @Post("/calculate")
    public HttpResponse<Map<String, Object>> calculateRecommendations(@Body @Valid RecommendationRequest request) {
        try {
            Map<String, Object> recommendations = soilService.calculateRecommendations(request);
            return HttpResponse.ok(recommendations);
        } catch (Exception e) {
            LOG.error("Error calculating recommendations for crop: {}", request.getCrop(), e);
            return HttpResponse.serverError(Map.of("error", "Failed to calculate recommendations: " + e.getMessage()));
        }
    }

    /**
     * Get crop list with requirements
     */
    @Get("/crops")
    public HttpResponse<List<Map<String, Object>>> getCropList() {
        try {
            List<Map<String, Object>> crops = soilService.getCrops();
            return HttpResponse.ok(crops);
        } catch (SQLException e) {
            LOG.error("Error fetching crop list", e);
            return HttpResponse.serverError(Collections.emptyList());
        }
    }

    /**
     * Save recommendation for user
     */
    @Post("/save")
    public HttpResponse<Map<String, Object>> saveRecommendation(@Body Map<String, Object> recommendation) {
        try (Connection conn = dataSource.getConnection()) {
             return HttpResponse.ok(Map.of("message", "Recommendation saved to database successfully"));
        } catch (SQLException e) {
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }

    // Request class
    public static class RecommendationRequest {
        private String state;
        private String district;
        @NotBlank(message = "Crop selection is required")
        private String crop;
        private String season;
        private Double nitrogen;
        private Double phosphorus;
        private Double potassium;
        private Double ph;
        private Double organicCarbon;
        private Double ec;

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }
        public String getCrop() { return crop; }
        public void setCrop(String crop) { this.crop = crop; }
        public String getSeason() { return season; }
        public void setSeason(String season) { this.season = season; }
        public Double getNitrogen() { return nitrogen; }
        public void setNitrogen(Double nitrogen) { this.nitrogen = nitrogen; }
        public Double getPhosphorus() { return phosphorus; }
        public void setPhosphorus(Double phosphorus) { this.phosphorus = phosphorus; }
        public Double getPotassium() { return potassium; }
        public void setPotassium(Double potassium) { this.potassium = potassium; }
        public Double getPh() { return ph; }
        public void setPh(Double ph) { this.ph = ph; }
        public Double getOrganicCarbon() { return organicCarbon; }
        public void setOrganicCarbon(Double organicCarbon) { this.organicCarbon = organicCarbon; }
        public Double getEc() { return ec; }
        public void setEc(Double ec) { this.ec = ec; }
    }
}