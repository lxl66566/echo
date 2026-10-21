package com.lxl66566.echo

/** Process-wide view of the recording service, shared between service, activity and tile. */
object ServiceState {
    @Volatile var running: Boolean = false
        internal set
    @Volatile var handle: Long = 0
        internal set
    @Volatile var startedAtMs: Long = 0
        internal set
    @Volatile var lastStatsJson: String = "{}"
        internal set
    @Volatile var lastSaveResult: String? = null
        internal set
}
