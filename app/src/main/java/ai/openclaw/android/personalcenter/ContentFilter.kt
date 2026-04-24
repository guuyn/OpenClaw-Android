package ai.openclaw.android.personalcenter

import ai.openclaw.android.personalcenter.models.CenterItem

/**
 * 内容过滤器 - 快速关键词过滤层
 * 职责：基于关键词黑白名单快速过滤噪音内容
 */
object ContentFilter {

    // ========== 黑名单关键词（广告/营销/系统噪音） ==========
    
    private val NOISE_KEYWORDS = setOf(
        // 中文广告营销
        "广告", "推广", "营销", "优惠", "折扣", "秒杀", "限时", "抢购", "特卖", "爆款",
        "新品", "热销", "爆款", "领券", "返现", "满减", "买赠", "拼团", "砍价",
        "抽奖", "中奖", "获奖", "恭喜", "大奖", "红包", "现金", "赚钱", "收益",
        "投资", "理财", "贷款", "信用卡", "办卡", "提额", "刷单", "兼职", "招聘",
        "下载", "安装", "注册", "登录", "会员", "VIP", "专属", "特权", "试用",
        
        // 英文广告营销
        "ad", "advertisement", "promotion", "marketing", "discount", "sale",
        "offer", "deal", "buy", "shop", "purchase", "order", "cart",
        "checkout", "free trial", "premium", "vip", "member", "subscription",
        "sign up", "register", "download", "install", "click here",
        "limited time", "exclusive", "special", "bonus", "reward",
        
        // 系统噪音（精确匹配，避免误杀正常内容）
        "WiFi 已连接", "WiFi 断开", "WiFi 连接失败",
        "电量不足", "电池管理", "Toast 通知",
        "清理大师", "加速大师", "内存清理", "垃圾清理",
        "空间不足请清理", "缓存清理"
    )

    // ========== 白名单关键词（直接放行） ==========
    
    private val WHITELIST_KEYWORDS = setOf(
        // 验证码相关
        "验证码", "verification", "otp", "code", "code is", "code:",
        // 银行金融
        "银行", "bank", "账户", "account", "余额", "balance", "交易", "transaction",
        "扣款", "charge", "还款", "repayment", "到账", "arrived", "成功",
        // 来电相关
        "来电", "通话", "电话", "call", "missed", "接听", "拒接",
        // 重要服务
        "政务", "政府", "公安", "医院", "医生", "挂号", "预约", "保险", "保单"
    )

    /**
     * 判断是否为噪音内容
     * 
     * 过滤逻辑：
     * 1. 如果包含白名单关键词 → 直接放行（false）
     * 2. 如果包含黑名单关键词 → 过滤掉（true）
     * 3. 其他情况 → 放行（false）
     *
     * @param item 待判断的中心项
     * @return true表示是噪音需要过滤，false表示非噪音可保留
     */
    fun isNoise(item: CenterItem): Boolean {
        val combinedText = (item.title + " " + item.body).lowercase()
        
        // 检查白名单 - 如果包含白名单关键词，直接放行
        if (WHITELIST_KEYWORDS.any { combinedText.contains(it, ignoreCase = true) }) {
            return false
        }
        
        // 检查黑名单 - 如果包含黑名单关键词，过滤掉
        if (NOISE_KEYWORDS.any { combinedText.contains(it, ignoreCase = true) }) {
            return true
        }
        
        // 默认放行
        return false
    }
}