package ai.openclaw.android.notification

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.util.Calendar

/**
 * 通知分类器
 * 根据应用来源、内容关键词、时间、ML模型等因素分类通知
 *
 * 分类优先级：
 * 1. 时间过滤（免打扰时段）
 * 2. 关键词检测（噪音/紧急）
 * 3. 飞书专用分类
 * 4. ML模型分类（TFLite）
 * 5. 应用包名优先级
 * 6. 启发式规则
 */
class NotificationClassifier(private val context: Context) {

    companion object {
        private const val TAG = "NotificationClassifier"

        // 免打扰时段
        private const val QUIET_HOUR_START = 22 // 22:00
        private const val QUIET_HOUR_END = 7    // 07:00

        // 工作时段
        private const val WORK_HOUR_START = 9
        private const val WORK_HOUR_END = 18
    }

    private val mlClassifier = NotificationMLClassifier(context)

    // ==================== 飞书（Feishu/Lark）通知类型 ====================

    private val feishuPackages = setOf(
        "com.feishu.ss",            // 飞书国内版
        "com.larksuite.oa",         // 飞书国际版 Lark
        "com.larksuite.suite",      // Lark Suite
        "com.ss.android.larksuite", // 飞书旧包名
    )

    /** 飞书紧急子类型：@提及、加急消息 */
    private val feishuUrgentKeywords = listOf(
        "有人@你", "@了你", "@all", "@所有人", "加急",
        "mentioned you", "urgent", "emergency",
        "审批催办", "加急审批", "[加急]",
    )

    /** 飞书重要子类型：审批、日程、会议、任务 */
    private val feishuImportantKeywords = listOf(
        "审批", "日程", "会议", "任务", "文档协作",
        "approval", "calendar", "meeting", "task",
        "doc shared", "invited you",
    )

    /** 飞书一般子类型：文档更新、周报等 */
    private val feishuNormalKeywords = listOf(
        "飞书桥", "飞书文档更新", "_okr", "周报",
        "newsletter", "weekly report",
    )

    /** 飞书噪音子类型：推荐、活动推送 */
    private val feishuNoiseKeywords = listOf(
        "飞书推荐", "飞书活动", "每日推荐",
        "recommended", "promotion",
    )

    // ==================== 应用优先级配置 ====================

    /**
     * 紧急应用（立即提醒）- 通讯类
     */
    private val urgentPackages = setOf(
        // 即时通讯
        "com.tencent.mm",                   // 微信
        "com.tencent.mobileqq",             // QQ
        "com.tencent.tim",                  // TIM
        "com.whatsapp",                     // WhatsApp
        "com.telegram.messenger",           // Telegram
        "com.viber.voip",                   // Viber
        "com.linecorp.LINE",                // Line
        "com.imo.android.imoim",            // IMO
        "org.telegram.messenger",           // Telegram OSS
        "com.Slack",                        // Slack（DM 通知紧急）

        // 电话/短信
        "com.android.phone",                // 电话
        "com.android.dialer",               // 拨号器
        "com.android.mms",                  // 短信
        "com.android.messaging",            // 信息
        "com.samsung.android.messaging",    // 三星短信
        "com.samsung.android.dialer",       // 三星电话
        "com.google.android.apps.messaging",// Google Messages
        "com.google.android.dialer",        // Google Phone

        // 安全/紧急
        "com.google.android.apps.safety",   // Google 安全
    )

