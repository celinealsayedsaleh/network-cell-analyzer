"""
Network Cell Analyzer — Flask Server
EECE 451 · American University of Beirut

Matched to the new Android app (with login screen).

What the app POSTs to /api/report:
  report_id, device_id, user_name (from login), operator, network_type,
  signal_power, sinr_snr, frequency_band, cell_id, timestamp

No device_nickname — removed everywhere.

Endpoints:
  POST /api/report      ← Android app
  GET  /api/stats       ← dashboard
  GET  /api/devices     ← dashboard
  GET  /api/recent      ← dashboard measurements tab
  GET  /api/handovers   ← dashboard sidebar
  GET  /api/quality     ← dashboard sidebar
  GET  /                ← serves templates/dashboard.html
"""

import sqlite3, time, threading
from datetime import datetime
from flask import Flask, request, jsonify, render_template, g

app = Flask(__name__)
DB_PATH          = "cell_data.db"
LIVE_TIMEOUT_SEC = 30

device_registry = {}
registry_lock   = threading.Lock()

def live_count():
    now = time.time()
    with registry_lock:
        return sum(1 for d in device_registry.values()
                   if now - d.get("last_seen_ts", 0) <= LIVE_TIMEOUT_SEC)

# ── DB ────────────────────────────────────────────────────────────────────────

def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH, detect_types=sqlite3.PARSE_DECLTYPES)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA journal_mode=WAL")
        g.db.execute("PRAGMA foreign_keys=ON")
    return g.db

@app.teardown_appcontext
def close_db(exc=None):
    db = g.pop("db", None)
    if db: db.close()

def _safe_add_column(db, table, col, col_type):
    try:
        existing = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
        if col not in existing:
            db.execute(f"ALTER TABLE {table} ADD COLUMN {col} {col_type}")
            print(f"[DB] Added column {table}.{col}")
    except Exception as e:
        print(f"[DB] Migration warning ({table}.{col}): {e}")

def init_db():
    with app.app_context():
        db = get_db()
        db.executescript("""
            CREATE TABLE IF NOT EXISTS devices (
                device_id    TEXT PRIMARY KEY,
                user_name    TEXT,
                first_seen   INTEGER NOT NULL,
                last_seen    INTEGER NOT NULL,
                report_count INTEGER DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS connections (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id    TEXT NOT NULL,
                ip_address   TEXT NOT NULL,
                mac_address  TEXT,
                connected_at INTEGER NOT NULL,
                FOREIGN KEY (device_id) REFERENCES devices(device_id)
            );
            CREATE TABLE IF NOT EXISTS cell_reports (
                report_id      TEXT PRIMARY KEY,
                device_id      TEXT NOT NULL,
                operator       TEXT NOT NULL,
                network_type   TEXT NOT NULL,
                signal_power   INTEGER NOT NULL,
                sinr_snr       REAL,
                frequency_band TEXT,
                cell_id        TEXT,
                timestamp      INTEGER NOT NULL,
                received_at    INTEGER NOT NULL,
                FOREIGN KEY (device_id) REFERENCES devices(device_id)
            );
            CREATE INDEX IF NOT EXISTS idx_reports_ts     ON cell_reports(timestamp);
            CREATE INDEX IF NOT EXISTS idx_reports_device ON cell_reports(device_id);
            CREATE INDEX IF NOT EXISTS idx_reports_type   ON cell_reports(network_type);
        """)
        # Safe migrations for anyone with an older DB
        _safe_add_column(db, "devices", "user_name", "TEXT")
        _safe_add_column(db, "connections", "mac_address", "TEXT")
        db.commit()
        print("[DB] Initialised at", DB_PATH)

def classify_signal(dbm):
    if dbm >= -70:  return "Excellent"
    if dbm >= -85:  return "Good"
    if dbm >= -100: return "Fair"
    return "Poor"

def _fmt_ts(ms):
    if ms is None: return "—"
    return datetime.fromtimestamp(ms / 1000).strftime("%d %b %Y %H:%M:%S")

