package com.signalx

object PasswordRulesHelper {

    fun containsReservedChars(password: String): Boolean {
        return password.contains(',') || password.contains('/')
    }
}