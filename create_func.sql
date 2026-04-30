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
