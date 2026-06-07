package asia.nana7mi.arirang.model

enum class ClipboardAccessDecision(val value: Int) {
    DENY(0),
    ALLOW(1);

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value} ?: DENY
    }
}

enum class ClipboardUiResult(val value: Int) {
    DENY_ONCE(0),
    ALLOW_ONCE(1),
    ALLOW_ALWAYS(2),
    DENY_ALWAYS(3);

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: DENY_ONCE;
    }

    fun ClipboardUiResult.toAccessDecision() = when (this) {
        ALLOW_ONCE, ALLOW_ALWAYS -> ClipboardAccessDecision.ALLOW
        DENY_ONCE, DENY_ALWAYS -> ClipboardAccessDecision.DENY
    }

}

