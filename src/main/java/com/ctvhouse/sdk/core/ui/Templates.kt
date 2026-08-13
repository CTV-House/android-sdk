package com.ctvhouse.sdk.core.ui

internal object Templates {

    private val WHITESPACE = Regex("\\s+")

    fun format(template: String, vararg vars: Pair<String, String>): String {
        var out = template
        for ((key, value) in vars) {
            out = out.replace("\${$key}", value)
        }
        return out.replace(WHITESPACE, " ").trim()
    }

    fun marking(template: String, erid: String?): String =
        format(template, "ERID" to (erid?.takeIf { it.isNotBlank() }.orEmpty()))

    fun skipCountdown(template: String, seconds: Int): String =
        format(template, "SECONDS" to seconds.toString())
}
