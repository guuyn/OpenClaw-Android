package ai.openclaw.android.di

import ai.openclaw.android.LogManager
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.local.BM25Index
import ai.openclaw.android.domain.memory.ColdStartManager
import ai.openclaw.android.domain.memory.EmbeddingService
import ai.openclaw.android.domain.memory.HybridSearchEngine
import ai.openclaw.android.ml.EmbeddingServiceFactory
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.security.SecurityKeyManager
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.viewmodel.ChatViewModel
import ai.openclaw.android.viewmodel.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI 模块定义
 *
 * 管理应用所有核心依赖的生命周期
 */
val appModule = module {
    // 单例：安全密钥管理器
    single { SecurityKeyManager(androidContext()) }

    // 单例：数据库
    single { AppDatabase.getInstance(androidContext()) }

    // 单例：技能管理器
    single { SkillManager(androidContext()).also { it.loadBuiltinSkills(androidContext()) } }

    // 单例：权限管理器
    single { PermissionManager(androidContext()) }

    // 单例：Embedding 服务（懒加载，初始化可能较慢）
    single<EmbeddingService> {
        EmbeddingServiceFactory.create(androidContext())
    }

    // 单例：BM25 内存索引
    single { BM25Index() }

    // 单例：混合搜索引擎
    single {
        HybridSearchEngine(
            bm25Index = get(),
            memoryDao = get<AppDatabase>().memoryDao(),
            vectorDao = get<AppDatabase>().memoryVectorDao(),
            embeddingService = get()
        )
    }

    // 单例：日志管理器
    single { LogManager.shared }

    // 单例：冷启动管理器
    single { ColdStartManager(androidContext()) }

    // ViewModel：聊天
    viewModel {
        ChatViewModel(
            skillManager = get(),
            permManager = get(),
            database = get(),
            embeddingService = get(),
            hybridSearchEngine = get()
        )
    }

    // ViewModel：设置
    viewModel {
        SettingsViewModel(
            database = get()
        )
    }
}