    /**
     * 重要应用（5分钟汇总）- 工作/效率类
     */
    private val importantPackages = setOf(
        // 办公协作
        "com.alibaba.android.rimet",        // 钉钉
        "com.dingtalk",                     // 钉钉新包名
        "com.tencent.wework",               // 企业微信
        "com.work.weixin",                  // 企业微信新包名
        "com.microsoft.teams",              // Teams
        "com.microsoft.outlook",            // Outlook
        "com.slack",                        // Slack
        "com.discord",                      // Discord

        // 日历/邮件
        "com.google.android.calendar",      // Google 日历
        "com.samsung.android.calendar",     // 三星日历
        "com.android.calendar",             // 日历
        "com.google.android.gm",            // Gmail
        "cn.mail.126",                      // 网易邮箱
        "com.mail163",                      // 网易邮箱
        "com.qq.email",                     // QQ邮箱
        "com.tencent.qqmail",               // QQ邮箱
        "com.samsung.android.email",        // 三星邮箱

        // 文档/效率
        "cn.wps.moffice",                   // WPS
        "com.microsoft.office.outlook",     // Office
        "com.microsoft.office.word",        // Word
        "com.google.android.apps.docs",     // Google Docs

        // 网盘/存储
        "com.baidu.netdisk",                // 百度网盘
        "com.tencent.weishi",               // 腾讯微云
        "com.alicloud.databox",             // 阿里云盘

        // 出行/地图
        "com.baidu.BaiduMap",               // 百度地图
        "com.autonavi.minimap",             // 高德地图
        "com.sankuai.meituan",              // 美团
        "com.sankuai.meituan.takeoutnew",   // 美团外卖
        "com.dianping.v1",                  // 大众点评
        "com.sdu.didi.psnger",              // 滴滴出行
    )

    /**
     * 一般应用（30分钟汇总）- 社交/资讯/娱乐
     */
    private val normalPackages = setOf(
        // 浏览器
        "com.android.chrome",                           // Chrome
        "com.google.android.apps.chrome.main",          // Chrome
        "com.microsoft.emmx",                           // Edge
        "com.brave.browser",                            // Brave

        // 社交媒体（国际）
        "com.twitter.android",              // Twitter / X
        "com.instagram.android",            // Instagram
        "com.facebook.katana",              // Facebook
        "com.facebook.lite",                // Facebook Lite
        "com.linkedin.android",             // LinkedIn
        "com.reddit.frontpage",             // Reddit
        "com.tumblr",                       // Tumblr
        "com.zhiliaoapp.musically",         // TikTok 国际版

        // 社交媒体（国内）
        "com.zhihu.android",                // 知乎
        "com.weibo.international",          // 微博国际版
        "com.sina.weibo",                   // 微博
        "com.sina.weibolite",               // 微博轻享版
        "com.xiaomi.hm.health",             // 小米运动
        "com.xiaomi.jr.community",          // 小米社区

        // 短视频
        "com.smile.gifmaker",               // 快手
        "com.ss.android.ugc.aweme",         // 抖音
        "com.ss.android.ugc.aweme.lite",    // 抖音极速版

        // 资讯/阅读
        "com.netease.news",                 // 网易新闻
        "com.sohu.news",                    // 搜狐新闻
        "com.tencent.news",                 // 腾讯新闻
        "com.dragon.read",                  // 番茄小说
        "com.duokan.reader",                // 多看阅读
    )

    /**
     * 噪音应用（静默/丢弃）- 推广/广告/工具
     */
    private val noisePackages = setOf(
        "com.android.vending",                          // Google Play
        "com.google.android.googlequicksearchbox",      // Google 搜索
        "com.cleanmaster.mguard",                       // 清理大师
        "com.cleargreennow.memorybooster",              // 内存清理
        "com.ijinshan.duba",                            // 金山毒霸
        "com.qihoo.security",                           // 360安全卫士
        "com.qihoo.cleanmaster",                        // 360清理大师
        "com.baidu.appsearch",                          // 百度手机助手
        "com.tencent.android.qqdownloader",             // 应用宝
    )

    // ==================== 电商应用（促销噪音多，但有物流通知） ====================

    private val ecommercePackages = setOf(
        "com.taobao.taobao",                // 淘宝
        "com.taobao.idlefish",              // 闲鱼
        "com.tmall.wireless",               // 天猫
        "com.jingdong.app.mall",            // 京东
        "com.jingdong.pdl",                 // 京东
        "com.xunmeng.pinduoduo",            // 拼多多
        "com.xunmeng.pinduoduo.lite",       // 拼多多极速版
        "com.suning.mobile.ebuy",           // 苏宁易购
    )

