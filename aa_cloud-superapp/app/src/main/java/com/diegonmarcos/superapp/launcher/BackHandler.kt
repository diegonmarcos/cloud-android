package com.diegonmarcos.superapp.launcher

/** Opt-in contract for Fragments that handle Back themselves before letting
 *  the activity pop the back-stack (e.g. multi-step Compose flows). */
interface BackHandler {
    fun tryHandleBack(): Boolean
}
