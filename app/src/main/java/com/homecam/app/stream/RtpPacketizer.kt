package com.homecam.app.stream

import java.net.DatagramPacket
import java.net.InetAddress

class RtpPacketizer {

    companion object {
        private const val TAG = "RtpPacketizer"
        private const val RTP_VERSION = 0x80
        private const val PAYLOAD_TYPE_H264 = 96
        private const val MAX_RTP_PAYLOAD = 1400
        private const val RTP_HEADER_SIZE = 12

        private const val NAL_FU_A = 28
        private const val FU_HEADER_S = 0x80
        private const val FU_HEADER_E = 0x40
    }

    private var ssrc: Long = (1000000L..9999999L).random()

    data class PacketResult(
        val packets: List<DatagramPacket>,
        val nextSeqNum: Int
    )

    data class PacketResultRaw(
        val packets: List<ByteArray>,
        val nextSeqNum: Int
    )

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
        val rawResult = packetizeNaluRaw(nalu, timestamp, startSeqNum, marker)
        val packets = rawResult.packets.map { data ->
            DatagramPacket(data, data.size, targetAddress, targetPort)
        }
        return PacketResult(packets, rawResult.nextSeqNum)
    }

    fun packetizeNaluRaw(
        nalu: ByteArray,
        timestamp: Long,
        startSeqNum: Int,
        marker: Boolean
    ): PacketResultRaw {
        val packets = mutableListOf<ByteArray>()
        var seqNum = startSeqNum

        if (nalu.size <= MAX_RTP_PAYLOAD) {
            val rtpHeader = createRtpHeader(seqNum, timestamp, ssrc, marker && true)
            val packetData = rtpHeader + nalu
            packets.add(packetData)
            seqNum++
        } else {
            val nalHeader = nalu[0].toInt() and 0xFF
            val nalType = nalHeader and 0x1F
            val fuIndicator = (nalHeader and 0xE0) or NAL_FU_A

            var offset = 0
            var fragmentCount = 0

            while (offset < nalu.size - 1) {
                val fragmentSize = minOf(MAX_RTP_PAYLOAD, nalu.size - 1 - offset)
                fragmentCount++
                val isFirst = fragmentCount == 1
                val isLast = (offset + fragmentSize) >= (nalu.size - 1)

                var fuHeader = nalType
                if (isFirst) fuHeader = fuHeader or FU_HEADER_S
                if (isLast) fuHeader = fuHeader or FU_HEADER_E

                val isMarker = isLast && marker
                val rtpHeader = createRtpHeader(seqNum, timestamp, ssrc, isMarker)
                val fragmentData = nalu.copyOfRange(1 + offset, 1 + offset + fragmentSize)
                val packetData = rtpHeader + byteArrayOf(fuIndicator.toByte()) + byteArrayOf(fuHeader.toByte()) + fragmentData

                packets.add(packetData)
                seqNum++
                offset += fragmentSize
            }
        }

        return PacketResultRaw(packets, seqNum)
    }

    private fun createRtpHeader(seqNum: Int, timestamp: Long, ssrc: Long, marker: Boolean): ByteArray {
        val header = ByteArray(RTP_HEADER_SIZE)
        header[0] = RTP_VERSION.toByte()
        header[1] = ((if (marker) 0x80 else 0x00) or PAYLOAD_TYPE_H264).toByte()
        header[2] = ((seqNum shr 8) and 0xFF).toByte()
        header[3] = (seqNum and 0xFF).toByte()
        header[4] = ((timestamp shr 24) and 0xFF).toByte()
        header[5] = ((timestamp shr 16) and 0xFF).toByte()
        header[6] = ((timestamp shr 8) and 0xFF).toByte()
        header[7] = (timestamp and 0xFF).toByte()
        header[8] = ((ssrc shr 24) and 0xFF).toByte()
        header[9] = ((ssrc shr 16) and 0xFF).toByte()
        header[10] = ((ssrc shr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }

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