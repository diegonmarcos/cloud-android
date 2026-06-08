package com.diegonmarcos.superapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite-backed store for the GPS tracker. Three tables:
 *
 *   • points        — every raw GPS sample written by
 *                     [LocationTrackerService]. Lat/lon/accuracy/speed
 *                     + epoch millis. High-volume; index on ts.
 *   • stops         — derived: dwell events (radius + min duration).
 *                     [LocationTrackerService]'s stop-detector emits
 *                     a row on transition from MOVING → STOPPED. Push 3
 *                     fills place_name / neighborhood / city / country
 *                     via the active reverse-geocode + POI providers.
 *   • places_cache  — keyed cache of "what's at this rounded lat/lon"
 *                     so Push 3 doesn't double-call the geocoder for
 *                     the same Stop region. JSON blob; opaque to SQL.
 *
 * Raw SQLite (no Room) so we keep the build free of kapt/ksp plugin
 * commitments. Schema migrations done by bumping [DB_VERSION] and
 * adding an `onUpgrade` branch — kept simple by design.
 */
class MapsDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE points (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ts        INTEGER NOT NULL,
                lat       REAL    NOT NULL,
                lon       REAL    NOT NULL,
                accuracy  REAL,
                speed     REAL,
                bearing   REAL,
                altitude  REAL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_points_ts ON points(ts);")
        db.execSQL(
            """
            CREATE TABLE stops (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at   INTEGER NOT NULL,
                ended_at     INTEGER,
                lat          REAL    NOT NULL,
                lon          REAL    NOT NULL,
                accuracy     REAL,
                place_name   TEXT,
                neighborhood TEXT,
                city         TEXT,
                country      TEXT,
                enriched_at  INTEGER
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_stops_started_at ON stops(started_at);")
        db.execSQL(
            """
            CREATE TABLE places_cache (
                latlon_key TEXT PRIMARY KEY,
                json       TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No migrations yet — Push 2 is v1.
    }

    fun insertPoint(p: PointRow): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("ts", p.ts)
            put("lat", p.lat)
            put("lon", p.lon)
            put("accuracy", p.accuracy)
            put("speed", p.speed)
            put("bearing", p.bearing)
            put("altitude", p.altitude)
        }
        return db.insert("points", null, cv)
    }

    fun insertStop(s: StopRow): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("started_at", s.startedAt)
            put("ended_at", s.endedAt)
            put("lat", s.lat)
            put("lon", s.lon)
            put("accuracy", s.accuracy)
        }
        return db.insert("stops", null, cv)
    }

    /** Most recent N points, oldest first. Used by the map fragment
     *  to draw the user's recent track polyline. */
    fun recentPoints(limit: Int): List<PointRow> {
        val db = readableDatabase
        val out = mutableListOf<PointRow>()
        db.rawQuery(
            "SELECT ts,lat,lon,accuracy,speed,bearing,altitude FROM points ORDER BY ts DESC LIMIT ?;",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(PointRow(
                    ts        = c.getLong(0),
                    lat       = c.getDouble(1),
                    lon       = c.getDouble(2),
                    accuracy  = if (c.isNull(3)) null else c.getDouble(3).toFloat(),
                    speed     = if (c.isNull(4)) null else c.getDouble(4).toFloat(),
                    bearing   = if (c.isNull(5)) null else c.getDouble(5).toFloat(),
                    altitude  = if (c.isNull(6)) null else c.getDouble(6),
                ))
            }
        }
        return out.reversed()
    }

    /** Total stored point count — surfaces in the tracker control
     *  fragment as a "tracking is working" health check. */
    fun pointCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM points;", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun stopCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM stops;", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Wipe every table — backs Configs → Maps → Reset / Clear data. */
    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM points;")
        db.execSQL("DELETE FROM stops;")
        db.execSQL("DELETE FROM places_cache;")
    }

    data class PointRow(
        val ts: Long,
        val lat: Double,
        val lon: Double,
        val accuracy: Float? = null,
        val speed: Float? = null,
        val bearing: Float? = null,
        val altitude: Double? = null,
    )

    data class StopRow(
        val startedAt: Long,
        val endedAt: Long? = null,
        val lat: Double,
        val lon: Double,
        val accuracy: Float? = null,
    )

    companion object {
        private const val DB_NAME = "maps.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: MapsDb? = null
        fun get(ctx: Context): MapsDb = instance ?: synchronized(this) {
            instance ?: MapsDb(ctx.applicationContext).also { instance = it }
        }
    }
}
