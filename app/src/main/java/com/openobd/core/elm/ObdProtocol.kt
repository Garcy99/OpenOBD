package com.openobd.core.elm

/** ELM327 protocol numbers as used by ATSPx / ATDPN. */
enum class ObdProtocol(val code: Char, val label: String, val isCan: Boolean, val headerBytes: Int) {
    AUTO('0', "Automatic", false, 0),
    J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)", false, 3),
    J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)", false, 3),
    ISO_9141_2('3', "ISO 9141-2 (5 baud init)", false, 3),
    ISO_14230_4_KWP_5BAUD('4', "ISO 14230-4 KWP (5 baud init)", false, 3),
    ISO_14230_4_KWP_FAST('5', "ISO 14230-4 KWP (fast init)", false, 3),
    ISO_15765_4_CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", true, 2),
    ISO_15765_4_CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", true, 4),
    ISO_15765_4_CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", true, 2),
    ISO_15765_4_CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", true, 4),
    SAE_J1939_CAN('A', "SAE J1939 CAN (29 bit, 250 kbaud)", true, 4),
    USER1_CAN('B', "User1 CAN (11 bit, 125 kbaud)", true, 2),
    USER2_CAN('C', "User2 CAN (11 bit, 50 kbaud)", true, 2);

    val is29Bit: Boolean get() = headerBytes == 4

    companion object {
        /** Parses the answer to ATDPN, e.g. "A6" (auto, found 6) or "6". */
        fun fromDpn(response: String): ObdProtocol? {
            val c = response.trim().uppercase().removePrefix("A").firstOrNull() ?: return null
            return entries.firstOrNull { it.code == c }
        }

        val selectable: List<ObdProtocol> get() = entries.filter { it != SAE_J1939_CAN }
    }
}
