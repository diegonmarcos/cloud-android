package com.diegonmarcos.superapp

/**
 * The **app** entry point — `MAIN` + `LAUNCHER`, its own `taskAffinity`.
 *
 * All the behaviour lives in [ShellActivity]; the only thing this class
 * carries is its manifest identity. That identity is the whole point: a
 * distinct task affinity puts this instance in its own task, which is what
 * earns it an Overview card, and Overview is where the user picks the second
 * half of a split. The home task (see [HomeActivity]) can never be a split
 * half — that is the platform, not us — so serving both intent-filters from
 * one `singleTask` activity, as this app did until now, meant the SuperApp
 * could never appear in split-screen at all.
 *
 * Launch targets that mean "open the app" (App.launchActivity,
 * KdeStatusNotifier, the floating-nav / one-hand `openInHost` shortcuts)
 * point here, not at [HomeActivity], so a shortcut fired from another app
 * opens the splittable task instead of yanking the user to the home screen.
 */
class MainActivity : ShellActivity()
