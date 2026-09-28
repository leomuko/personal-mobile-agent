package dev.edgecompanion.app.navigation

import dev.edgecompanion.app.MainActivity
import dev.edgecompanion.app.DrawerActivity

import android.content.Context
import android.content.Intent

internal object AssistantIntents {
    fun drawer(context: Context) = Intent(context, DrawerActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun main(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
