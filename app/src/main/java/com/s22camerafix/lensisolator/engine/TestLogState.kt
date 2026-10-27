package com.s22camerafix.lensisolator.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val message: String,
    val isError: Boolean = false,
    val isWarning: Boolean = false,
    val isSuccess: Boolean = false
) {
    companion object {
        private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fun create(
            message: String,
            isError: Boolean = false,
            isWarning: Boolean = false,
            isSuccess: Boolean = false
        ): LogEntry {
            return LogEntry(
                timestamp = timeFormatter.format(Date()),
                message = message,
                isError = isError,
                isWarning = isWarning,
                isSuccess = isSuccess
            )
        }
    }
}
