package com.aadam.jarviscompanion

/**
 * Centralized, in-memory call-state tracker. Two independent sources
 * write into this:
 *  - JarvisConnectionService/JarvisConnection update fakeCallState as our
 *    own simulated calls ring, get answered, and end.
 *  - RealCallStateWatcher (TelephonyManager/PhoneStateListener) updates
 *    realCallState for genuine cellular calls -- Android lets us OBSERVE
 *    these but not answer/reject them from a third-party app without
 *    either becoming the default dialer or resorting to fragile root
 *    hacks, so this side is read-only/notify-only by design.
 *
 * CallStateService (the HTTP endpoint) reads both and reports a combined
 * picture -- "on a call" should be true if EITHER side is active, since
 * from the PC's point of view what matters is whether the phone is
 * currently in a call at all, not which subsystem it came from.
 */
object CallStateManager {

    enum class State { IDLE, RINGING, DIALING, ACTIVE }

    // Live reference to the current fake call's Connection, so the HTTP
    // accept/reject endpoints (CallTriggerServer) can drive it from
    // outside JarvisConnection's own class. Registered in JarvisConnection's
    // init and cleared on any terminal state (answered isn't terminal,
    // but reject/disconnect/abort are).
    @Volatile
    var activeFakeConnection: JarvisConnection? = null

    @Volatile
    var fakeCallState: State = State.IDLE
        private set

    @Volatile
    var fakeCallCallerName: String = ""
        private set

    @Volatile
    var realCallState: State = State.IDLE
        private set

    @Volatile
    var realCallNumber: String = ""
        private set

    fun setFakeCallState(state: State, callerName: String = "") {
        fakeCallState = state
        if (state != State.IDLE) fakeCallCallerName = callerName
        if (state == State.IDLE) fakeCallCallerName = ""
    }

    fun setRealCallState(state: State, number: String = "") {
        realCallState = state
        if (state != State.IDLE) realCallNumber = number
        if (state == State.IDLE) realCallNumber = ""
    }

    /** Combined summary for the /call_state endpoint. */
    fun snapshot(): Map<String, Any> {
        val overall = when {
            fakeCallState == State.ACTIVE || realCallState == State.ACTIVE -> "active"
            fakeCallState == State.RINGING || realCallState == State.RINGING -> "ringing"
            fakeCallState == State.DIALING || realCallState == State.DIALING -> "dialing"
            else -> "idle"
        }
        return mapOf(
            "overall_state" to overall,
            "fake_call" to mapOf(
                "state" to fakeCallState.name.lowercase(),
                "caller_name" to fakeCallCallerName
            ),
            "real_call" to mapOf(
                "state" to realCallState.name.lowercase(),
                "number" to realCallNumber
            )
        )
    }
}
