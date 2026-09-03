package com.diegonmarcos.superapp

/**
 * The **launcher** entry point — `MAIN` + `HOME` + `DEFAULT`, `singleTask`
 * with an empty `taskAffinity`, matching AOSP Launcher3 task semantics.
 *
 * Behaviour is identical to [MainActivity]: both are empty subclasses of
 * [ShellActivity], same process, same SharedPreferences, so Configs →
 * Launcher and Configs → One-Hand keep driving the home screen from inside
 * the app with no cross-process plumbing.
 *
 * The two can be alive at once — the launcher behind, an app instance in a
 * split half. Anything static that tracks "the host is on screen" must
 * therefore count instances rather than hold a boolean; see
 * `FloatingNavService.hostVisible`.
 */
class HomeActivity : ShellActivity()
