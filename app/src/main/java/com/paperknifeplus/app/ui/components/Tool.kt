package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.UUID

/** One History entry. [id] is unique (it keys the History list). */
data class ActivityEntry(
    val id: String,
    val name: String,
    val tool: String,
    val size: String,
    val icon: ImageVector,
    val uri: Uri? = null,
    val pageCount: Int = 0
)

data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val subtitle: String = "",
    val icon: ImageVector? = null,
    val category: String, // Edit, Secure, Convert, Optimize
    val color: androidx.compose.ui.graphics.Color,
    val bgColor: androidx.compose.ui.graphics.Color
)

/** This session's History, newest first. Compose state: change it on the main thread. */
object SessionManager {
    val history = mutableStateListOf<ActivityEntry>()

    fun addEntry(name: String, tool: String, size: String, icon: ImageVector, uri: Uri? = null, pageCount: Int = 0) {
        history.add(0, ActivityEntry(UUID.randomUUID().toString(), name, tool, size, icon, uri, pageCount))
    }

    fun clearHistory() {
        history.clear()
    }
}
