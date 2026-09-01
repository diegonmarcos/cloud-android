package com.diegonmarcos.superapp.profile

import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.ConfigSyncClient
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.FS
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Import GH SSH" — read the config artifact out of the vault repo using an
 * SSH private key instead of a token.
 *
 * WHY THIS ONE IS DIFFERENT. The other three import routes are a single
 * authenticated HTTP GET and share [ConfigSyncClient.request]. GitHub over SSH
 * speaks only the git wire protocol — there is no file-read endpoint — so this
 * route is the one that actually clones, and the only reason JGit is a
 * dependency of this app at all.
 *
 * WHAT IT COSTS. A clone fetches the WHOLE repository, not one file. cloud-vault
 * is a secrets vault, so this briefly writes every object in it into the app's
 * cache directory. Three things keep that as narrow as possible:
 *
 *   • `setBare(true)` + `setNoCheckout(true)` — no working tree is ever
 *     materialised, so no secret is written out as a readable plaintext file;
 *     only packed objects land on disk.
 *   • `setDepth(1)` + a single branch — one commit, not the history.
 *   • the clone directory is deleted in a `finally`, so it does not outlive
 *     the import even when the import fails.
 *
 * The token route pulls exactly one file over HTTPS and none of this applies,
 * which is a real reason to prefer it when a token is available.
 *
 * HOST VERIFICATION IS NOT OPTIONAL. The artifact this fetches is applied to
 * the device — it carries WireGuard keys and mail credentials — so a
 * man-in-the-middle serving a forged repo would be handing us a config to
 * install. StrictHostKeyChecking stays ON and GitHub's current host keys are
 * fetched over TLS from api.github.com/meta rather than pinned in the build,
 * so a GitHub key rotation does not turn into a mysterious failure.
 */
object GitSshVault {

    private const val TAG = "ConfigSync"

