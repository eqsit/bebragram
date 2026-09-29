package androidx.core.content
import android.content.SharedPreferences
inline fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) { edit().apply { block(); apply() } }
