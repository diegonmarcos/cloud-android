package com.termux.cloud;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Run a command in this terminal for another app in the constellation, and
 * hand back what it printed.
 *
 * WHY THIS EXISTS
 * The watchdog app has to read the machine this terminal lives on, and Android
 * gives almost nothing for reaching into another app's sandbox. RUN_COMMAND
 * fires and forgets. A session is interactive, so finding the end of the
 * output means watching for a prompt or a pause, which truncates the moment
 * the phone is busy. The workaround was ssh to loopback, which turned one
 * question into four things that had to be true at once — sshd installed,
 * running, on the port we guessed, holding our key — and at least one of them
 * was false often enough to make the dashboard unusable.
 *
 * A bound service is none of that: a function call that blocks until the
 * command is done and returns its output. It works because we own both ends,
 * which is the whole reason this terminal was forked.
 *
 * WHY IT IS SAFE TO EXPORT
 * The manifest guards it with a signature-level permission, so only an APK
 * signed with the constellation key can bind — not a user prompt that can be
 * tapped through, and not something another app can request. Anything else
 * gets a SecurityException from the system before this class is reached.
 *
 * WHY IT REUSES TermuxShellEnvironmentClient RATHER THAN ProcessBuilder'S ENV
 * $PREFIX, $HOME, LD_LIBRARY_PATH and $PATH are what make a Termux binary
 * runnable at all, and they are already computed here for the terminal's own
 * sessions. Building a second environment would mean a command behaving one
 * way when typed and another way when called, which is the kind of difference
 * nobody finds quickly.
 *
 * WHY IT OWNS THE PROCESS RATHER THAN CALLING TermuxTask
 * TermuxTask runs synchronously but hands back no way to kill a command that
 * never ends. A binder call that never returns wedges the caller's thread, and
 * on a phone nobody is sitting there to press ^C — so the process is started
 * here and destroyed on timeout.
 */
public class CloudExecService extends Service {

    /** Nothing runs longer than this, whatever the caller asked for. */
    private static final int MAX_TIMEOUT_MS = 120_000;
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    /**
     * Output ceiling per stream. A command like `find /` would otherwise be
     * answered with a Bundle far past the ~1MB binder limit, and the failure
     * for that is TransactionTooLargeException thrown at the CALLER — a crash
     * in someone else's app, blamed on them, for a command they ran here.
     * Truncating is the honest failure; the marker says it happened.
     */
    private static final int MAX_OUTPUT = 512 * 1024;

    private final ICloudExec.Stub mBinder = new ICloudExec.Stub() {

        @Override
        public Bundle exec(String executable, String[] args, String stdin, String workdir, int timeoutMs) {
            Bundle out = new Bundle();
            if (executable == null || executable.isEmpty()) {
                out.putString("error", "no executable");
                out.putInt("exit", -1);
                return out;
            }

            int timeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : Math.min(timeoutMs, MAX_TIMEOUT_MS);
            String cwd = (workdir == null || workdir.isEmpty())
                ? TermuxConstants.TERMUX_HOME_DIR_PATH : workdir;

            TermuxShellEnvironmentClient env = new TermuxShellEnvironmentClient();
            String[] environment = env.buildEnvironment(CloudExecService.this, false, cwd);
            String[] argv = env.setupProcessArgs(executable, args == null ? new String[0] : args);

            Process process;
            try {
                process = Runtime.getRuntime().exec(argv, environment, new File(cwd));
            } catch (IOException e) {
                out.putString("error", "could not start " + executable + ": " + e.getMessage());
                out.putInt("exit", -1);
                return out;
            }

            // stdin first and closed straight after: a command reading from a
            // pipe that is never closed waits for input that is never coming,
            // and then the timeout below is the only thing that ends it.
            try (OutputStream os = process.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) os.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // A command that exits before reading its input is normal —
                // `head -1` does it — and is not a failure of this call.
            }

            // Both streams drained concurrently. A single-threaded reader
            // deadlocks the moment the command fills the other pipe's buffer,
            // which for stderr is 64KB and one verbose build away.
            Drain sout = new Drain(process.getInputStream());
            Drain serr = new Drain(process.getErrorStream());
            sout.start();
            serr.start();

            // A killer thread rather than Process.waitFor(timeout, unit):
            // that overload is API 26 and this app supports 24, where it is
            // not a compile error but a NoSuchMethodError on the first slow
            // command — the worst place to find an API gate.
            final Process p = process;
            final boolean[] killed = {false};
            Thread killer = new Thread(() -> {
                try {
                    Thread.sleep(timeout);
                    killed[0] = true;
                    p.destroy();
                } catch (InterruptedException ignored) {
                    // Normal exit: the command finished and we cancelled this.
                }
            });
            killer.setDaemon(true);
            killer.start();

            boolean finished;
            try {
                process.waitFor();
                finished = !killed[0];
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            } finally {
                killer.interrupt();
            }

            if (!finished) {
                process.destroy();
                out.putString("error", executable + " did not finish within " + timeout + "ms");
            }

            // Joined after the process is done or killed, so the readers see
            // EOF and stop; without this the output is whatever happened to
            // have arrived when we looked.
            sout.finish();
            serr.finish();

            out.putString("stdout", sout.text());
            out.putString("stderr", serr.text());
            out.putInt("exit", finished ? process.exitValue() : -1);
            if (sout.truncated || serr.truncated) out.putBoolean("truncated", true);
            return out;
        }

        @Override
        public String prefix() {
            return TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        }

        @Override
        public String toolsDir() {
            // nativeLibraryDir is the one directory an app can both ship into
            // and execute from — since API 29, W^X means nothing this app
            // unpacks itself may carry +x. Nothing dlopens these; the lib*.so
            // naming is the price of admission to that directory.
            return getApplicationInfo().nativeLibraryDir;
        }
    };

    /** One stream, read to EOF on its own thread, capped. */
    private static final class Drain extends Thread {
        private final InputStream in;
        private final StringBuilder sb = new StringBuilder();
        volatile boolean truncated;

        Drain(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            char[] buf = new char[8192];
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                int n;
                while ((n = r.read(buf)) != -1) {
                    if (sb.length() >= MAX_OUTPUT) {
                        truncated = true;
                        // Keep draining: stopping here leaves the pipe full
                        // and the command blocked on a write forever, which
                        // turns a truncated answer into a hung one.
                        continue;
                    }
                    sb.append(buf, 0, n);
                }
            } catch (IOException ignored) {
                // A killed process closes its pipes mid-read. Whatever arrived
                // before that is still worth returning.
            }
        }

        void finish() {
            try {
                join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            return sb.length() > MAX_OUTPUT ? sb.substring(0, MAX_OUTPUT) : sb.toString();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return mBinder;
    }
}
