# ISHM Fertilizer Recommendations – README (Formulas & Implementation)

> Scope: This doc explains **what the code does**, the **math behind it**, and the **API surface**. It’s built on demo data (inline + a sample table). In prod, nutrient/crop requirements will come from proper **APIs/DBs**, not hard-coded.

---

## Overview

`RecommendationController` (Micronaut) exposes three endpoints:

* `POST /api/recommendations/calculate` → compute N-P-K status and fertilizer doses + schedule/tips
* `GET  /api/recommendations/crops` → list crop requirement ranges (demo DB table)
* `POST /api/recommendations/save` → stub that pretends to save (returns OK without DB write)

### High-level flow (`/calculate`)

1. Validate `crop`.
2. Resolve soil nutrients (N, P, K):

    * Use request values **if provided**, else **estimate** from latest district averages (`soil_health_data … ORDER BY measurement_year DESC LIMIT 1`).
    * If DB/District not available → fall back to defaults (N=250, P=15, K=150).
3. Classify nutrient status (Low/Medium/High) using fixed thresholds.
4. Load **crop nutrient requirements** (demo map; e.g., rice: N150-P60-K60).
5. Compute **fertilizer product doses** (Urea, DAP, MOP, SSP) from deficits.
6. If pH < 6.0 → compute lime dose.
7. Return schedule (basal/topdress) + tips.

---

## Request & Response

### Request body (JSON)

```json
{
  "state": "Tamil Nadu",
  "district": "Chengalpattu",
  "crop": "rice",
  "season": "kharif",
  "nitrogen": 260,
  "phosphorus": null,
  "potassium": null,
  "ph": 5.8,
  "organicCarbon": 0.6,
  "ec": 0.3
}
```

* Any of `nitrogen|phosphorus|potassium` can be `null` → will be **estimated**.

### Response body (example)

```json
{
  "nitrogenStatus": "Medium",
  "phosphorusStatus": "Low",
  "potassiumStatus": "Medium",
  "ureaDose": 141.3,
  "dapDose": 108.7,
  "mopDose": 66.7,
  "sspDose": 312.5,
  "schedule": {
    "basal": "Apply 50% of N (141.3 kg Urea), full P (108.7 kg DAP) and K (66.7 kg MOP) at sowing",
    "firstTopdress": "20-25 days after transplanting",
    "secondTopdress": "45-50 days after transplanting"
  },
  "tips": [
    "Apply fertilizers when soil has adequate moisture",
    "Avoid fertilizer application during heavy rain",
    "Phosphorus deficiency may delay maturity - monitor crop closely",
    "Conduct soil testing every 2-3 years for best results"
  ],
  "limeRequired": true,
  "limeDose": 1000.0
}
```

> **Units**: The controller assumes **kg/ha** planning targets and interprets soil test numbers consistently with thresholds below. If your upstream sources differ (e.g., ppm, kg/ha, %OC), normalize before calling this service (see “Production notes” at end).

---

## Endpoints

### 1) Calculate

```
POST /api/recommendations/calculate
Content-Type: application/json
```

**cURL**

```bash
curl -X POST http://localhost:8080/api/recommendations/calculate \
  -H 'Content-Type: application/json' \
  -d '{"crop":"rice","state":"Tamil Nadu","district":"Chengalpattu","ph":5.8}'
```

### 2) Crop list (demo table-backed)

```
GET /api/recommendations/crops
```

### 3) Save (stub)

```
POST /api/recommendations/save
```

Returns `{ "message": "Recommendation saved successfully" }`, no persistence.

---

## Nutrient Status Classification

```text
N (available N in soil):
  Low    : < 280
  Medium : 280 – 560
  High   : > 560

P (available P in soil):
  Low    : < 10
  Medium : 10 – 25
  High   : > 25

K (available K in soil):
  Low    : < 110
  Medium : 110 – 280
  High   : > 280
```

> These thresholds are hard-coded demo cutoffs. In prod, put them in a table/config so regions/crops can override.

---

## Crop Nutrient Requirements (demo)

Per-crop **target application** (kg/ha of nutrient, not product):

| Crop      | N   | P   | K   |
| --------- | --- | --- | --- |
| wheat     | 120 | 60  | 60  |
| rice      | 150 | 60  | 60  |
| maize     | 120 | 60  | 40  |
| cotton    | 160 | 80  | 60  |
| sugarcane | 300 | 100 | 150 |
| mustard   | 80  | 40  | 40  |
| tomato    | 150 | 80  | 100 |
| potato    | 180 | 80  | 120 |

