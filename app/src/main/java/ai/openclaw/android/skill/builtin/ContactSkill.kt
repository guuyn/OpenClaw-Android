package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

class ContactSkill(private val context: Context) : Skill {
    override val id = "contact"
    override val name = "通讯录"
    override val description = "查询联系人信息和拨打电话"
    override val version = "2.0.0"
    
    override val instructions = """
# Contact Skill

查询通讯录联系人，发起电话呼叫。

## 用法
- search_contacts: 按姓名或号码搜索联系人
- get_contact: 获取联系人详情
- call_contact: 拨打电话

## A2UI 卡片输出格式（参考）
[A2UI]{"type":"contact","data":{"name":"姓名","phone":"电话","email":"邮箱","address":"地址"},"actions":[{"label":"📞 拨打电话","action":"call_contact","style":"Primary"}]}[/A2UI]
"""

    data class ContactInfo(
        val id: Long,
        val name: String,
        val phone: String
    )

    data class ContactDetail(val email: String?, val address: String?)
    
    override val tools: List<SkillTool> = listOf(
        // search_contacts tool
        object : SkillTool {
            override val name = "search_contacts"
            override val description = "搜索联系人"
            override val parameters = mapOf(
                "query" to SkillParam("string", "搜索关键词（姓名或号码）", true)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val query = params["query"] as? String
                if (query.isNullOrBlank()) return SkillResult(false, "", "缺少 query 参数")
                
                if (!hasContactsPermission()) {
                    return SkillResult(false, "", "需要通讯录权限")
                }
                
                return try {
                    val contacts = searchContacts(query)
                    if (contacts.isEmpty()) {
                        return SkillResult(true, "未找到匹配的联系人: $query")
                    } else {
                        // 返回第一个联系人的 ContactCard
                        val first = contacts.first()
                        val detailed = getContactDetail(first.id)
                        
                        val cardJson = buildContactCardV2(
                            name = first.name,
                            phone = first.phone,
                            email = detailed?.email,
                            address = detailed?.address
                        )
                        SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                    }
                } catch (e: Exception) {
                    return SkillResult(false, "", "搜索失败: ${e.message}")
                }
            }
            
            private fun searchContacts(query: String): List<ContactInfo> {
                val contacts = mutableListOf<ContactInfo>()
                val resolver = context.contentResolver
                
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
                val selectionArgs = arrayOf("%$query%", "%$query%")
                
                val cursor: Cursor? = resolver.query(uri, projection, selection, selectionArgs, null)
                
                cursor?.use {
                    val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    
                    while (it.moveToNext()) {
                        val id = it.getLong(idIndex)
                        val name = it.getString(nameIndex) ?: ""
                        val phone = it.getString(phoneIndex) ?: ""
                        
                        // Avoid duplicates
                        if (contacts.none { c -> c.id == id }) {
                            contacts.add(ContactInfo(id, name, phone))
                        }
                    }
                }
                
                return contacts
            }

            private fun getContactDetail(contactId: Long): ContactDetail? {
                val resolver = context.contentResolver
                val uri = ContactsContract.Data.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Email.DATA,
                    ContactsContract.CommonDataKinds.StructuredPostal.DATA
                )
                val selection = "${ContactsContract.Data.CONTACT_ID} = ?"
                val selectionArgs = arrayOf(contactId.toString())

                var email: String? = null
                var address: String? = null

                resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    val mimeTypeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                    val dataIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)

                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeTypeIndex)
                        val data = cursor.getString(dataIndex)
                        if (data.isNullOrBlank()) continue

