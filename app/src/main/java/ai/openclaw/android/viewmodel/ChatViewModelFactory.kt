package ai.openclaw.android.viewmodel

import android.app.Application
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.domain.memory.EmbeddingService
import ai.openclaw.android.domain.memory.HybridSearchEngine
import ai.openclaw.android.ml.EmbeddingServiceFactory
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Factory for creating ChatViewModel with all required dependencies.
 */
class ChatViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            val skillManager = SkillManager(application).also {
                it.loadBuiltinSkills(application)
            }
            val permManager = PermissionManager(application)
            val database = AppDatabase.getInstance(application)
            val embeddingService = EmbeddingServiceFactory.create(application)
            val hybridSearchEngine = HybridSearchEngine(
                bm25Index = ai.openclaw.android.data.local.BM25Index(),
                memoryDao = database.memoryDao(),
                vectorDao = database.memoryVectorDao(),
                embeddingService = embeddingService
            )
            return ChatViewModel(
                skillManager = skillManager,
                permManager = permManager,
                database = database,
                embeddingService = embeddingService,
                hybridSearchEngine = hybridSearchEngine
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