    /** 物流相关关键词（电商通知中的有用信息） */
    private val logisticsKeywords = listOf(
        "已发货", "已揽收", "派送中", "已签收", "已送达", "待取件",
        "快递", "物流", "包裹", "驿站",
        "delivered", "shipped", "tracking", "package",
    )

    // ==================== 关键词库 ====================

    /** 噪音关键词 */
    private val noiseKeywords = listOf(
        // 中文广告/推广
        "广告", "推广", "优惠", "促销", "红包", "抽奖",
        "限时", "特价", "折扣", "满减", "秒杀", "优惠券",
        "恭喜您", "您已中奖", "点击领取", "立即抢购",
        "签到", "领券", "新人专享", "会员日", "大促",
        "猜你喜欢", "为你推荐", "专属优惠", "爆款",
        "直播预告", "开播提醒", "好物推荐", "限时秒",
        // English
        "advertisement", "promo", "sale", "discount",
        "limited time", "offer", "coupon", "deal",
        "subscribe now", "click here", "free trial",
    )

    /** 紧急关键词 */
    private val urgentKeywords = listOf(
        // 中文紧急
        "紧急", "重要", "紧急会议", "来电", "未接",
        "救命", "报警", "火警", "急救",
        "服务器告警", "系统告警", "故障", "宕机",
        // English
        "urgent", "important", "call", "missed",
        "emergency", "critical", "alert", "down",
        "outage", "incident", "sev1", "sev2",
    )

    /** 工作相关关键词（工作时段提升优先级） */
    private val workKeywords = listOf(
        "会议", "日程", "审批", "打卡", "日报", "周报",
        "项目", "需求", "上线", "发布", "部署",
        "meeting", "agenda", "approval", "deploy", "release",
    )

    // ==================== 时间上下文 ====================

    data class TimeContext(
        val hourOfDay: Int,
        val dayOfWeek: Int,
        val isWeekend: Boolean,
        val isQuietHours: Boolean,
        val isWorkHours: Boolean,
    )

    private fun getCurrentTimeContext(): TimeContext {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

        val isQuietHours = if (QUIET_HOUR_START > QUIET_HOUR_END) {
            hour >= QUIET_HOUR_START || hour < QUIET_HOUR_END
        } else {
            hour >= QUIET_HOUR_START && hour < QUIET_HOUR_END
        }

        val isWorkHours = !isWeekend && hour >= WORK_HOUR_START && hour < WORK_HOUR_END

        return TimeContext(
            hourOfDay = hour,
            dayOfWeek = dayOfWeek,
            isWeekend = isWeekend,
            isQuietHours = isQuietHours,
            isWorkHours = isWorkHours,
        )
    }

    // ==================== 分类逻辑 ====================

    /**
     * 分类通知
     */
    fun classify(notification: SmartNotification): NotificationCategory {
        val packageName = notification.packageName
        val title = notification.title.lowercase()
        val text = notification.text.lowercase()
        val content = "$title $text"
        val timeContext = getCurrentTimeContext()

        // 1. 飞书专用分类
        if (isFeishuPackage(packageName)) {
            return classifyFeishu(content, timeContext).also {
                Log.d(TAG, "Feishu classified as $it: ${notification.title}")
            }
        }

        // 2. 噪音关键词检测
        if (containsKeywords(content, noiseKeywords)) {
            Log.d(TAG, "Noise keyword detected: $content")
            return NotificationCategory.NOISE
        }

        // 3. 紧急关键词检测
        if (containsKeywords(content, urgentKeywords)) {
            Log.d(TAG, "Urgent keyword detected: $content")
            return adjustForTime(NotificationCategory.URGENT, timeContext)
        }

        // 4. 电商通知特殊处理：物流通知升级，其他降为噪音
        if (packageName in ecommercePackages) {
            return classifyEcommerce(content, timeContext)
        }

        // 5. ML模型分类（如果模型可用）
        val mlResult = mlClassifier.classify(notification)
        if (mlResult != null) {
            Log.d(TAG, "ML classification: $mlResult for ${notification.title}")
            return adjustForTime(mlResult, timeContext)
        }

        // 6. 包名优先级
        val packageResult = when {
            packageName in noisePackages -> NotificationCategory.NOISE
            packageName in urgentPackages -> NotificationCategory.URGENT
            packageName in importantPackages -> NotificationCategory.IMPORTANT
            packageName in normalPackages -> NotificationCategory.NORMAL
            else -> null
        }

        if (packageResult != null) {
            return adjustForTime(packageResult, timeContext)
        }

        // 7. 工作时段关键词提升
        if (timeContext.isWorkHours && containsKeywords(content, workKeywords)) {
            return NotificationCategory.IMPORTANT
        }

        // 8. 启发式分类
        return adjustForTime(classifyByHeuristics(notification), timeContext)
    }

