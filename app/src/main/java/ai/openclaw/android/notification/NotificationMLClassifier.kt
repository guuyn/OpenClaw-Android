package ai.openclaw.android.notification

import android.content.Context
import android.util.Log

/**
 * 基于规则的轻量通知分类器（TFLite 模型到位前的兜底实现）
 *
 * 分类流程：
 * 1. 包名白名单 → 快速分类
 * 2. 标题/内容关键词 → 语义分类
 * 3. 启发式规则 → 兜底分类
 */
class NotificationMLClassifier(private val context: Context) {

    companion object {
        private const val TAG = "NotificationMLClassifier"

        // ========== 包名 → 分类 映射 ==========

        private val SOCIAL_PACKAGES = setOf(
            "com.tencent.mm",           // 微信
            "com.tencent.mobileqq",     // QQ
            "com.ss.android.ugc.aweme", // 抖音
            "com.smile.gifmaker",       // 快手
            "com.sina.weibo",           // 微博
            "com.zhihu.android",       // 知乎
            "com.xiaomi.vipaccount",    // 小米社区
        )

        private val WORK_PACKAGES = setOf(
            "com.alibaba.android.rimet",    // 钉钉
            "com.tencent.wework",           // 企业微信
            "com.feishu.ss",                // 飞书
            "com.larksuite.suite",          // Lark
            "com.microsoft.teams",          // Teams
            "com.slack",                    // Slack
            "com.google.android.gm",        // Gmail
            "cn.mail.126",                  // 网易邮箱
            "com.qq.email",                 // QQ邮箱
            "com.google.android.calendar",  // Google 日历
        )

        private val FINANCE_PACKAGES = setOf(
            "com.alipay.android.phone.mobilecommon", // 支付宝
            "com.cmbchina.ccd.pluto.cmbActivity",    // 招商银行
            "com.icbc",                              // 工商银行
            "com.chinamworld.bocmbci",                // 建设银行
            "com.ecitic.bank.mobile",                 // 中信银行
        )

        private val SHOPPING_PACKAGES = setOf(
            "com.taobao.taobao",          // 淘宝
            "com.jingdong.app.mall",      // 京东
            "com.xunmeng.pinduoduo",      // 拼多多
            "com.tmall.wireless",         // 天猫
            "com.suning.mobile.ebuy",     // 苏宁
        )

        private val SYSTEM_PACKAGES = setOf(
            "com.android.phone",
            "com.android.dialer",
            "com.android.messaging",
            "com.google.android.dialer",
            "com.huawei.health",          // 华为运动健康
            "com.huawei.android.launcher",
            "com.android.systemui",
        )

        private val PROMO_PACKAGES = setOf(
            "com.android.vending",
            "com.cleanmaster.mguard",
            "com.qihoo.security",
            "com.tencent.android.qqdownloader",
            "com.baidu.appsearch",
        )

        // ========== 关键词 → 分类 映射 ==========

        private val SOCIAL_KEYWORDS = listOf(
            "发来一条消息", "撤回了一条消息", "拍了拍", "邀请你加入",
            "给你留言", "评论了你", "赞了你的", "回复了你",
            "新消息", "有人@你", "群聊", "私聊",
            "sent you a message", "mentioned you", "new message",
        )

        private val WORK_KEYWORDS = listOf(
            "审批", "会议", "日程", "任务", "打卡",
            "周报", "日报", "月报", "需求评审", "代码审查",
            "已发货", "派送中", "已签收", "快递", "物流",
            "approval", "meeting", "task", "calendar",
            "delivery", "shipped", "package arrived",
        )

        private val FINANCE_KEYWORDS = listOf(
            "账户变动", "消费", "转账", "到账", "余额",
            "信用卡", "还款", "账单", "支付",
            "transaction", "payment", "balance", "transfer",
        )

        private val PROMO_KEYWORDS = listOf(
            "优惠", "促销", "红包", "领券", "折扣",
            "限时", "特价", "满减", "秒杀", "猜你喜欢",
            "为你推荐", "签到", "新人专享", "会员日",
            "sale", "discount", "promo", "coupon", "limited time",
            "恭喜", "中奖", "点击领取",
        )

        private val SYSTEM_KEYWORDS = listOf(
            "来电", "未接来电", "短信", "验证码",
            "电量不足", "存储空间", "系统更新",
            "missed call", "low battery", "storage",
            "Wi-Fi", "蓝牙", "飞行模式",
        )
    }

    /**
     * 分类通知
     * @return 分类结果（不再返回 null）
     */
    fun classify(notification: SmartNotification): NotificationCategory {
        val packageName = notification.packageName
        val title = notification.title
        val text = notification.text
        val content = "$title $text".lowercase()

        Log.d(TAG, "Classifying: pkg=$packageName, title=$title")

        // 1. 包名白名单快速匹配
        val packageCategory = classifyByPackage(packageName)
        if (packageCategory != null) {
            Log.d(TAG, "Package matched: $packageCategory")
            return packageCategory
        }

        // 2. 关键词匹配（按优先级）
        // 财务关键词优先级最高（涉及资金）
        if (containsAny(content, FINANCE_KEYWORDS)) {
            Log.d(TAG, "Finance keyword matched")
            return NotificationCategory.IMPORTANT
        }

        // 工作关键词
        if (containsAny(content, WORK_KEYWORDS)) {
            Log.d(TAG, "Work keyword matched")
            return NotificationCategory.IMPORTANT
        }

        // 社交关键词
        if (containsAny(content, SOCIAL_KEYWORDS)) {
            Log.d(TAG, "Social keyword matched")
            return NotificationCategory.NORMAL
        }

        // 系统关键词
        if (containsAny(content, SYSTEM_KEYWORDS)) {
            Log.d(TAG, "System keyword matched")
            return NotificationCategory.URGENT
        }

        // 促销/广告关键词 → 噪音
        if (containsAny(content, PROMO_KEYWORDS)) {
            Log.d(TAG, "Promo keyword matched")
            return NotificationCategory.NOISE
        }

        // 3. 启发式兜底
        return classifyByHeuristics(notification)
    }

    /**
     * 根据包名快速分类
     */
    private fun classifyByPackage(packageName: String): NotificationCategory? {
        return when {
            packageName in SYSTEM_PACKAGES -> NotificationCategory.URGENT
            packageName in WORK_PACKAGES -> NotificationCategory.IMPORTANT
            packageName in FINANCE_PACKAGES -> NotificationCategory.IMPORTANT
            packageName in SOCIAL_PACKAGES -> NotificationCategory.NORMAL
            packageName in SHOPPING_PACKAGES -> NotificationCategory.NOISE
            packageName in PROMO_PACKAGES -> NotificationCategory.NOISE
            else -> null
        }
    }

    /**
     * 启发式兜底分类
     */
    private fun classifyByHeuristics(notification: SmartNotification): NotificationCategory {
        val title = notification.title
        val text = notification.text

        // 短消息 + 有冒号 → 可能是聊天消息
        if (title.contains(":") || title.contains("：")) {
            return NotificationCategory.NORMAL
        }

        // 包含链接 → 可能是推广
        if (text.contains("http://") || text.contains("https://")) {
            return NotificationCategory.NOISE
        }

        // 默认一般通知
        return NotificationCategory.NORMAL
    }

    /**
     * 检查内容是否包含任意关键词
     */
    private fun containsAny(content: String, keywords: List<String>): Boolean {
        return keywords.any { content.contains(it.lowercase()) }
    }

    fun close() {
        Log.d(TAG, "Classifier closed")
    }
}
