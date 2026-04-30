import hashlib

def hash_pass(pwd):
    return hashlib.sha256(pwd.encode()).hexdigest()

state_codes = {
    'Arunachal Pradesh': 'AR', 'Assam': 'AS', 'Bihar': 'BR', 'Delhi': 'DL', 'Gujarat': 'GJ',
    'Himachal Pradesh': 'HP', 'Karnataka': 'KA', 'Kerala': 'KL', 'Madhya Pradesh': 'MP',
    'Maharashtra': 'MH', 'Odisha': 'OR', 'Punjab': 'PB', 'Rajasthan': 'RJ', 'Tamil Nadu': 'TN',
    'Telangana': 'TG', 'Uttar Pradesh': 'UP', 'Uttarakhand': 'UK', 'West Bengal': 'WB'
}

users = [
    ('raj_kumar98', 'Raj Kumar', 'Uttar Pradesh', '226001'),
    ('ananya.mitra', 'Ananya Mitra', 'West Bengal', '700091'),
    ('vivek_pat97', 'Vivek Patil', 'Maharashtra', '411014'),
    ('harshita_gupta', 'Harshita Gupta', 'Delhi', '110092'),
    ('karthik_r', 'Karthik Reddy', 'Telangana', '500081'),
    ('sneha.nair', 'Sneha Nair', 'Kerala', '682020'),
    ('aman_singh01', 'Aman Singh', 'Punjab', '160022'),
    ('priya_sharma22', 'Priya Sharma', 'Rajasthan', '302017'),
    ('arjun_menon', 'Arjun Menon', 'Karnataka', '560037'),
    ('neha_das', 'Neha Das', 'Odisha', '751024'),
    ('rohit_verma007', 'Rohit Verma', 'Uttar Pradesh', '226001'),
    ('pooja_choudhary', 'Pooja Choudhary', 'Madhya Pradesh', '462016'),
    ('imran_khan786', 'Imran Khan', 'Gujarat', '380015'),
    ('deepak_rawat', 'Deepak Rawat', 'Uttarakhand', '248001'),
    ('kavya_iyer', 'Kavya Iyer', 'Tamil Nadu', '600113'),
    ('rahul_borah', 'Rahul Borah', 'Assam', '781036'),
    ('shalini_jain', 'Shalini Jain', 'Rajasthan', '302017'),
    ('manoj_thakur', 'Manoj Thakur', 'Himachal Pradesh', '171001'),
    ('farhan_ali', 'Farhan Ali', 'Bihar', '800020'),
    ('tashi_dolma', 'Tashi Dolma', 'Arunachal Pradesh', '791111')
]

states_set = sorted(list(set([u[2] for u in users])))
state_map = {name: i+1 for i, name in enumerate(states_set)}

sql = []
sql.append('DELETE FROM fertilizer_recommendations;')
sql.append('DELETE FROM soil_tests;')
sql.append('DELETE FROM farms;')
sql.append('DELETE FROM farmers;')
sql.append('DELETE FROM districts;')
sql.append('DELETE FROM states;')

for name, sid in state_map.items():
    code = state_codes.get(name, name[:2].upper())
    sql.append("INSERT INTO states (state_id, name, code) VALUES (%d, '%s', '%s');" % (sid, name, code))

district_map = {}
dist_id_counter = 1
for user, name, state, pin in users:
    state_id = state_map[state]
    dist_name = "%s District %s" % (state, pin[:3])
    if (state_id, dist_name) not in district_map:
        sql.append("INSERT INTO districts (district_id, state_id, name) VALUES (%d, %d, '%s');" % (dist_id_counter, state_id, dist_name))
        district_map[(state_id, dist_name)] = dist_id_counter
        dist_id_counter += 1

for i, (user, name, state, pin) in enumerate(users):
    fid = i + 1
    pwd = hash_pass(user)
    sql.append("INSERT INTO farmers (farmer_id, username, password_hash, full_name, phone) VALUES (%d, '%s', '%s', '%s', '9876543%03d');" % (fid, user, pwd, name, fid))
    
    state_id = state_map[state]
    dist_name = "%s District %s" % (state, pin[:3])
    did = district_map[(state_id, dist_name)]
    sql.append("INSERT INTO farms (farm_id, farmer_id, district_id, postal_code) VALUES (%d, %d, %d, '%s');" % (fid, fid, did, pin))
    
    sql.append("INSERT INTO soil_tests (test_id, farm_id, nitrogen_val, phosphorus_val, potassium_val, ph_val) VALUES (%d, %d, %d, %d, %d, %.1f);" % (fid, fid, 200+i*5, 20+i, 150+i*2, 6.5 + (i%10)*0.1))
    
    sql.append("INSERT INTO fertilizer_recommendations (rec_id, test_id, crop_id, urea_dose, dap_dose, mop_dose) VALUES (%d, %d, 1, %d, %d, %d);" % (fid, fid, 50+i, 20+i, 30+i))

sql.append("SELECT setval('states_state_id_seq', (SELECT MAX(state_id) FROM states));")
sql.append("SELECT setval('districts_district_id_seq', (SELECT MAX(district_id) FROM districts));")
sql.append("SELECT setval('farmers_farmer_id_seq', (SELECT MAX(farmer_id) FROM farmers));")
sql.append("SELECT setval('farms_farm_id_seq', (SELECT MAX(farm_id) FROM farms));")
sql.append("SELECT setval('soil_tests_test_id_seq', (SELECT MAX(test_id) FROM soil_tests));")
sql.append("SELECT setval('fertilizer_recommendations_rec_id_seq', (SELECT MAX(rec_id) FROM fertilizer_recommendations));")

with open('populate_users.sql', 'w') as f:
    f.write('\n'.join(sql))
