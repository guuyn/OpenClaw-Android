package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class SMSSkill(private val context: Context) : Skill {
    override val id = "sms"
    override val name = "短信"
    override val description = "读取和发送短信"
    override val version = "1.0.0"
    
    override val instructions = """
# SMS Skill

读取和发送短信消息。

## 用法
- send_sms: 发送短信
- read_sms: 读取最近的短信
- get_unread_sms: 获取未读短信数量
"""
    
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    
    override val tools: List<SkillTool> = listOf(
        // send_sms tool
        object : SkillTool {
            override val name = "send_sms"
            override val description = "发送短信"
            override val parameters = mapOf(
                "phone_number" to SkillParam("string", "接收方电话号码", true),
                "message" to SkillParam("string", "短信内容", true)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val phoneNumber = params["phone_number"] as? String
                if (phoneNumber.isNullOrBlank()) return SkillResult(false, "", "缺少 phone_number 参数")
                
                val message = params["message"] as? String
                if (message.isNullOrBlank()) return SkillResult(false, "", "缺少 message 参数")
                
                if (!hasSendSmsPermission()) {
                    return SkillResult(false, "", "需要发送短信权限")
                }
                
                return try {
                    val smsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                    SkillResult(true, "短信已发送到 $phoneNumber")
                } catch (e: Exception) {
                    SkillResult(false, "", "发送失败: ${e.message}")
                }
            }
        },
        
        // read_sms tool
        object : SkillTool {
            override val name = "read_sms"
            override val description = "读取最近的短信"
            override val parameters = mapOf(
                "limit" to SkillParam("number", "读取数量（默认10条）", false, 10),
                "from" to SkillParam("string", "发件人号码筛选（可选）", false)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                if (!hasReadSmsPermission()) {
                    return SkillResult(false, "", "需要读取短信权限")
                }
                
                val limit = (params["limit"] as? Number)?.toInt() ?: 10
                val fromFilter = params["from"] as? String
                
                return try {
                    val messages = readSmsMessages(limit, fromFilter)
                    if (messages.isEmpty()) {
                        SkillResult(true, "没有短信记录")
                    } else {
                        val list = messages.joinToString("\n") { m ->
                            "- [${m.sender}] ${m.body.take(50)}... (${dateFormat.format(Date(m.date))})"
                        }
                        SkillResult(true, "最近 ${messages.size} 条短信:\n$list")
                    }
                } catch (e: Exception) {
                    SkillResult(false, "", "读取失败: ${e.message}")
                }
            }
            
            private fun readSmsMessages(limit: Int, fromFilter: String?): List<SMSInfo> {
                val messages = mutableListOf<SMSInfo>()
                val resolver = context.contentResolver
                
                val uri = Uri.parse("content://sms/inbox")
                val projection = arrayOf("_id", "address", "body", "date")
                
                val selection = if (!fromFilter.isNullOrBlank()) {
                    "address LIKE ?"
                } else null
                
                val selectionArgs = if (!fromFilter.isNullOrBlank()) {
                    arrayOf("%$fromFilter%")
                } else null
                
                val cursor: Cursor? = resolver.query(uri, projection, selection, selectionArgs, "date DESC LIMIT $limit")
                
                cursor?.use {
                    val idIndex = it.getColumnIndex("_id")
                    val addressIndex = it.getColumnIndex("address")
                    val bodyIndex = it.getColumnIndex("body")
                    val dateIndex = it.getColumnIndex("date")
                    
                    while (it.moveToNext()) {
                        messages.add(SMSInfo(
                            id = it.getLong(idIndex),
                            sender = it.getString(addressIndex) ?: "未知",
                            body = it.getString(bodyIndex) ?: "",
                            date = it.getLong(dateIndex)
                        ))
                    }
                }
                
                return messages
            }
        },
        
        // get_unread_sms tool
        object : SkillTool {
            override val name = "get_unread_sms"
            override val description = "获取未读短信数量"
            override val parameters = emptyMap<String, SkillParam>()
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                if (!hasReadSmsPermission()) {
                    return SkillResult(false, "", "需要读取短信权限")
                }
                
                return try {
                    val count = getUnreadCount()
                    SkillResult(true, "未读短信: $count 条")
                } catch (e: Exception) {
                    SkillResult(false, "", "查询失败: ${e.message}")
                }
            }
            
            private fun getUnreadCount(): Int {
                val resolver = context.contentResolver
                val uri = Uri.parse("content://sms/inbox")
                val projection = arrayOf("_id")
                val selection = "read = 0"
                
                val cursor = resolver.query(uri, projection, selection, null, null)
                val count = cursor?.count ?: 0
                cursor?.close()
                
                return count
            }
        }
    )
    
    data class SMSInfo(
        val id: Long,
        val sender: String,
        val body: String,
        val date: Long
    )
    
    private fun hasSendSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}
}