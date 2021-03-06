/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.ui.test

import androidx.collection.SparseArrayCompat
import java.util.WeakHashMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.node.Owner
import androidx.compose.ui.unit.Duration
import androidx.compose.ui.unit.inMilliseconds
import androidx.compose.ui.unit.milliseconds
import kotlin.math.max
import kotlin.math.roundToInt

internal abstract class BaseInputDispatcher : InputDispatcher {
    companion object {
        /**
         * Indicates that [nextDownTime] is not set
         */
        private const val DownTimeNotSet = -1L

        /**
         * Stores the [InputDispatcherState] of each [Owner]. The state will be restored in an
         * [InputDispatcher] when it is created for an owner that has a state stored.
         */
        internal val states = WeakHashMap<Owner, InputDispatcherState>()
    }

    internal var nextDownTime = DownTimeNotSet

    /**
     * The time difference between enqueuing the first event of the gesture and dispatching it.
     *
     * When the first event of a gesture is enqueued, its eventTime is fixed to the current time.
     * However, there is inevitably some time between enqueuing and dispatching of that event.
     * This means that event is going to be "late" by [gestureLateness] milliseconds when it is
     * dispatched. Because the dispatcher wants to align events with the current time, it will
     * dispatch all events that are late immediately and without delay, until it has reached an
     * event whose eventTime is in the future (i.e. an event that is "early").
     *
     * The [gestureLateness] will be used to offset all events, effectively aligning the first
     * event with the dispatch time.
     */
    internal var gestureLateness: Long? = null

    internal var partialGesture: PartialGesture? = null

    /**
     * Indicates if a gesture is in progress or not. A gesture is in progress if at least one
     * finger is (still) touching the screen.
     */
    val isGestureInProgress: Boolean
        get() = partialGesture != null

    abstract override val now: Long

    /**
     * Generates the downTime of the next gesture with the given [duration]. The gesture's
     * [duration] is necessary to facilitate chaining of gestures: if another gesture is made
     * after the next one, it will start exactly [duration] after the start of the next gesture.
     * Always use this method to determine the downTime of the [down event][enqueueDown] of a
     * gesture.
     *
     * If the duration is unknown when calling this method, use a duration of zero and update
     * with [moveNextDownTime] when the duration is known, or use [moveNextDownTime]
     * incrementally if the gesture unfolds gradually.
     */
    private fun generateDownTime(duration: Duration): Long {
        val downTime = if (nextDownTime == DownTimeNotSet) {
            now
        } else {
            nextDownTime
        }
        nextDownTime = downTime + duration.inMilliseconds()
        return downTime
    }

    /**
     * Moves the start time of the next gesture ahead by the given [duration]. Does not affect
     * any event time from the current gesture. Use this when the expected duration passed to
     * [generateDownTime] has changed.
     */
    private fun moveNextDownTime(duration: Duration) {
        generateDownTime(duration)
    }

    /**
     * Increases the eventTime with the given [time]. Also pushes the downTime for the next
     * chained gesture by the same amount to facilitate chaining.
     */
    private fun PartialGesture.increaseEventTime(time: Long = InputDispatcher.eventPeriod) {
        moveNextDownTime(time.milliseconds)
        lastEventTime += time
    }

    override fun enqueueDelay(duration: Duration) {
        require(duration >= Duration.Zero) {
            "duration of a delay can only be positive, not $duration"
        }
        moveNextDownTime(duration)
    }

    override fun enqueueClick(position: Offset) {
        enqueueDown(0, position)
        enqueueMove()
        enqueueUp(0)
    }

    override fun enqueueSwipe(start: Offset, end: Offset, duration: Duration) {
        val durationFloat = duration.inMilliseconds().toFloat()
        enqueueSwipe(
            curve = { lerp(start, end, it / durationFloat) },
            duration = duration
        )
    }

    override fun enqueueSwipe(
        curve: (Long) -> Offset,
        duration: Duration,
        keyTimes: List<Long>
    ) {
        enqueueSwipes(listOf(curve), duration, keyTimes)
    }

    override fun enqueueSwipes(
        curves: List<(Long) -> Offset>,
        duration: Duration,
        keyTimes: List<Long>
    ) {
        val startTime = 0L
        val endTime = duration.inMilliseconds()

        // Validate input
        require(duration >= 1.milliseconds) {
            "duration must be at least 1 millisecond, not $duration"
        }
        val validRange = startTime..endTime
        require(keyTimes.all { it in validRange }) {
            "keyTimes contains timestamps out of range [$startTime..$endTime]: $keyTimes"
        }
        require(keyTimes.asSequence().zipWithNext { a, b -> a <= b }.all { it }) {
            "keyTimes must be sorted: $keyTimes"
        }

        // Send down events
        curves.forEachIndexed { i, curve ->
            enqueueDown(i, curve(startTime))
        }

        // Send move events between each consecutive pair in [t0, ..keyTimes, tN]
        var currTime = startTime
        var key = 0
        while (currTime < endTime) {
            // advance key
            while (key < keyTimes.size && keyTimes[key] <= currTime) {
                key++
            }
            // send events between t and next keyTime
            val tNext = if (key < keyTimes.size) keyTimes[key] else endTime
            sendPartialSwipes(curves, currTime, tNext)
            currTime = tNext
        }

        // And end with up events
        repeat(curves.size) {
            enqueueUp(it)
        }
    }