# ── POST /api/report ──────────────────────────────────────────────────────────

@app.route("/api/report", methods=["POST"])
def ingest_report():
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"error": "Invalid JSON"}), 400

    required = ("report_id", "device_id", "operator", "network_type",
                "signal_power", "timestamp")
    missing = [f for f in required if f not in data]
    if missing:
        return jsonify({"error": f"Missing fields: {missing}"}), 400

    device_id = data["device_id"]
    now_ms    = int(time.time() * 1000)
    client_ip = request.remote_addr or "unknown"
    user_name = (data.get("user_name") or "").strip() or None

    db = get_db()

    db.execute("""
        INSERT INTO devices (device_id, user_name, first_seen, last_seen, report_count)
        VALUES (?, ?, ?, ?, 1)
        ON CONFLICT(device_id) DO UPDATE SET
            last_seen    = excluded.last_seen,
            report_count = report_count + 1,
            user_name    = COALESCE(excluded.user_name, devices.user_name)
    """, (device_id, user_name, now_ms, now_ms))

    last = db.execute(
        "SELECT ip_address FROM connections WHERE device_id=? "
        "ORDER BY connected_at DESC LIMIT 1", (device_id,)
    ).fetchone()
    if last is None or last["ip_address"] != client_ip:
        db.execute(
            "INSERT INTO connections (device_id, ip_address, mac_address, connected_at) VALUES (?,?,?,?)",
            (device_id, client_ip, data.get("mac_address"), now_ms)
        )

    try:
        db.execute("""
            INSERT OR IGNORE INTO cell_reports
                (report_id, device_id, operator, network_type,
                 signal_power, sinr_snr, frequency_band, cell_id,
                 timestamp, received_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
        """, (
            data["report_id"], device_id,
            data["operator"],  data["network_type"],
            int(data["signal_power"]), data.get("sinr_snr"),
            data.get("frequency_band", ""), data.get("cell_id", ""),
            int(data["timestamp"]), now_ms,
        ))
        db.commit()
    except sqlite3.IntegrityError as e:
        return jsonify({"error": str(e)}), 409

    with registry_lock:
        device_registry[device_id] = {
            "ip":            client_ip,
            "device_id":     device_id,
            "user_name":     user_name or "—",
            "last_seen":     _fmt_ts(now_ms),
            "last_seen_ts":  time.time(),
            "last_operator": data["operator"],
            "last_network":  data["network_type"],
            "last_signal":   data["signal_power"],
        }

    return jsonify({
        "status":         "ok",
        "received_at":    now_ms,
        "signal_quality": classify_signal(int(data["signal_power"])),
    }), 201

# ── GET /api/stats ────────────────────────────────────────────────────────────

