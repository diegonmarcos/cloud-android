// Cloud-IDE IPC — action + push surface.
// Mirrors contract/ide-ipc-v1.json::aidl exactly. The hub IMPLEMENTS this
// (dispatching to the per-fork exporter services); Cloud-SuperApp BINDS it.
// Gated by the signature permission on the <service> declaration — a caller
// signed with a different key gets SecurityException at bind time.
package com.diegonmarcos.ide;

import com.diegonmarcos.ide.IIdeCallback;

interface IIdeService {
    /** Open a file in the editor fork. Returns false if the editor isn't
        installed or the URI can't be resolved. File CONTENT never crosses IPC —
        this deep-links the owning app to render it. */
    boolean openFile(String fileUri);

    /** Open a workspace (by workspace_id from the workspaces table) in the
        editor fork. Returns false on the same conditions as openFile(). */
    boolean openWorkspace(String workspaceId);

    /** Reveal a file in the file-manager fork (Amaze FM) at its location. */
    void revealInFiles(String fileUri);

    /** Trigger a storage analysis scan in the utils fork for the given volume.
        Completion is reported via IIdeCallback.onStorageScanComplete. */
    void analyzeStorage(String volume);

    /** Open a remote endpoint (id from data/ide-endpoints.json — an sftp preset
        or the gitea base) in the editor fork's remote browser. Returns false if
        the editor isn't installed. SFTP presets are WG-only. */
    boolean openRemote(String endpointId);

    /** Register for onRecentChanged / onStorageScanComplete /
        onTransferStateChanged push callbacks. */
    void registerCallback(IIdeCallback cb);

    void unregisterCallback(IIdeCallback cb);
}
