package com.diegonmarcos.superapp.ops.dagu

/**
 * DTOs mirroring Dagu's `GET /api/v1/dags` response shape — the
 * fields the Dagu page actually renders. Dagu's full schema has
 * ~30 fields per DAG; carrying everything would just bloat the
 * parser. Add fields when a new UI surface needs them.
 *
 * Status enum (Dagu's internal int → human label):
 *   0 = unknown / no runs yet
 *   1 = running
 *   2 = error / failure
 *   3 = cancel
 *   4 = success
 *   5 = skipped
 *   6 = partial-success
 */
data class DaguRun(
    val status: Int,
    val finishedAtMs: Long,   // unix epoch ms; 0 when no run yet
    val startedAtMs: Long,
)

data class DaguDag(
    /** DAG identifier (filename minus the .yaml extension on disk). */
    val name: String,
    /** Optional human-friendly display title; falls back to name. */
    val displayLabel: String,
    /** Free-form description from the DAG YAML — surfaced as a row
     *  subtitle when present. Empty when missing. */
    val description: String,
    /** Schedule string ("0 3 * * *" / cron, or empty if manual-only). */
    val schedule: String,
    /** Last run summary; null when the DAG has never executed. */
    val lastRun: DaguRun?,
)

/** Top-level response of GET /api/v1/dags. Dagu actually returns
 *  `{"DAGs": [...], "Errors": [...]}` on its v1 API; we keep just
 *  the DAGs list for the launcher's read-only surface. */
data class DaguDagList(val dags: List<DaguDag>)