    /**
     * Clone, read [BuildConfig.UI_CONFIG_GIT_PATH], return it as the same
     * [ConfigSyncClient.Outcome] every other import route produces — so the
     * caller's apply-and-report path is identical for all four tiles.
     *
     * Blocking; call from a background dispatcher.
     */
    fun fetchArtifact(cacheDir: File, privateKeyPem: String, passphrase: String): ConfigSyncClient.Outcome {
        val repo = BuildConfig.UI_CONFIG_GIT_REPO
        val path = BuildConfig.UI_CONFIG_GIT_PATH
        val ref = BuildConfig.UI_CONFIG_GIT_REF
        if (repo.isBlank() || path.isBlank()) {
            return failed(
                ConfigSyncClient.Kind.MALFORMED,
                "No vault repo configured (build.json::ui.config_source.git.repo / .path are empty)",
            )
        }
        if (privateKeyPem.isBlank()) {
            return failed(ConfigSyncClient.Kind.UNAUTHORIZED, "No SSH private key supplied")
        }

        val knownHosts = githubKnownHosts()
            ?: return failed(
                ConfigSyncClient.Kind.NETWORK,
                "Could not fetch GitHub's SSH host keys from api.github.com/meta, so the server " +
                    "cannot be verified. Refusing to clone rather than trusting an unverified host.",
            )

        val dir = File(cacheDir, "vault-clone-${System.nanoTime()}")
        val uri = "git@github.com:$repo.git"
        Log.i(TAG, "ssh: clone $uri (bare, depth 1, branch $ref)")

        return try {
            val callback = TransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = sessionFactory(privateKeyPem, passphrase, knownHosts)
                }
            }
            Git.cloneRepository()
                .setURI(uri)
                .setDirectory(dir)
                .setBare(true)
                .setNoCheckout(true)
                .setCloneAllBranches(false)
                .setBranchesToClone(listOf("refs/heads/$ref"))
                .setBranch("refs/heads/$ref")
                .setDepth(1)
                .setNoTags()
                .setTransportConfigCallback(callback)
                .call()
                .use { git -> readBlob(git.repository, ref, path) }
        } catch (t: Throwable) {
            Log.w(TAG, "ssh clone failed: ${t.javaClass.simpleName}")
            failed(
                ConfigSyncClient.Kind.NETWORK,
                "Clone of $uri failed — ${t.javaClass.simpleName}: ${scrub(t.message, privateKeyPem)}",
            )
        } finally {
            // The vault's objects must not outlive the import, success or not.
            dir.deleteRecursively()
        }
    }

    /** Pull one path out of the cloned commit without ever writing it to disk. */
    private fun readBlob(repository: Repository, ref: String, path: String): ConfigSyncClient.Outcome {
        val head = repository.resolve("refs/heads/$ref")
            ?: repository.resolve("HEAD")
            ?: return failed(ConfigSyncClient.Kind.NOT_FOUND, "Cloned, but branch '$ref' has no commit.")

        RevWalk(repository).use { walk ->
            val tree = walk.parseCommit(head).tree
            val walker = TreeWalk.forPath(repository, path, tree)
                ?: return failed(
                    ConfigSyncClient.Kind.NOT_FOUND,
                    "Cloned ${BuildConfig.UI_CONFIG_GIT_REPO}@$ref, but it has no file at '$path'. " +
                        "Check build.json::ui.config_source.git.path.",
                )
            val bytes = walker.use { repository.open(it.getObjectId(0)).bytes }
            val body = String(bytes, Charsets.UTF_8)
            return if (body.isBlank()) {
                failed(ConfigSyncClient.Kind.MALFORMED, "'$path' is empty.")
            } else {
                try {
                    ConfigSyncClient.Outcome.Ok(JSONObject(body), body.length)
                } catch (t: Throwable) {
                    failed(ConfigSyncClient.Kind.MALFORMED, "'$path' is not a JSON object: ${t.message}")
                }
            }
        }
    }

    /**
     * A JSch that knows exactly one identity and exactly the host keys GitHub
     * publishes — nothing from the filesystem.
     *
     * [createDefaultJSch] is overridden rather than extended because the stock
     * implementation probes `~/.ssh` for keys and known_hosts, which on Android
     * is both meaningless and a source of obscure failures.
     */
    private fun sessionFactory(
        privateKeyPem: String,
        passphrase: String,
        knownHosts: String,
    ): JschConfigSessionFactory = object : JschConfigSessionFactory() {

        override fun createDefaultJSch(fs: FS): JSch {
            val jsch = JSch()
            jsch.addIdentity(
                "cloud-vault",
                privateKeyPem.toByteArray(Charsets.UTF_8),
                null,
                passphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8),
            )
            jsch.setKnownHosts(ByteArrayInputStream(knownHosts.toByteArray(Charsets.UTF_8)))
            return jsch
        }

        override fun configure(hc: OpenSshConfig.Host, session: Session) {
            session.setConfig("StrictHostKeyChecking", "yes")
            // The key is the whole point; never fall back to asking for a
            // password on a headless connection (it would just hang).
            session.setConfig("PreferredAuthentications", "publickey")
        }
    }

    /**
     * GitHub's current SSH host keys, as a known_hosts file.
     *
     * Fetched over TLS instead of pinned in build.json: a pinned key is correct
     * right up until GitHub rotates it (as they did in March 2023 after the RSA
     * key leak), and then every import fails with a host-key error that looks
     * exactly like an attack. TLS to api.github.com is the trust anchor.
     */
    private fun githubKnownHosts(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://api.github.com/meta").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS
                readTimeout = BuildConfig.UI_CONFIG_SOURCE_READ_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Cloud-SuperApp-ConfigSync/1")
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val keys: JSONArray = JSONObject(body).optJSONArray("ssh_keys") ?: return null
            val lines = (0 until keys.length()).mapNotNull { i ->
                keys.optString(i).takeIf { it.isNotBlank() }?.let { "github.com $it" }
            }
            lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
        } catch (t: Throwable) {
            Log.w(TAG, "could not fetch github host keys: ${t.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** A key in an exception message would be a key in the UI and in logcat. */
    private fun scrub(message: String?, key: String): String {
        val text = message ?: "no detail"
        return if (key.isBlank()) text else text.replace(key, "«key»")
    }

    private fun failed(kind: ConfigSyncClient.Kind, message: String): ConfigSyncClient.Outcome.Failed {
        Log.w(TAG, "$kind: ${message.lineSequence().first()}")
        return ConfigSyncClient.Outcome.Failed(kind, message)
    }
}
