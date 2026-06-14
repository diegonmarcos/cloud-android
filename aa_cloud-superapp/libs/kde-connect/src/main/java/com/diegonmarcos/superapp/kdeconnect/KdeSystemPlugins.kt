package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * kdeconnect.runcommand — expose a data-driven list of commands the desktop can
 * trigger on the phone (build.json commands[]): launch an app or open a URL. No
 * permission required. The commandList is sent as a JSON-encoded STRING, per
 * KDE's contract.
 */
object RunCommandPlugin : KdePlugin {
    override val incoming = setOf("kdeconnect.runcommand.request")
    override val outgoing = setOf("kdeconnect.runcommand")

    override fun onLinkReady(ctx: Context, link: KdeLink) = sendList(ctx, link)

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        if (packet.getBoolean("requestCommandList")) { sendList(ctx, link); return true }
        val key = packet.getString("key")
        if (key.isNotEmpty()) execute(ctx, key)
        return true
    }

    private fun sendList(ctx: Context, link: KdeLink) {
        val map = JSONObject()
        for (cmd in KdeConnectConfig.get().commands) {
            map.put(cmd.key, JSONObject().put("name", cmd.name).put("command", cmd.name))
        }
        link.send(NetworkPacket.of("kdeconnect.runcommand") { put("commandList", map.toString()) })
    }

    private fun execute(ctx: Context, key: String) {
        val cmd = KdeConnectConfig.get().commands.firstOrNull { it.key == key } ?: return
        runCatching {
            val intent = when (cmd.type) {
                "url" -> Intent(Intent.ACTION_VIEW, Uri.parse(cmd.value))
                else  -> ctx.packageManager.getLaunchIntentForPackage(cmd.value)
            } ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.onFailure { Log.i("KdeConnect/RunCmd", "exec $key failed: ${it.message}") }
    }
}