                        when (mimeType) {
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                                if (email == null) email = data
                            }
                            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                                if (address == null) address = data
                            }
                        }
                    }
                }

                return if (email != null || address != null) ContactDetail(email, address) else null
            }
        },
        
        // get_contact tool
        object : SkillTool {
            override val name = "get_contact"
            override val description = "获取联系人详情"
            override val parameters = mapOf(
                "name" to SkillParam("string", "联系人姓名", true)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val name = params["name"] as? String
                if (name.isNullOrBlank()) return SkillResult(false, "", "缺少 name 参数")
                
                if (!hasContactsPermission()) {
                    return SkillResult(false, "", "需要通讯录权限")
                }
                
                return try {
                    val contact = getContactByName(name)
                    if (contact != null) {
                        val detail = getContactDetail(contact.id)
                        val cardJson = buildContactCardV2(
                            name = contact.name,
                            phone = contact.phone,
                            email = detail?.email,
                            address = detail?.address
                        )
                        SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                    } else {
                        SkillResult(false, "", "未找到联系人: $name")
                    }
                } catch (e: Exception) {
                    SkillResult(false, "", "查询失败: ${e.message}")
                }
            }
            
            private fun getContactByName(name: String): ContactInfo? {
                val resolver = context.contentResolver
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(name)
                
                val cursor: Cursor? = resolver.query(uri, projection, selection, selectionArgs, null)
                
                return cursor?.use {
                    if (it.moveToFirst()) {
                        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        
                        ContactInfo(
                            it.getLong(idIndex),
                            it.getString(nameIndex) ?: "",
                            it.getString(phoneIndex) ?: ""
                        )
                    } else null
                }
            }

            private fun getContactDetail(contactId: Long): ContactDetail? {
                val resolver = context.contentResolver
                val uri = ContactsContract.Data.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Email.DATA,
                    ContactsContract.CommonDataKinds.StructuredPostal.DATA
                )
                val selection = "${ContactsContract.Data.CONTACT_ID} = ?"
                val selectionArgs = arrayOf(contactId.toString())

                var email: String? = null
                var address: String? = null

                resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    val mimeTypeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                    val dataIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)

                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeTypeIndex)
                        val data = cursor.getString(dataIndex)
                        if (data.isNullOrBlank()) continue

                        when (mimeType) {
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                                if (email == null) email = data
                            }
                            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                                if (address == null) address = data
                            }
                        }
                    }
                }

                return if (email != null || address != null) ContactDetail(email, address) else null
            }
        },
        
        // call_contact tool
        object : SkillTool {
            override val name = "call_contact"
            override val description = "拨打电话"
            override val parameters = mapOf(
                "phone_number" to SkillParam("string", "电话号码（可选，与contact_name二选一）", false),
                "contact_name" to SkillParam("string", "联系人姓名（可选，与phone_number二选一）", false)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                var phoneNumber = params["phone_number"] as? String
                val contactName = params["contact_name"] as? String
                
                if (phoneNumber.isNullOrBlank() && contactName.isNullOrBlank()) {
                    return SkillResult(false, "", "需要提供 phone_number 或 contact_name")
                }
                
                // If contact_name provided, look up phone number
                if (phoneNumber.isNullOrBlank() && !contactName.isNullOrBlank()) {
                    if (!hasContactsPermission()) {
                        return SkillResult(false, "", "需要通讯录权限")
                    }
                    
                    val contact = getContactByName(contactName)
                    if (contact == null) {
                        return SkillResult(false, "", "未找到联系人: $contactName")
                    }
                    phoneNumber = contact.phone
                }
                
                return try {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$phoneNumber")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    
                    SkillResult(true, "正在拨打: $phoneNumber")
                } catch (e: Exception) {
                    SkillResult(false, "", "拨号失败: ${e.message}")
                }
            }
            
            private fun getContactByName(name: String): ContactInfo? {
                val resolver = context.contentResolver
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("%$name%")
                
                val cursor: Cursor? = resolver.query(uri, projection, selection, selectionArgs, null)
                
                return cursor?.use {
                    if (it.moveToFirst()) {
                        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        
                        ContactInfo(
                            it.getLong(idIndex),
                            it.getString(nameIndex) ?: "",
                            it.getString(phoneIndex) ?: ""
                        )
                    } else null
                }
            }
        }
    )
    
    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}

    // ==================== v2 A2UI Card JSON 构建 ====================

    @OptIn(ExperimentalSerializationApi::class)
    internal fun buildContactCardV2(
        name: String,
        phone: String?,
        email: String? = null,
        address: String? = null
    ): String {
        val dataMap = mutableMapOf<String, JsonElement>(
            "name" to JsonPrimitive(name)
        )
        if (phone != null) dataMap["phone"] = JsonPrimitive(phone)
        if (email != null) dataMap["email"] = JsonPrimitive(email)
        if (address != null) dataMap["address"] = JsonPrimitive(address)

        val actionsList = mutableListOf<JsonObject>()
        if (phone != null) {
            actionsList.add(
                JsonObject(
                    mapOf(
                        "label" to JsonPrimitive("📞 拨打电话"),
                        "action" to JsonPrimitive("call_contact"),
                        "style" to JsonPrimitive("Primary")
                    )
                )
            )
        }
        actionsList.add(
            JsonObject(
                mapOf(
                    "label" to JsonPrimitive("📋 复制号码"),
                    "action" to JsonPrimitive("copy_contact"),
                    "style" to JsonPrimitive("Secondary")
                )
            )
        )

        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("contact"),
                "data" to JsonObject(dataMap),
                "actions" to JsonArray(actionsList)
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }
}
