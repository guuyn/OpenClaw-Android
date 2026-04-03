package ai.openclaw.android.skill

import android.content.Context
import okhttp3.OkHttpClient

interface SkillContext {
    val applicationContext: Context
    val httpClient: OkHttpClient
    fun log(message: String)
}