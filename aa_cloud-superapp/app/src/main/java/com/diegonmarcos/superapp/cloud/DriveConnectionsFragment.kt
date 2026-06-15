package com.diegonmarcos.superapp.cloud

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Drive · Connections — declarative listing of every storage backend
 * reachable from the stack: S3 (MinIO), Google Drive / Workspace,
 * rclone remotes, Borg/Bup backup servers, filebrowser HTTP, hedgedoc /
 * vaultwarden attachment stores, IMAP attachment blobs, etc.
 *
 * Data: build.json::ui.drive_connections (baked into BuildConfig via
 * app/build.gradle, parsed by [Sections.driveConnections]).
 *
 * Status dot:
 *   ok      green
 *   warn    amber
 *   down    red
 *   unknown grey
 */
class DriveConnectionsFragment : Fragment(R.layout.fragment_drive_connections) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root   = view.findViewById<LinearLayout>(R.id.drive_root)
        val status = view.findViewById<TextView>(R.id.drive_status)
        val inflater = LayoutInflater.from(requireContext())

        val conns = Sections.driveConnections()
        val pub  = conns.count { it.scope == "public" }
        val priv = conns.count { it.scope == "private" }
        status.text = getString(R.string.drive_connections_status, conns.size, pub, priv)

        for (c in conns) {
            val row = inflater.inflate(R.layout.item_drive_connection, root, false)
            row.findViewById<View>(R.id.dc_status_dot).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(when (c.status) {
                    "ok"   -> 0xFF2E7D32.toInt()
                    "warn" -> 0xFFEF6C00.toInt()
                    "down" -> 0xFFC62828.toInt()
                    else   -> 0xFF9E9E9E.toInt()
                })
            }
            row.findViewById<TextView>(R.id.dc_name).text     = c.name
            row.findViewById<TextView>(R.id.dc_kind).text     = "${c.kind} · ${c.scope}"
            row.findViewById<TextView>(R.id.dc_endpoint).text = c.endpoint
            row.findViewById<TextView>(R.id.dc_auth).text     = "auth: ${c.auth}"
            row.findViewById<TextView>(R.id.dc_vm).text       = c.vm
            row.findViewById<TextView>(R.id.dc_notes).text    = c.notes
            root.addView(row)
        }
    }

    companion object { fun newInstance() = DriveConnectionsFragment() }
}
