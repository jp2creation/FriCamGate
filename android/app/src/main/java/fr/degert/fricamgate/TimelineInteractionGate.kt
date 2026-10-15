package fr.degert.fricamgate

internal class TimelineInteractionGate {
    var followsPlayback = true
        private set

    fun beginScroll() { followsPlayback = false }
    fun commitScroll() { followsPlayback = true }
}