    // ==================== 飞书专用分类 ====================

    private fun classifyFeishu(
        content: String,
        timeContext: TimeContext,
    ): NotificationCategory {
        if (containsKeywords(content, feishuNoiseKeywords)) {
            return NotificationCategory.NOISE
        }
        if (containsKeywords(content, feishuUrgentKeywords)) {
            return NotificationCategory.URGENT
        }
        if (containsKeywords(content, feishuImportantKeywords)) {
            return NotificationCategory.IMPORTANT
        }
        // 默认飞书消息在工作时段为重要，否则为一般
        return if (timeContext.isWorkHours) {
            NotificationCategory.IMPORTANT
        } else {
            NotificationCategory.NORMAL
        }
    }

    // ==================== 电商分类 ====================

    private fun classifyEcommerce(
        content: String,
        timeContext: TimeContext,
    ): NotificationCategory {
        // 物流通知保持为一般
        if (containsKeywords(content, logisticsKeywords)) {
            return NotificationCategory.NORMAL
        }
        // 其他电商通知（促销等）视为噪音
        return NotificationCategory.NOISE
    }

    // ==================== 时间调整 ====================

    /**
     * 免打扰时段：非紧急通知降级
     */
    private fun adjustForTime(
        category: NotificationCategory,
        timeContext: TimeContext,
    ): NotificationCategory {
        if (!timeContext.isQuietHours) return category

        return when (category) {
            NotificationCategory.URGENT -> NotificationCategory.URGENT
            NotificationCategory.IMPORTANT -> NotificationCategory.NORMAL
            NotificationCategory.NORMAL -> NotificationCategory.NOISE
            NotificationCategory.NOISE -> NotificationCategory.NOISE
            NotificationCategory.PENDING -> NotificationCategory.NORMAL
        }
    }

    // ==================== 启发式分类 ====================

    private fun classifyByHeuristics(notification: SmartNotification): NotificationCategory {
        val title = notification.title.lowercase()
        val text = notification.text.lowercase()

        if (isImMessage(title, text)) {
            return NotificationCategory.IMPORTANT
        }

        if (notification.packageName.startsWith("com.android.") ||
            notification.packageName.startsWith("com.google.android.")) {
            return NotificationCategory.NORMAL
        }

        return NotificationCategory.NORMAL
    }

    // ==================== 工具方法 ====================

    private fun isFeishuPackage(packageName: String): Boolean {
        return packageName in feishuPackages
    }

    private fun containsKeywords(content: String, keywords: List<String>): Boolean {
        return keywords.any { content.contains(it.lowercase()) }
    }

    private fun isImMessage(title: String, text: String): Boolean {
        val imPatterns = listOf(
            Regex(".*[:：].*"),  // "名字: 消息内容"
            Regex("@.*"),        // "@某人"
        )
        return imPatterns.any { it.matches(title) || it.matches(text) }
    }

    fun getAppName(packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 释放ML模型资源
     */
    fun close() {
        mlClassifier.close()
    }
}