@app.route("/api/stats", methods=["GET"])
def get_stats():
    try:
        from_ms = int(request.args["from"])
        to_ms   = int(request.args["to"])
    except (KeyError, ValueError):
        to_ms   = int(time.time() * 1000)
        from_ms = to_ms - 7 * 86_400_000

    device_id = request.args.get("device_id")
    db        = get_db()
    df        = "AND device_id = ?" if device_id else ""
    base      = (from_ms, to_ms, device_id) if device_id else (from_ms, to_ms)

    rows  = db.execute(f"SELECT operator, COUNT(*) cnt FROM cell_reports "
                       f"WHERE timestamp BETWEEN ? AND ? {df} "
                       f"GROUP BY operator ORDER BY cnt DESC", base).fetchall()
    total = sum(r["cnt"] for r in rows) or 1
    operator_time = [{"operator": r["operator"],
                      "percentage": round(r["cnt"] / total * 100, 1)} for r in rows]

    rows  = db.execute(f"SELECT network_type, COUNT(*) cnt FROM cell_reports "
                       f"WHERE timestamp BETWEEN ? AND ? {df} "
                       f"GROUP BY network_type ORDER BY cnt DESC", base).fetchall()
    total = sum(r["cnt"] for r in rows) or 1
    network_type_time = [{"type": r["network_type"],
                          "percentage": round(r["cnt"] / total * 100, 1)} for r in rows]

    rows = db.execute(f"SELECT network_type, AVG(signal_power) avg_dbm FROM cell_reports "
                      f"WHERE timestamp BETWEEN ? AND ? {df} "
                      f"GROUP BY network_type ORDER BY network_type", base).fetchall()
    avg_signal_per_type = [{"type": r["network_type"],
                             "avg_dbm": round(r["avg_dbm"], 1)} for r in rows]

    rows = db.execute(f"SELECT device_id, AVG(signal_power) avg_dbm FROM cell_reports "
                      f"WHERE timestamp BETWEEN ? AND ? {df} "
                      f"GROUP BY device_id ORDER BY device_id", base).fetchall()
    avg_signal_per_device = [{"device_id": r["device_id"],
                               "avg_dbm": round(r["avg_dbm"], 1)} for r in rows]

    rows = db.execute(f"SELECT network_type, AVG(sinr_snr) avg_sinr FROM cell_reports "
                      f"WHERE timestamp BETWEEN ? AND ? {df} AND sinr_snr IS NOT NULL "
                      f"GROUP BY network_type ORDER BY network_type", base).fetchall()
    avg_sinr_per_type = [{"type": r["network_type"],
                          "avg_sinr": round(r["avg_sinr"], 1) if r["avg_sinr"] is not None else None}
                         for r in rows]

    q = db.execute(
        f"SELECT "
        f"COALESCE(SUM(CASE WHEN signal_power>=-70 THEN 1 ELSE 0 END),0) excellent,"
        f"COALESCE(SUM(CASE WHEN signal_power BETWEEN -85 AND -71 THEN 1 ELSE 0 END),0) good,"
        f"COALESCE(SUM(CASE WHEN signal_power BETWEEN -100 AND -86 THEN 1 ELSE 0 END),0) fair,"
        f"COALESCE(SUM(CASE WHEN signal_power<-100 THEN 1 ELSE 0 END),0) poor,"
        f"COUNT(*) total FROM cell_reports WHERE timestamp BETWEEN ? AND ? {df}", base
    ).fetchone()
    tq = q["total"] or 1

    return jsonify({
        "from_ms":               from_ms,
        "to_ms":                 to_ms,
        "operator_time":         operator_time,
        "network_type_time":     network_type_time,
        "avg_signal_per_type":   avg_signal_per_type,
        "avg_signal_per_device": avg_signal_per_device,
        "avg_sinr_per_type":     avg_sinr_per_type,
        "signal_quality_dist": {
            "excellent": round(q["excellent"] / tq * 100, 1),
            "good":      round(q["good"]      / tq * 100, 1),
            "fair":      round(q["fair"]       / tq * 100, 1),
            "poor":      round(q["poor"]       / tq * 100, 1),
        },
    })

# ── GET /api/devices ──────────────────────────────────────────────────────────

@app.route("/api/devices", methods=["GET"])
def get_devices():
    db   = get_db()
    rows = db.execute("""
        SELECT d.device_id, d.user_name, d.first_seen, d.last_seen, d.report_count,
               c.ip_address, c.mac_address
        FROM devices d
        LEFT JOIN connections c ON c.device_id = d.device_id
            AND c.connected_at = (
                SELECT MAX(connected_at) FROM connections WHERE device_id = d.device_id)
        ORDER BY d.last_seen DESC
    """).fetchall()

    return jsonify({
        "total_devices": len(rows),
        "live_count":    live_count(),
        "devices": [{
            "device_id":    r["device_id"],
            "user_name":    r["user_name"]  or "—",
            "ip_address":   r["ip_address"] or "—",
            "mac_address":  r["mac_address"] or "—",
            "first_seen":   _fmt_ts(r["first_seen"]),
            "last_seen":    _fmt_ts(r["last_seen"]),
            "report_count": r["report_count"],
        } for r in rows],
    })