    /**
     * Generates move events between `f([t0])` and `f([tN])` during the time window `(downTime +
     * t0, downTime + tN]`, using [fs] to sample the coordinate of each event. The number of
     * events sent (#numEvents) is such that the time between each event is as close to
     * [InputDispatcher.eventPeriod] as possible, but at least 1. The first event is sent at time
     * `downTime + (tN - t0) / #numEvents`, the last event is sent at time tN.
     *
     * @param fs The functions that define the coordinates of the respective gestures over time
     * @param t0 The start time of this segment of the swipe, in milliseconds relative to downTime
     * @param tN The end time of this segment of the swipe, in milliseconds relative to downTime
     */
    private fun sendPartialSwipes(
        fs: List<(Long) -> Offset>,
        t0: Long,
        tN: Long
    ) {
        var step = 0
        // How many steps will we take between t0 and tN? At least 1, and a number that will
        // bring as as close to eventPeriod as possible
        val steps = max(1, ((tN - t0) / InputDispatcher.eventPeriod.toFloat()).roundToInt())

        var tPrev = t0
        while (step++ < steps) {
            val progress = step / steps.toFloat()
            val t = androidx.compose.ui.util.lerp(t0, tN, progress)
            fs.forEachIndexed { i, f ->
                movePointer(i, f(t))
            }
            enqueueMove(t - tPrev)
            tPrev = t
        }
    }

    override fun getCurrentPosition(pointerId: Int): Offset? {
        return partialGesture?.lastPositions?.get(pointerId)
    }

    override fun enqueueDown(pointerId: Int, position: Offset) {
        var gesture = partialGesture

        // Check if this pointer is not already down
        require(gesture == null || !gesture.lastPositions.containsKey(pointerId)) {
            "Cannot send DOWN event, a gesture is already in progress for pointer $pointerId"
        }

        gesture?.flushPointerUpdates()

        // Start a new gesture, or add the pointerId to the existing gesture
        if (gesture == null) {
            gesture = PartialGesture(generateDownTime(0.milliseconds), position, pointerId)
            partialGesture = gesture
        } else {
            gesture.lastPositions.put(pointerId, position)
        }

        // Send the DOWN event
        gesture.enqueueDown(pointerId)
    }

    override fun movePointer(pointerId: Int, position: Offset) {
        val gesture = partialGesture

        // Check if this pointer is in the gesture
        check(gesture != null) {
            "Cannot move pointers, no gesture is in progress"
        }
        require(gesture.lastPositions.containsKey(pointerId)) {
            "Cannot move pointer $pointerId, it is not active in the current gesture"
        }

        gesture.lastPositions.put(pointerId, position)
        gesture.hasPointerUpdates = true
    }

    override fun enqueueMove(delay: Long) {
        val gesture = checkNotNull(partialGesture) {
            "Cannot send MOVE event, no gesture is in progress"
        }
        require(delay >= 0) {
            "Cannot send MOVE event with a delay of $delay ms"
        }

        gesture.increaseEventTime(delay)
        gesture.enqueueMove()
        gesture.hasPointerUpdates = false
    }

    override fun enqueueUp(pointerId: Int, delay: Long) {
        val gesture = partialGesture

        // Check if this pointer is in the gesture
        check(gesture != null) {
            "Cannot send UP event, no gesture is in progress"
        }
        require(gesture.lastPositions.containsKey(pointerId)) {
            "Cannot send UP event for pointer $pointerId, it is not active in the current gesture"
        }
        require(delay >= 0) {
            "Cannot send UP event with a delay of $delay ms"
        }

        gesture.flushPointerUpdates()
        gesture.increaseEventTime(delay)

        // First send the UP event
        gesture.enqueueUp(pointerId)

        // Then remove the pointer, and end the gesture if no pointers are left
        gesture.lastPositions.remove(pointerId)
        if (gesture.lastPositions.isEmpty) {
            partialGesture = null
        }
    }

    override fun enqueueCancel(delay: Long) {
        val gesture = checkNotNull(partialGesture) {
            "Cannot send CANCEL event, no gesture is in progress"
        }
        require(delay >= 0) {
            "Cannot send CANCEL event with a delay of $delay ms"
        }

        gesture.increaseEventTime(delay)
        gesture.enqueueCancel()
        partialGesture = null
    }

    /**
     * Generates a MOVE event with all pointer locations, if any of the pointers has been moved by
     * [movePointer] since the last MOVE event.
     */
    private fun PartialGesture.flushPointerUpdates() {
        if (hasPointerUpdates) {
            enqueueMove(InputDispatcher.eventPeriod)
        }
    }

    protected abstract fun PartialGesture.enqueueDown(pointerId: Int)

    protected abstract fun PartialGesture.enqueueMove()

    protected abstract fun PartialGesture.enqueueUp(pointerId: Int)

    protected abstract fun PartialGesture.enqueueCancel()
}

/**
 * The state of an [InputDispatcher], saved when the [GestureScope] is disposed and restored when
 * the [GestureScope] is recreated.
 *
 * @param nextDownTime The downTime of the start of the next gesture, when chaining gestures.
 * This property will only be restored if an incomplete gesture was in progress when the state of
 * the [InputDispatcher] was saved.
 * @param gestureLateness The time difference in milliseconds between enqueuing the first event
 * of the gesture and dispatching it. Depending on the implementation of [InputDispatcher], this
 * may or may not be used.
 * @param partialGesture The state of an incomplete gesture. If no gesture was in progress
 * when the state of the [InputDispatcher] was saved, this will be `null`.
 */
internal data class InputDispatcherState(
    val nextDownTime: Long,
    var gestureLateness: Long?,
    val partialGesture: PartialGesture?
)

internal class PartialGesture constructor(
    val downTime: Long,
    startPosition: Offset,
    pointerId: Int
) {
    var lastEventTime: Long = downTime
    var hasPointerUpdates: Boolean = false
    val lastPositions = SparseArrayCompat<Offset>().apply { put(pointerId, startPosition) }
}
