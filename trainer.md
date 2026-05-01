# 🎓 Trainee Guide: Interactive Soil Health Mapping (ISHM) System

Welcome to the ISHM project! This document will help you understand the architecture, tools, and data flow of the application.

---

## 1. Project Overview
The **Interactive Soil Health Mapping (ISHM)** system is a full-stack platform designed to help farmers and administrators monitor soil quality across different regions. It provides real-time visualization of soil nutrients (NPK), personalized fertilizer recommendations, and district-wise analytics.

---

## 2. Technology Stack & Tools (The "Why")

| Tool | Purpose | Why we use it? |
|------|---------|----------------|
| **Micronaut 4.x** | Backend Framework | High performance, low memory footprint, and fast startup (perfect for Docker/Serverless). |
| **PostgreSQL** | Primary Database | Robust, open-source relational database that handles complex joins and transactions. |
| **PostGIS** | Spatial Extension | Adds support for geographic objects, allowing us to store and query map boundaries (polygons). |
| **JWT** | Authentication | Stateless security. The server doesn't need to store sessions; the token contains all identity info. |
| **Gradle** | Build Tool | Automates dependency management and builds the executable JAR file. |
| **Leaflet.js** | Mapping Library | Lightweight and mobile-friendly library for rendering interactive maps in the browser. |
| **Chart.js** | Data Visualization | Simple yet powerful way to render soil health trends and distributions. |
| **Docker** | Containerization | Ensures the app runs the same way on every machine (Developer's PC vs. Production Server). |

---

## 3. Core Components

### 🏗️ Backend (Java/Micronaut)
Located in `src/main/java/com/ishm/`.
- **Controllers**: Handle HTTP requests (e.g., `MapController`, `AuthController`).
- **Security**: Managed via JWT. See `micronaut-security-jwt` in `build.gradle`.
- **Logic**: Calculations for fertilizer doses are handled here or via DB functions.

### 🎨 Frontend (HTML/CSS/JS)
Located in `src/main/resources/public/`.
- **Dashboard**: `dashboard.html` for stats and charts.
- **Map View**: `map.html` for Leaflet-based visualization.
- **Recommendations**: `recommendations.html` for the tool that calculates fertilizer needs.

### 🗄️ Database (PostgreSQL + PostGIS)
Located in `schema/schema.sql`.
- **Normalization**: Tables are split (States -> Districts -> Farms) to avoid redundancy (up to 4NF).
- **Views**: We use `vw_district_soil_stats` to pre-calculate averages so the API stays fast.
- **PostGIS**: Stores `geom` data as `MultiPolygon`.

### 🔐 Security & Authentication
- **Stateless**: We use JWT (JSON Web Tokens) so the server doesn't need to track sessions.
- **Payload**: The token carries the `farmer_id` and `username` encrypted in its payload.
- **Interceptors**: Micronaut Security intercepts requests to `/api/dashboard/*` and `/api/recommendations/*` to ensure only logged-in users can access them.

---

## 4. How the Database is Connected

1.  **Driver**: The project uses the `postgresql` JDBC driver.
2.  **Pool**: **HikariCP** (Connection Pool) is used to maintain a set of open connections, so we don't waste time opening a new one for every request.
3.  **Configuration**: Credentials are kept in `.env` and mapped to Micronaut via `application.yml`.
4.  **Execution**: We use `javax.sql.DataSource` to get a connection and run SQL queries using `PreparedStatement` to prevent SQL Injection.

---

## 5. The Complete Data Flow

Let's trace a single request: **"Show me the Soil Health Map for Delhi"**

1.  **Frontend**: The user opens `index.html`. The JavaScript calls `fetch('/api/map/districts?state=Delhi')`.
2.  **Backend (Controller)**: `MapController.getDistricts()` receives the request.
3.  **Database Query**: The controller runs a SQL query joining `districts`, `states`, and the `vw_district_soil_stats` view.
    - It uses `ST_AsGeoJSON(geom)` to convert the map shape into a format JavaScript understands.
4.  **JSON Response**: The Backend converts the SQL result set into a **GeoJSON** object and sends it back to the browser.
5.  **Rendering**: **Leaflet.js** receives the GeoJSON and draws the district boundaries on the screen, coloring them based on nitrogen levels (Low/Medium/High).

---

## 6. Key Database Concepts in ISHM

- **DDL (Data Definition Language)**: Creating tables like `farmers`, `soil_tests`.
- **DML (Data Manipulation Language)**: Inserting sample data for 20+ farmers.
- **Triggers**: When a farmer's profile is updated, a trigger automatically inserts a record into `audit_logs`.
- **Stored Procedures**: `fn_register_farmer_full` handles registration and farm creation in a single transaction (Atomic).

---

## 7. How to Start Developing

1.  **Spin up the Database**: `docker-compose up db -d`
2.  **Run the App**: `./gradlew run`
3.  **Check Health**: Go to `http://localhost:8080/health`
4.  **View Logs**: Look at the console or `logs/` directory to debug issues.

---

### 💡 Pro Tip for Trainees:
Always check `vw_farmer_soil_data` if you want to see how different tables are joined together. It is the "Master View" of the project!