# ── GET /api/recent ───────────────────────────────────────────────────────────

@app.route("/api/recent", methods=["GET"])
def get_recent():
    limit = min(int(request.args.get("limit", 50)), 200)
    db    = get_db()
    rows  = db.execute("""
        SELECT r.device_id, d.user_name, r.operator, r.network_type,
               r.signal_power, r.sinr_snr, r.frequency_band, r.cell_id, r.received_at
        FROM cell_reports r
        LEFT JOIN devices d ON d.device_id = r.device_id
        ORDER BY r.received_at DESC LIMIT ?
    """, (limit,)).fetchall()

    return jsonify({"count": len(rows), "recent": [{
        "device_id":      r["device_id"],
        "user_name":      r["user_name"] or "—",
        "operator":       r["operator"],
        "network_type":   r["network_type"],
        "signal_power":   r["signal_power"],
        "sinr_snr":       r["sinr_snr"],
        "frequency_band": r["frequency_band"] or "—",
        "cell_id":        r["cell_id"] or "—",
        "received_at":    _fmt_ts(r["received_at"]),
    } for r in rows]})

# ── GET /api/handovers ────────────────────────────────────────────────────────

@app.route("/api/handovers", methods=["GET"])
def get_handovers():
    device_id = request.args.get("device_id")
    limit     = min(int(request.args.get("limit", 50)), 200)
    db        = get_db()

    if device_id:
        rows = db.execute(
            "SELECT device_id,network_type,operator,signal_power,timestamp "
            "FROM cell_reports WHERE device_id=? ORDER BY timestamp ASC", (device_id,)
        ).fetchall()
    else:
        rows = db.execute(
            "SELECT device_id,network_type,operator,signal_power,timestamp "
            "FROM cell_reports ORDER BY device_id, timestamp ASC"
        ).fetchall()

    handovers, prev = [], {}
    for r in rows:
        did = r["device_id"]
        if did in prev:
            p = prev[did]
            if p["network_type"] != r["network_type"] or p["operator"] != r["operator"]:
                handovers.append({
                    "device_id":     did,
                    "timestamp":     _fmt_ts(r["timestamp"]),
                    "from_network":  p["network_type"],
                    "to_network":    r["network_type"],
                    "from_operator": p["operator"],
                    "to_operator":   r["operator"],
                    "signal_after":  r["signal_power"],
                    "type": "network" if p["network_type"] != r["network_type"] else "operator",
                })
        prev[did] = dict(r)

    return jsonify({"total": len(handovers),
                    "handovers": list(reversed(handovers))[:limit]})


# ── GET /api/measurements  (date-filtered, replaces /api/recent) ──────────────

