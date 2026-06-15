package com.diegonmarcos.superapp.battery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Bounded SQLite ring for energy samples ([EnergyWatchdog.Sample]). Prunes to
 *  [CAP] newest rows on each insert so the table never grows unbounded. */
class EnergyStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $T (ts INTEGER PRIMARY KEY, draw_ma INTEGER, power_w REAL, " +
                "batt_pct INTEGER, batt_temp REAL, charging INTEGER, screen_on INTEGER, " +
                "brightness INTEGER, fg_pkg TEXT, cpu_load INTEGER, mobile_dbm INTEGER, " +
                "wifi_rssi INTEGER, audio INTEGER, power_save INTEGER, device_idle INTEGER, " +
                "self_cpu INTEGER, self_rx INTEGER, self_tx INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T"); onCreate(db)
    }

    fun insert(s: EnergyWatchdog.Sample) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO $T VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                s.ts, s.drawMa, s.powerW, s.battPct, s.battTempC,
                if (s.charging) 1 else 0, if (s.screenOn) 1 else 0, s.brightness,
                s.fgPkg, s.cpuLoadPct, s.mobileSignalDbm, s.wifiRssi,
                if (s.audioActive) 1 else 0, if (s.powerSave) 1 else 0,
                if (s.deviceIdle) 1 else 0, s.selfCpuJiffies, s.selfRxBytes, s.selfTxBytes,
            ))
        db.execSQL("DELETE FROM $T WHERE ts NOT IN (SELECT ts FROM $T ORDER BY ts DESC LIMIT $CAP)")
    }

    fun all(): List<EnergyWatchdog.Sample> {
        val out = ArrayList<EnergyWatchdog.Sample>()
        readableDatabase.rawQuery("SELECT * FROM $T ORDER BY ts ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(EnergyWatchdog.Sample(
                    ts = c.getLong(0), drawMa = c.getInt(1), powerW = c.getDouble(2),
                    battPct = c.getInt(3), battTempC = c.getDouble(4), charging = c.getInt(5) == 1,
                    screenOn = c.getInt(6) == 1, brightness = c.getInt(7), fgPkg = c.getString(8) ?: "",
                    cpuLoadPct = c.getInt(9), mobileSignalDbm = c.getInt(10), wifiRssi = c.getInt(11),
                    audioActive = c.getInt(12) == 1, powerSave = c.getInt(13) == 1,
                    deviceIdle = c.getInt(14) == 1, selfCpuJiffies = c.getLong(15),
                    selfRxBytes = c.getLong(16), selfTxBytes = c.getLong(17),
                ))
            }
        }
        return out
    }

    fun recent(limit: Int): List<EnergyWatchdog.Sample> = all().takeLast(limit)

    fun clear() { writableDatabase.execSQL("DELETE FROM $T") }

    companion object {
        private const val DB = "energy_watchdog.db"
        private const val T = "energy_sample"
        private const val CAP = 8000  // ~5.5 days at 1/min, bounded
    }
}
