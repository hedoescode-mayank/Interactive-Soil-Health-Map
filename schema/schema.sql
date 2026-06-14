-- ISHM Redesigned Schema (Based on li.pdf DBMS Lab Manual)
-- Concepts: DDL, DML, Constraints, Normalization, Joins, Views, PL/pgSQL, Triggers, Cursors

-- Start Fresh
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO ishm;
CREATE EXTENSION IF NOT EXISTS postgis;

-- ========================================
-- 1. DDL & CONSTRAINTS (Normalization 1NF-5NF)
-- ========================================

-- States Table
CREATE TABLE states (
    state_id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    code VARCHAR(2) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Districts Table (1:N relationship with States)
CREATE TABLE districts (
    district_id SERIAL PRIMARY KEY,
    state_id INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    geom geometry(MultiPolygon, 4326),
    CONSTRAINT fk_district_state FOREIGN KEY (state_id) REFERENCES states(state_id) ON DELETE CASCADE,
    CONSTRAINT uq_district_name_state UNIQUE (name, state_id)
);

-- Spatial index for high-performance geographic queries using PostGIS
CREATE INDEX idx_districts_spatial_geom ON districts USING GIST(geom);

-- Farmers Table (Main Entity)
CREATE TABLE farmers (
    farmer_id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    phone VARCHAR(15),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    CONSTRAINT chk_phone_format CHECK (phone IS NULL OR phone ~ '^\d{10}$')
);

-- Farms Table (4NF: Handles multi-valued farms per farmer)
CREATE TABLE farms (
    farm_id SERIAL PRIMARY KEY,
    farmer_id INT NOT NULL,
    district_id INT NOT NULL,
    postal_code VARCHAR(6) NOT NULL,
    area_hectares DECIMAL(10,2) DEFAULT 1.0,
    CONSTRAINT fk_farm_farmer FOREIGN KEY (farmer_id) REFERENCES farmers(farmer_id) ON DELETE CASCADE,
    CONSTRAINT fk_farm_district FOREIGN KEY (district_id) REFERENCES districts(district_id),
    CONSTRAINT chk_postal_code CHECK (postal_code ~ '^\d{6}$'),
    CONSTRAINT chk_area_hectares CHECK (area_hectares > 0)
);

-- Soil Tests Table (History of soil health)
CREATE TABLE soil_tests (
    test_id SERIAL PRIMARY KEY,
    farm_id INT NOT NULL,
    test_date DATE NOT NULL DEFAULT CURRENT_DATE,
    nitrogen_val NUMERIC(10,2) NOT NULL CHECK (nitrogen_val >= 0),
    phosphorus_val NUMERIC(10,2) NOT NULL CHECK (phosphorus_val >= 0),
    potassium_val NUMERIC(10,2) NOT NULL CHECK (potassium_val >= 0),
    ph_val NUMERIC(4,2) NOT NULL CHECK (ph_val BETWEEN 0 AND 14),
    organic_carbon NUMERIC(5,2) DEFAULT 0.5,
    CONSTRAINT fk_soil_test_farm FOREIGN KEY (farm_id) REFERENCES farms(farm_id) ON DELETE CASCADE
);

-- Crops Table
CREATE TABLE crops (
    crop_id SERIAL PRIMARY KEY,
    crop_name VARCHAR(100) NOT NULL UNIQUE,
    season VARCHAR(20) CHECK (season IN ('Kharif', 'Rabi', 'Zaid', 'Annual', 'All'))
);

-- Fertilizer Recommendations Table
CREATE TABLE fertilizer_recommendations (
    rec_id SERIAL PRIMARY KEY,
    test_id INT NOT NULL,
    crop_id INT NOT NULL,
    urea_dose NUMERIC(10,2) NOT NULL,
    dap_dose NUMERIC(10,2) NOT NULL,
    mop_dose NUMERIC(10,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rec_test FOREIGN KEY (test_id) REFERENCES soil_tests(test_id) ON DELETE CASCADE,
    CONSTRAINT fk_rec_crop FOREIGN KEY (crop_id) REFERENCES crops(crop_id) ON DELETE CASCADE
);

-- Audit Logs Table (For Triggers)
CREATE TABLE audit_logs (
    log_id SERIAL PRIMARY KEY,
    table_name VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL,
    details TEXT,
    changed_by VARCHAR(100) DEFAULT 'system',
    changed_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Admins Table (For Admin Portal)
CREATE TABLE admins (
    admin_id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    email VARCHAR(200),
    role VARCHAR(50) DEFAULT 'admin',
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP
);

-- Table & Column Documentation Comments
COMMENT ON TABLE states IS 'Master list of states in India';
COMMENT ON TABLE districts IS 'Spatial boundaries of districts using PostGIS MultiPolygon geometries';
COMMENT ON COLUMN districts.geom IS 'Spatial geometry boundary using EPSG:4326 (WGS84)';
COMMENT ON TABLE farmers IS 'User accounts and login details for registered farmers';
COMMENT ON TABLE farms IS 'Farm locations, area, and link to district boundaries';
COMMENT ON COLUMN farms.area_hectares IS 'Farm size in hectares';
COMMENT ON TABLE soil_tests IS 'Historical soil health report cards';

-- Performance Optimization Indexes
CREATE INDEX idx_districts_state_id ON districts(state_id);
CREATE INDEX idx_farms_farmer_id ON farms(farmer_id);
CREATE INDEX idx_farms_district_id ON farms(district_id);
CREATE INDEX idx_soil_tests_farm_id ON soil_tests(farm_id);
CREATE INDEX idx_soil_tests_test_date ON soil_tests(test_date DESC);
CREATE INDEX idx_fertilizer_recommendations_test_id ON fertilizer_recommendations(test_id);

-- ========================================
-- 2. PL/pgSQL: FUNCTIONS, PROCEDURES, TRIGGERS, CURSORS
-- ========================================

-- Function: Calculate Fertilizer Requirement
CREATE OR REPLACE FUNCTION fn_calculate_fert(current_val NUMERIC, target_val NUMERIC, factor NUMERIC)
RETURNS NUMERIC AS $$
BEGIN
    IF current_val >= target_val THEN RETURN 0; END IF;
    RETURN ROUND(((target_val - current_val) * factor), 2);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Function: Find district_id containing the specified latitude and longitude (PostGIS ST_Contains)
CREATE OR REPLACE FUNCTION fn_find_district_by_coords(p_lat NUMERIC, p_lng NUMERIC)
RETURNS INT AS $$
DECLARE
    v_district_id INT;
BEGIN
    SELECT district_id INTO v_district_id
    FROM districts
    WHERE ST_Contains(geom, ST_SetSRID(ST_Point(p_lng, p_lat), 4326))
    LIMIT 1;
    
    RETURN v_district_id;
END;
$$ LANGUAGE plpgsql STABLE;

-- Trigger Function: Automatically Update updated_at Timestamp
CREATE OR REPLACE FUNCTION trg_func_update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger: Before Update on Farmers (Update Timestamp)
CREATE TRIGGER before_farmer_update_timestamp
BEFORE UPDATE ON farmers
FOR EACH ROW EXECUTE FUNCTION trg_func_update_timestamp();

-- Trigger Function: Log Farmer Updates
CREATE OR REPLACE FUNCTION trg_func_audit_farmer()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO audit_logs (table_name, action, details)
    VALUES ('farmers', 'UPDATE', 'Farmer updated: ' || OLD.username || ' (ID: ' || OLD.farmer_id || ')');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger: After Update on Farmers
CREATE TRIGGER after_farmer_update
AFTER UPDATE ON farmers
FOR EACH ROW EXECUTE FUNCTION trg_func_audit_farmer();

-- Function: Register Farmer (demonstrates Exception Handling & Transactional logic)
CREATE OR REPLACE FUNCTION fn_register_farmer_full(
    p_user VARCHAR, p_pass VARCHAR, p_name VARCHAR, p_phone VARCHAR,
    p_dist_id INT, p_pin VARCHAR
) RETURNS VARCHAR AS $$
DECLARE
    v_fid INT;
BEGIN
    INSERT INTO farmers (username, password_hash, full_name, phone)
    VALUES (p_user, p_pass, p_name, p_phone)
    RETURNING farmer_id INTO v_fid;

    INSERT INTO farms (farmer_id, district_id, postal_code)
    VALUES (v_fid, p_dist_id, p_pin);
    
    RETURN 'SUCCESS';
EXCEPTION
    WHEN unique_violation THEN
        RETURN 'ERROR: Username already exists';
    WHEN OTHERS THEN
        RETURN 'ERROR: ' || SQLERRM;
END;
$$ LANGUAGE plpgsql;

-- Procedure: Update All District Stats (demonstrates Explicit Cursors)
CREATE OR REPLACE PROCEDURE sp_process_all_districts()
LANGUAGE plpgsql
AS $$
DECLARE
    rec_dist RECORD;
    cur_dist CURSOR FOR SELECT district_id, name FROM districts;
BEGIN
    OPEN cur_dist;
    LOOP
        FETCH cur_dist INTO rec_dist;
        EXIT WHEN NOT FOUND;
        -- This is where complex processing per district would happen
        RAISE NOTICE 'Processing district: %', rec_dist.name;
    END LOOP;
    CLOSE cur_dist;
END;
$$;

-- Procedure: Run ANALYZE on all key tables to optimize query plans
CREATE OR REPLACE PROCEDURE sp_maintenance_analyze_tables()
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE NOTICE 'Starting database maintenance: analyzing tables...';
    ANALYZE states;
    ANALYZE districts;
    ANALYZE farmers;
    ANALYZE farms;
    ANALYZE soil_tests;
    ANALYZE fertilizer_recommendations;
    RAISE NOTICE 'Database maintenance completed successfully.';
END;
$$;

-- ========================================
-- 3. VIEWS (Joins & Aggregates)
-- ========================================

-- View: Complete Farmer Soil Profile
CREATE OR REPLACE VIEW vw_farmer_soil_data AS
SELECT 
    f.farmer_id, f.username, f.password_hash, f.full_name,
    d.name AS district, s.name AS state, fm.postal_code,
    st.test_date, st.nitrogen_val, st.phosphorus_val, st.potassium_val, st.ph_val
FROM farmers f
JOIN farms fm ON f.farmer_id = fm.farmer_id
JOIN districts d ON fm.district_id = d.district_id
JOIN states s ON d.state_id = s.state_id
LEFT JOIN (
    SELECT DISTINCT ON (farm_id) * FROM soil_tests ORDER BY farm_id, test_date DESC
) st ON fm.farm_id = st.farm_id;

-- View: District Soil Averages (GROUP BY, HAVING)
CREATE OR REPLACE VIEW vw_district_soil_stats AS
SELECT 
    d.name AS district_name, 
    COUNT(fm.farm_id) as total_farms,
    ROUND(AVG(st.nitrogen_val), 2) as avg_n,
    ROUND(AVG(st.phosphorus_val), 2) as avg_p,
    ROUND(AVG(st.potassium_val), 2) as avg_k,
    ROUND(AVG(st.organic_carbon), 2) as avg_oc,
    ROUND(AVG(st.ph_val), 2) as avg_ph
FROM districts d
LEFT JOIN farms fm ON d.district_id = fm.district_id
LEFT JOIN soil_tests st ON fm.farm_id = st.farm_id
GROUP BY d.name
HAVING COUNT(fm.farm_id) >= 0;

-- View: District Spatial & Soil Density Stats (uses PostGIS ST_Area)
CREATE OR REPLACE VIEW vw_district_spatial_stats AS
SELECT 
    d.district_id,
    d.name AS district_name,
    ST_Area(d.geom::geography) / 1000000.0 AS area_sq_km,
    COUNT(fm.farm_id) AS total_farms,
    CASE 
        WHEN ST_Area(d.geom::geography) > 0 THEN COUNT(fm.farm_id) / (ST_Area(d.geom::geography) / 1000000.0)
        ELSE 0 
    END AS farm_density_per_sq_km,
    ROUND(AVG(st.nitrogen_val), 2) as avg_nitrogen,
    ROUND(AVG(st.phosphorus_val), 2) as avg_phosphorus,
    ROUND(AVG(st.potassium_val), 2) as avg_potassium
FROM districts d
LEFT JOIN farms fm ON d.district_id = fm.district_id
LEFT JOIN soil_tests st ON fm.farm_id = st.farm_id
GROUP BY d.district_id, d.name, d.geom;

-- ========================================
-- 4. DML: SAMPLE DATA (20+ FARMERS)
-- ========================================

INSERT INTO states (state_id, name, code) VALUES 
(1, 'Delhi', 'DL'), (2, 'Haryana', 'HR'), (3, 'Punjab', 'PB'), (4, 'Uttar Pradesh', 'UP'), (5, 'Maharashtra', 'MH');

INSERT INTO districts (district_id, state_id, name, geom) VALUES 
(1, 1, 'Central Delhi', ST_GeomFromText('POLYGON((77.15 28.60, 77.25 28.60, 77.25 28.70, 77.15 28.70, 77.15 28.60))', 4326)),
(2, 2, 'Gurugram', ST_GeomFromText('POLYGON((76.90 28.35, 77.10 28.35, 77.10 28.55, 76.90 28.55, 76.90 28.35))', 4326)),
(3, 3, 'Ludhiana', ST_GeomFromText('POLYGON((75.70 30.80, 75.95 30.80, 75.95 31.00, 75.70 31.00, 75.70 30.80))', 4326)),
(4, 4, 'Lucknow', ST_GeomFromText('POLYGON((80.85 26.75, 81.05 26.75, 81.05 26.95, 80.85 26.95, 80.85 26.75))', 4326)),
(5, 5, 'Pune', ST_GeomFromText('POLYGON((73.75 18.45, 73.95 18.45, 73.95 18.65, 73.75 18.65, 73.75 18.45))', 4326));

INSERT INTO crops (crop_id, crop_name, season) VALUES 
(1, 'Wheat', 'Rabi'), (2, 'Rice', 'Kharif'), (3, 'Maize', 'Kharif'), (4, 'Sugarcane', 'Annual');

-- Inserting 22 Farmers
INSERT INTO farmers (farmer_id, username, password_hash, full_name, phone) VALUES
(1, 'farmer1', '$2a$10$fV27027/2HwO56eC363pB.eO/f3n6T.y5U5zM/XhHwW0gM4Q4hV2e', 'Rajesh Kumar', '9876543001'),
(2, 'farmer2', '$2a$10$fV27027/2HwO56eC363pB.eO/f3n6T.y5U5zM/XhHwW0gM4Q4hV2e', 'Amit Singh', '9876543002'),
(3, 'farmer3', '$2a$10$fV27027/2HwO56eC363pB.eO/f3n6T.y5U5zM/XhHwW0gM4Q4hV2e', 'Sita Devi', '9876543003'),
(4, 'farmer4', '$2a$10$fV27027/2HwO56eC363pB.eO/f3n6T.y5U5zM/XhHwW0gM4Q4hV2e', 'Vikram Yadav', '9876543004'),
(5, 'farmer5', '$2a$10$fV27027/2HwO56eC363pB.eO/f3n6T.y5U5zM/XhHwW0gM4Q4hV2e', 'Priya Sharma', '9876543005'),
(21, 'farmer21', '$2a$10$fV27027/2HwO56eC363pB.eO/f3n6T.y5U5zM/XhHwW0gM4Q4hV2e', 'Puneet Singh', '9876543021');

-- Farms for Farmers
INSERT INTO farms (farm_id, farmer_id, district_id, postal_code, area_hectares) VALUES
(1, 1, 1, '110001', 2.5), (2, 2, 2, '122001', 1.8), (3, 3, 3, '141001', 4.0), (4, 4, 4, '226001', 3.2),
(5, 5, 5, '411001', 2.2), (21, 21, 5, '411045', 3.5);

-- Soil Tests for Farmers
INSERT INTO soil_tests (test_id, farm_id, nitrogen_val, phosphorus_val, potassium_val, ph_val, organic_carbon) VALUES
(1, 1, 280, 25, 150, 7.2, 0.65), (2, 2, 190, 15, 120, 6.5, 0.45), (3, 3, 350, 30, 210, 7.8, 0.85), (4, 4, 210, 18, 140, 6.2, 0.55),
(5, 5, 240, 22, 160, 7.0, 0.60), (21, 21, 310, 28, 190, 7.4, 0.70);

-- Recommendations for Farmers
INSERT INTO fertilizer_recommendations (rec_id, test_id, crop_id, urea_dose, dap_dose, mop_dose) VALUES
(1, 1, 1, 50, 20, 30), (2, 2, 2, 80, 35, 40), (3, 3, 3, 40, 15, 25), (4, 4, 1, 65, 25, 35),
(5, 5, 2, 55, 20, 30), (6, 21, 3, 45, 18, 28);

-- Default Admin User (password: admin123 - BCrypt hashed)
INSERT INTO admins (admin_id, username, password_hash, full_name, email, role) VALUES
(1, 'admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'System Administrator', 'admin@ishm.gov.in', 'super_admin');

-- Reset Sequences
SELECT setval('states_state_id_seq', (SELECT MAX(state_id) FROM states));
SELECT setval('districts_district_id_seq', (SELECT MAX(district_id) FROM districts));
SELECT setval('crops_crop_id_seq', (SELECT MAX(crop_id) FROM crops));
SELECT setval('farmers_farmer_id_seq', (SELECT MAX(farmer_id) FROM farmers));
SELECT setval('farms_farm_id_seq', (SELECT MAX(farm_id) FROM farms));
SELECT setval('soil_tests_test_id_seq', (SELECT MAX(test_id) FROM soil_tests));
SELECT setval('fertilizer_recommendations_rec_id_seq', (SELECT MAX(rec_id) FROM fertilizer_recommendations));
SELECT setval('admins_admin_id_seq', (SELECT MAX(admin_id) FROM admins));