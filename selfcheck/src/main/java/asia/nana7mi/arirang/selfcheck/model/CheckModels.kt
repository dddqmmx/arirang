package asia.nana7mi.arirang.selfcheck.model

enum class CheckState {
    VISIBLE,
    BLOCKED,
    LEAKED
}

data class CheckResult(
    val state: CheckState,
    val status: String,
    val content: String
)
