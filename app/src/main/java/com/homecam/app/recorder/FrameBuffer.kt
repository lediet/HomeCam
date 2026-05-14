package com.homecam.app.recorder

class FrameBuffer(private val capacity: Int) {

    private data class Frame(val timestamp: Long, val jpeg: ByteArray)

    private val frames = ArrayDeque<Frame>(capacity + 1)

    @Synchronized
    fun addFrame(timestamp: Long, jpeg: ByteArray) {
        frames.addLast(Frame(timestamp, jpeg))
        if (frames.size > capacity) {
            frames.removeFirst()
        }
    }

    @Synchronized
    fun getFramesInRange(startTimeMs: Long, endTimeMs: Long): List<Pair<Long, ByteArray>> {
        return frames
            .filter { it.timestamp in startTimeMs..endTimeMs }
            .map { it.timestamp to it.jpeg }
    }

    @Synchronized
    fun getLatestFrame(): Pair<Long, ByteArray>? {
        return frames.lastOrNull()?.let { it.timestamp to it.jpeg }
    }

    @Synchronized
    fun clear() {
        frames.clear()
    }

    @Synchronized
    fun size(): Int = frames.size
}
