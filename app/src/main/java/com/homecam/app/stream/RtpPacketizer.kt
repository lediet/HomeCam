package com.homecam.app.stream

import java.net.DatagramPacket
import java.net.InetAddress

/**
 * RFC 3984 H.264 RTP packetizer.
 *
 * Handles:
 * - Single NAL Unit packets (NAL <= 1400 bytes)
 * - FU-A fragmentation (NAL > 1400 bytes)
 * - RTP header construction (V=2, P=0, X=0, CC=0)
 */
class RtpPacketizer {

    companion object {
        private const val TAG = "RtpPacketizer"
        private const val RTP_VERSION = 0x80            // V=2, P=0, X=0, CC=0
        private const val PAYLOAD_TYPE_H264 = 96         // Dynamic payload type per RFC
        private const val MAX_RTP_PAYLOAD = 1400          // Conservative MTU
        private const val RTP_HEADER_SIZE = 12

        // NAL unit type masks
        private const val NAL_FU_A = 28                   // FU-A fragmentation type
        private const val NAL_STAP_A = 24                 // Not used, single NAL units only

        // FU-A header bits
        private const val FU_HEADER_S = 0x80              // Start fragment
        private const val FU_HEADER_E = 0x40              // End fragment
    }

    private var ssrc: Long = (1000000L..9999999L).random()

    /**
     * Packetize a single H.264 NAL unit into one or more RTP packets.
     *
     * @param nalu The NAL unit bytes (WITH Annex B start code stripped, includes NAL header)
     * @param timestamp RTP timestamp (90000 Hz clock)
     * @param seqNum Starting RTP sequence number for this frame
     * @param marker Whether this is the last NAL unit of the frame (M bit)
     * @param targetAddress Destination IP address
     * @param targetPort Destination UDP port
     * @return List of DatagramPackets and the next sequence number
     */
    data class PacketResult(
        val packets: List<DatagramPacket>,
        val nextSeqNum: Int
    )

    /**
     * Reset SSRC (call when server restarts).
     */
    fun resetSsrc() {
        ssrc = (1000000L..9999999L).random()
    }

    fun packetizeNalu(
        nalu: ByteArray,
        timestamp: Long,
        startSeqNum: Int,
        marker: Boolean,
        targetAddress: InetAddress,
        targetPort: Int
    ): PacketResult {
        val packets = mutableListOf<DatagramPacket>()
        var seqNum = startSeqNum

        if (nalu.size <= MAX_RTP_PAYLOAD) {
            // Single NAL Unit packet
            val rtpHeader = createRtpHeader(seqNum, timestamp, ssrc, marker && true)
            val packetData = rtpHeader + nalu
            val packet = DatagramPacket(packetData, packetData.size, targetAddress, targetPort)
            packets.add(packet)
            seqNum++
        } else {
            // FU-A fragmentation
            val nalHeader = nalu[0].toInt() and 0xFF
            val nalType = nalHeader and 0x1F
            val nri = nalHeader and 0x60
            val fuIndicator = (nalHeader and 0xE0) or NAL_FU_A  // F + NRI + type 28

            var offset = 0
            var fragmentCount = 0
            val totalFragments = (nalu.size - 1 + MAX_RTP_PAYLOAD - 1) / MAX_RTP_PAYLOAD

            while (offset < nalu.size - 1) {
                val fragmentSize = minOf(MAX_RTP_PAYLOAD, nalu.size - 1 - offset)
                fragmentCount++
                val isFirst = fragmentCount == 1
                val isLast = (offset + fragmentSize) >= (nalu.size - 1)

                // FU header
                var fuHeader = nalType
                if (isFirst) fuHeader = fuHeader or FU_HEADER_S
                if (isLast) fuHeader = fuHeader or FU_HEADER_E

                // M bit = 1 only on last fragment of last NAL unit of the frame
                val isMarker = isLast && marker

                val rtpHeader = createRtpHeader(seqNum, timestamp, ssrc, isMarker)
                // FU-A packet: [RTP header][FU indicator][FU header][fragment data]
                val fragmentData = nalu.copyOfRange(1 + offset, 1 + offset + fragmentSize)
                val packetData = rtpHeader + byteArrayOf(fuIndicator.toByte()) + byteArrayOf(fuHeader.toByte()) + fragmentData

                val packet = DatagramPacket(packetData, packetData.size, targetAddress, targetPort)
                packets.add(packet)
                seqNum++
                offset += fragmentSize
            }
        }

        return PacketResult(packets, seqNum)
    }

    private fun createRtpHeader(seqNum: Int, timestamp: Long, ssrc: Long, marker: Boolean): ByteArray {
        val header = ByteArray(RTP_HEADER_SIZE)

        // Byte 0: V(2) + P(1) + X(1) + CC(4)
        header[0] = RTP_VERSION.toByte()

        // Byte 1: M(1) + PT(7)
        header[1] = ((if (marker) 0x80 else 0x00) or PAYLOAD_TYPE_H264).toByte()

        // Bytes 2-3: Sequence number (network byte order)
        header[2] = ((seqNum shr 8) and 0xFF).toByte()
        header[3] = (seqNum and 0xFF).toByte()

        // Bytes 4-7: Timestamp (network byte order)
        header[4] = ((timestamp shr 24) and 0xFF).toByte()
        header[5] = ((timestamp shr 16) and 0xFF).toByte()
        header[6] = ((timestamp shr 8) and 0xFF).toByte()
        header[7] = (timestamp and 0xFF).toByte()

        // Bytes 8-11: SSRC (network byte order)
        header[8] = ((ssrc shr 24) and 0xFF).toByte()
        header[9] = ((ssrc shr 16) and 0xFF).toByte()
        header[10] = ((ssrc shr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()

        return header
    }

    /**
     * Strip Annex B start code from NAL unit if present.
     * start codes can be 0x00000001 (4 bytes) or 0x000001 (3 bytes).
     */
    fun stripStartCode(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        return when {
            data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 0.toByte() && data[3] == 1.toByte() ->
                data.copyOfRange(4, data.size)

            data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 1.toByte() ->
                data.copyOfRange(3, data.size)

            else -> data
        }
    }
}
