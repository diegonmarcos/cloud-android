// Cloud-IDE IPC — push callback interface.
// Mirrors contract/ide-ipc-v1.json::aidl.callbacks. SuperApp implements this
// and registers it via IIdeService.registerCallback; the hub invokes it when a
// fork reports new recent files, a finished storage scan, or a transfer change.
package com.diegonmarcos.ide;

oneway interface IIdeCallback {
    void onRecentChanged(String domain);
    void onStorageScanComplete(String volume);
    void onTransferStateChanged(String transferId);
}
