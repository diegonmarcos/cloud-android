package com.diegonmarcos.superapp.core;

/**
 * ONE contract for every data-engine library that ships as its own APK.
 *
 * The engines this serves (cal, contacts, news, datamanager, feed) all have
 * the same shape already: a name, some string arguments, a JSON string back.
 * That is what a WebView JS bridge is, and three of the five are driven by
 * exactly that. So they get one generic interface rather than five bespoke
 * ones that would each need its own .aidl, its own Stub and its own client -
 * five copies of the same five methods, which is the duplication this whole
 * sweep exists to remove.
 *
 * Typed interfaces are still the right answer where the surface is NOT
 * string-shaped: libs:net keeps INetBackend because Tunnel.State, Config and
 * Statistics are real types with real semantics.
 */
interface IDataBackend {
    /**
     * Invoke [method] with [args]. Returns the engine's JSON reply, or a JSON
     * object with an "error" key. Never throws across the binder: a remote
     * exception would surface in the caller as a bare DeadObjectException with
     * nothing to show a user.
     */
    String call(String method, in String[] args);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
}
