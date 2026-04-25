package org.a2ui.compose.data

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals

class DataModelProcessorTest {

    private val processor = DataModelProcessor()

    @Test
    fun testCreateSurface() {
        processor.createSurface("test_surface")
        assertNotNull(processor.getDataModel("test_surface"))
    }

    @Test
    fun testDeleteSurface() {
        processor.createSurface("test_surface")
        processor.deleteSurface("test_surface")
        assertNull(processor.getDataModel("test_surface"))
    }

    @Test
    fun testUpdateDataModel() {
        processor.createSurface("test_surface")
        processor.updateDataModel("test_surface", "/name", "John")
        assertEquals("John", processor.getValue("test_surface", "/name"))
    }

    @Test
    fun testResolveDynamicValue_literal() {
        processor.createSurface("test_surface")
        val literal = DynamicValue.LiteralValue("Hello")
        assertEquals("Hello", processor.resolveDynamicValue("test_surface", literal))
    }

    @Test
    fun testResolveDynamicValue_path() {
        processor.createSurface("test_surface")
        processor.updateDataModel("test_surface", "/user/name", "John")
        
        val pathValue = DynamicValue.PathValue<String>("/user/name")
        assertEquals("John", processor.resolveDynamicValue("test_surface", pathValue))
    }

    @Test
    fun testResolveFunctionCall_required() {
        val functionCall = FunctionCall("required", mapOf("value" to ""))
        val result1: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(functionCall))
        assertTrue((result1 as? Boolean) == false)

        val functionCall2 = FunctionCall("required", mapOf("value" to "test"))
        val result2: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(functionCall2))
        assertTrue((result2 as? Boolean) == true)
    }

    @Test
    fun testResolveFunctionCall_email() {
        val validEmail = FunctionCall("email", mapOf("value" to "test@example.com"))
        val result1: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(validEmail))
        assertTrue((result1 as? Boolean) == true)

        val invalidEmail = FunctionCall("email", mapOf("value" to "invalid-email"))
        val result2: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(invalidEmail))
        assertTrue((result2 as? Boolean) == false)
    }

    @Test
    fun testResolveFunctionCall_url() {
        val validUrl = FunctionCall("url", mapOf("value" to "https://example.com"))
        val result1: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(validUrl))
        assertTrue((result1 as? Boolean) == true)

        val invalidUrl = FunctionCall("url", mapOf("value" to "not-a-url"))
        val result2: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(invalidUrl))
        assertTrue((result2 as? Boolean) == false)
    }

    @Test
    fun testResolveFunctionCall_phone() {
        val validPhone = FunctionCall("phone", mapOf("value" to "1234567890"))
        val result1: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(validPhone))
        assertTrue((result1 as? Boolean) == true)

        val invalidPhone = FunctionCall("phone", mapOf("value" to "123"))
        val result2: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(invalidPhone))
        assertTrue((result2 as? Boolean) == false)
    }

    @Test
    fun testResolveFunctionCall_length() {
        val validLength = FunctionCall("length", mapOf("value" to "hello", "min" to 1, "max" to 10))
        val result1: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(validLength))
        assertTrue((result1 as? Boolean) == true)

        val invalidLength = FunctionCall("length", mapOf("value" to "hello world this is too long", "max" to 10))
        val result2: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(invalidLength))
        assertTrue((result2 as? Boolean) == false)
    }

    @Test
    fun testResolveFunctionCall_and() {
        val allTrue = FunctionCall("and", mapOf("values" to listOf(true, true, true)))
        val result1: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(allTrue))
        assertTrue((result1 as? Boolean) == true)

        val someFalse = FunctionCall("and", mapOf("values" to listOf(true, false, true)))
        val result2: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(someFalse))
        assertTrue((result2 as? Boolean) == false)
    }

    @Test
    fun testResolveFunctionCall_or() {
        val someTrue = FunctionCall("or", mapOf("values" to listOf(false, true, false)))
        val result1: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(someTrue))
        assertTrue((result1 as? Boolean) == true)

        val allFalse = FunctionCall("or", mapOf("values" to listOf(false, false, false)))
        val result2: Any? = processor.resolveDynamicValue("test_surface", DynamicValue.FunctionValue<Any>(allFalse))
        assertTrue((result2 as? Boolean) == false)
    }

    @Test
    fun testClear() {
        processor.createSurface("surface1")
        processor.createSurface("surface2")
        processor.clear()
        assertNull(processor.getDataModel("surface1"))
        assertNull(processor.getDataModel("surface2"))
    }
}