@app.route("/api/measurements", methods=["GET"])
def get_measurements():
    """
    Returns measurements filtered by date-time range and optional device/operator/type.

    Query params:
      from_ms   (int ms, default 7 days ago)
      to_ms     (int ms, default now)
      device_id (optional)
      operator  (optional)
      network_type (optional, e.g. 4G)
      limit     (default 100, max 500)
      offset    (default 0, for pagination)
    """
    now_ms  = int(time.time() * 1000)
    try:
        from_ms = int(request.args["from_ms"])
    except (KeyError, ValueError):
        from_ms = now_ms - 7 * 86_400_000
    try:
        to_ms = int(request.args["to_ms"])
    except (KeyError, ValueError):
        to_ms = now_ms

    limit     = min(int(request.args.get("limit", 100)), 500)
    offset    = max(int(request.args.get("offset", 0)), 0)
    device_id = request.args.get("device_id")
    operator  = request.args.get("operator")
    net_type  = request.args.get("network_type")

    db     = get_db()
    wheres = ["r.timestamp BETWEEN ? AND ?"]
    params = [from_ms, to_ms]

    if device_id:
        wheres.append("r.device_id = ?"); params.append(device_id)
    if operator:
        wheres.append("r.operator = ?"); params.append(operator)
    if net_type:
        wheres.append("r.network_type = ?"); params.append(net_type)

    where_clause = " AND ".join(wheres)

    total = db.execute(
        f"SELECT COUNT(*) FROM cell_reports r WHERE {where_clause}", params
    ).fetchone()[0]

    rows = db.execute(
        f"SELECT r.report_id, r.device_id, d.user_name, r.operator, r.network_type, "
        f"r.signal_power, r.sinr_snr, r.frequency_band, r.cell_id, "
        f"r.timestamp, r.received_at "
        f"FROM cell_reports r "
        f"LEFT JOIN devices d ON d.device_id = r.device_id "
        f"WHERE {where_clause} "
        f"ORDER BY r.timestamp DESC LIMIT ? OFFSET ?",
        params + [limit, offset]
    ).fetchall()

    # Collect distinct filter options for dropdowns
    ops = db.execute(
        "SELECT DISTINCT operator FROM cell_reports ORDER BY operator"
    ).fetchall()
    types = db.execute(
        "SELECT DISTINCT network_type FROM cell_reports ORDER BY network_type"
    ).fetchall()

    return jsonify({
        "total":    total,
        "limit":    limit,
        "offset":   offset,
        "from_ms":  from_ms,
        "to_ms":    to_ms,
        "operators":      [r["operator"]     for r in ops],
        "network_types":  [r["network_type"] for r in types],
        "measurements": [{
            "report_id":      r["report_id"],
            "device_id":      r["device_id"],
            "user_name":      r["user_name"] or "—",
            "operator":       r["operator"],
            "network_type":   r["network_type"],
            "signal_power":   r["signal_power"],
            "sinr_snr":       r["sinr_snr"],
            "frequency_band": r["frequency_band"] or "—",
            "cell_id":        r["cell_id"] or "—",
            "timestamp":      _fmt_ts(r["timestamp"]),
            "received_at":    _fmt_ts(r["received_at"]),
        } for r in rows],
    })

# ── GET /api/quality ──────────────────────────────────────────────────────────

@app.route("/api/quality", methods=["GET"])
def get_quality():
    db   = get_db()
    rows = db.execute("""
        SELECT device_id,
               SUM(CASE WHEN signal_power>=-70 THEN 1 ELSE 0 END) excellent,
               SUM(CASE WHEN signal_power BETWEEN -85 AND -71 THEN 1 ELSE 0 END) good,
               SUM(CASE WHEN signal_power BETWEEN -100 AND -86 THEN 1 ELSE 0 END) fair,
               SUM(CASE WHEN signal_power<-100 THEN 1 ELSE 0 END) poor,
               COUNT(*) total, AVG(signal_power) avg_signal
        FROM cell_reports GROUP BY device_id ORDER BY avg_signal DESC
    """).fetchall()

    result = []
    for r in rows:
        t = r["total"] or 1
        result.append({
            "device_id":     r["device_id"],
            "avg_signal":    round(r["avg_signal"], 1) if r["avg_signal"] else None,
            "quality_label": classify_signal(round(r["avg_signal"]) if r["avg_signal"] else -120),
            "distribution": {
                "excellent": round(r["excellent"] / t * 100, 1),
                "good":      round(r["good"]      / t * 100, 1),
                "fair":      round(r["fair"]       / t * 100, 1),
                "poor":      round(r["poor"]       / t * 100, 1),
            }
        })
    return jsonify({"devices": result})

# ── GET / ─────────────────────────────────────────────────────────────────────

@app.route("/")
def dashboard():
    return render_template("dashboard.html")

# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    init_db()
    print("[Server] Starting on http://0.0.0.0:5000 ...")
    print("[Server] Dashboard: http://localhost:5000/")
    app.run(host="0.0.0.0", port=5000, debug=False, threaded=True)