Fallback default: **N=100, P=50, K=50**.

---

## Product Content Assumptions

| Product | Nutrient form    | % nutrient |
| ------: | ---------------- | ---------: |
|    Urea | N                |      46% N |
|     DAP | P₂O₅ (and 18% N) |   46% P₂O₅ |
|     MOP | K₂O              |    60% K₂O |
|     SSP | P₂O₅             |   16% P₂O₅ |

> Note: Controller **does not** convert P₂O₅→P or K₂O→K. It compares target “P”/“K” against soil P/K and then divides deficit by **P₂O₅/K₂O product percentages**. If your agronomy standard plans in elemental P & K, add the standard conversion factors:
>
> * `P₂O₅ × 0.436 = P`, `P × 2.29 = P₂O₅`
> * `K₂O × 0.830 = K`, `K × 1.20 = K₂O`
    >   Adjust targets/percentages accordingly in prod.

---

## Deficit & Dose Formulas

Let:

* `ReqN, ReqP, ReqK` = crop **target** nutrient application (kg/ha)
* `SoilN, SoilP, SoilK` = available nutrients from test or estimate
* `AvailFrac` = assumed availability of tested nutrient toward current crop target (**0.5** in demo)
* Product contents: `UreaN=0.46`, `DAP_P2O5=0.46`, `MOP_K2O=0.60`, `SSP_P2O5=0.16`

### 1) Effective soil contribution (demo assumption)

```
EffN = SoilN * AvailFrac
EffP = SoilP * AvailFrac
EffK = SoilK * AvailFrac
where AvailFrac = 0.5
```

### 2) Nutrient deficits (kg/ha)

```
DefN = max(0, ReqN - EffN)
DefP = max(0, ReqP - EffP)
DefK = max(0, ReqK - EffK)
```

### 3) Product dose (kg/ha)

```
UreaDose = round1( DefN / 0.46 )

DAPDose  = round1( DefP / 0.46 )     // P₂O₅ basis
MOPDose  = round1( DefK / 0.60 )     // K₂O basis
SSPDose  = round1( DefP / 0.16 )     // P₂O₅ basis (alternative to DAP)
```

`round1(x)` = round to **1 decimal**.

> **Important**: The controller currently **computes both DAP and SSP** from the same P-deficit; in real plans you’d **choose** one or **mix** with explicit rules (and account for DAP’s 18% N bonus into the N plan).

---

## pH & Lime

Simple demo rule:

```
if pH < 5.5 → limeDose = 2000 kg/ha
else if pH < 6.0 → limeDose = 1000 kg/ha
else → limeDose = 500 kg/ha
```

Returned only if `pH < 6.0`.

---

## Scheduling (Basal & Topdress)

* **Basal** text is generated from computed doses:

  ```
  "Apply 50% of N (<UreaDose> kg Urea), full P (<DAPDose> kg DAP) and K (<MOPDose> kg MOP) at sowing"
  ```

  (This is a **string**. The code doesn’t split the N half into actual kg of Urea; it prints the full UreaDose inside the sentence. Adjust in prod if you want exact basal/topdress splits in **product kg**.)

* **Topdress** windows:

    * Wheat: 30–35 DAS, 60–65 DAS
    * Rice: 20–25 DAT, 45–50 DAT
    * Maize: 25–30 DAS, 50–55 DAS
    * Cotton: 40–45 DAS, 70–75 DAS
    * Sugarcane: 45–50 DAP, 90–100 DAP
    * Default: 30 and 60 days post sowing/planting

---

## Estimation Logic (when N/P/K missing)

If `district` & `state` present:

```sql
SELECT <nutrient>_avg
FROM soil_health_data s
JOIN districts d ON s.district_id = d.id
WHERE d.name = ? AND d.state_name = ?
ORDER BY s.measurement_year DESC
LIMIT 1;
```

If query fails/empty → defaults:

```
N=250, P=15, K=150
```

---

## Tips Rules (examples)

* Always: moisture timing, avoid heavy rain, test soil every 2–3 years
* If Low N: add organic manure
* If Low P: warn delayed maturity
* If Low K: warn disease resistance

---

## Error Handling

* Missing `crop` → `400 { "error": "Crop selection is required" }`
* Any internal error → `500 { "error": "Failed to calculate recommendations" }`

---

## Code Pseudocode

```java
POST /calculate:
  assert crop
  N = req.N != null ? req.N : estimate(req, "nitrogen")
  P = req.P != null ? req.P : estimate(req, "phosphorus")
  K = req.K != null ? req.K : estimate(req, "potassium")

  nStatus = classify(N, "N")
  pStatus = classify(P, "P")
  kStatus = classify(K, "K")

  reqs = getCropRequirements(crop)

  urea = round1( max(0, reqs.N - N*0.5) / 0.46 )
  dap  = round1( max(0, reqs.P - P*0.5) / 0.46 )
  mop  = round1( max(0, reqs.K - K*0.5) / 0.60 )
  ssp  = round1( max(0, reqs.P - P*0.5) / 0.16 )

  if (ph != null && ph < 6.0) lime = (ph < 5.5 ? 2000 : 1000) else 500 (not returned)

  schedule = {
    basal: "Apply 50% of N (" + urea + " kg Urea), full P (" + dap + " kg DAP) and K (" + mop + " kg MOP) at sowing",
    firstTopdress: byCrop1(crop),
    secondTopdress: byCrop2(crop)
  }

  tips = rules(nStatus, pStatus, kStatus)

  return { statuses, doses, schedule, tips, lime? }
```

---

## Local Run (dev)

* Micronaut app; ensure a JDBC `DataSource` is configured if you want `/crops` and estimation queries to work.
* For demo mode you can ignore DB; estimation falls back to defaults.

---

## Production Notes (what changes)

1. **External data**

    * Replace `getCropRequirements` hard-map with a **requirements API/table** (crop, variety, season, region).
    * Replace estimation SQL with **official soil/SHC API**; add caching + staleness rules.
    * Store **classification thresholds** per region/soil type.

2. **Units & normalization** (do this first)

    * Standardize soil test units (ppm vs kg/ha).
    * Decide **planning basis**: elemental P/K **or** oxide forms.
    * If using DAP/MOP/SSP, convert targets to **P₂O₅/K₂O** or back-convert product to elemental equivalents.
    * If using DAP, subtract **its N (18%)** from the N plan before calculating Urea.

3. **Splitting real basal/topdress**

    * Return **structured splits**: exact product kg at each stage, not just a sentence.

4. **Persistence & Auth**

    * Implement `/save` with user auth, tenanting, versioning.
    * Store runs, inputs (soil source + timestamp), chosen products, and final plan.

5. **Validation**

    * Strong schema validation (ranges, pH 0–14, EC, OC), crop whitelist, district mapping.

6. **Explainability**

    * Return a `calculationBreakdown` object: inputs → conversions → deficits → product mapping.

7. **Safety rails**

    * Upper caps for per-application dose, lockout on heavy rains (weather API), irrigation guardrails.

---

## Quick Examples

**Rice, no soil values (district provided)**

```bash
curl -X POST http://localhost:8080/api/recommendations/calculate \
  -H 'Content-Type: application/json' \
  -d '{"crop":"rice","state":"TN","district":"Chengalpattu"}'
```

**Wheat, full soil values**

```bash
curl -X POST http://localhost:8080/api/recommendations/calculate \
  -H 'Content-Type: application/json' \
  -d '{"crop":"wheat","nitrogen":220,"phosphorus":8,"potassium":180,"ph":5.4}'
```

---

## “Gotchas” in this demo

* **DAP’s N (18%) is ignored** in the N balance. In real plans, count it.
* **SSP vs DAP**: both are computed; you should **choose** one (or compute a mix) in the final plan.
* **P/K units**: targets are used as-is against soil P/K then divided by **oxide %**. Align to your agronomy standard in prod.
* **Basal string** prints full Urea/DAP/MOP numbers while claiming “50% of N” → stylistic mismatch. Split doses explicitly in prod.

---

## File Map (relevant parts)

* `RecommendationController`

    * `POST /calculate` – main engine
    * `classifyNutrientLevel` – thresholds
    * `estimateNutrient` – district → avg or defaults
    * `getCropRequirements` – demo map
    * `calculateUreaDose / DAP / MOP / SSP` – dose math
    * `calculateLimeDose` – pH simple rule
    * `generate*` – schedule & tips
    * Models: `RecommendationRequest`, `CropRequirements`

---

**TL;DR**: This controller is a **demo calculator** showing how to wire inputs → classify → compute deficits → map to product kg/ha with a couple of scheduling strings. For production, wire in **auth, real agronomy data, unit normalization, DAP-N accounting, and structured dose splitting**.
