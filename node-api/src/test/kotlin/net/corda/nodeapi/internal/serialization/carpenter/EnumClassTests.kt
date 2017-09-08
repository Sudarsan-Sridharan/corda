package net.corda.nodeapi.internal.serialization.carpenter

import org.junit.Test
import java.lang.reflect.Field
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumClassTests : AmqpCarpenterBase() {

    @Test
    fun oneValue() {
        val enumConstants = mapOf ("A" to EnumField())

        val schema = EnumSchema("gen.enum", enumConstants)

        cc.build(schema)
    }

    @Test
    fun oneValueInstantiate() {
        val enumConstants = mapOf ("A" to EnumField())
        val schema = EnumSchema("gen.enum", enumConstants)
        val clazz = cc.build(schema)

        assertTrue(clazz.isEnum)
        assertEquals(enumConstants.size, clazz.enumConstants.size)
        assertEquals("A", clazz.enumConstants.first().toString())
        assertEquals(0, (clazz.enumConstants.first() as Enum<*>).ordinal)
        assertEquals("A", (clazz.enumConstants.first() as Enum<*>).name)
    }

    @Test
    fun twoValuesInstantiate() {
        val enumConstants = mapOf ("left" to EnumField(), "right" to EnumField())
        val schema = EnumSchema("gen.enum", enumConstants)
        val clazz = cc.build(schema)

        assertTrue(clazz.isEnum)
        assertEquals(enumConstants.size, clazz.enumConstants.size)

        val left = clazz.enumConstants[0] as Enum<*>
        val right = clazz.enumConstants[1] as Enum<*>

        assertEquals(0, left.ordinal)
        assertEquals("left", left.name)
        assertEquals(1, right.ordinal)
        assertEquals("right", right.name)
    }

    @Test
    fun manyValues() {
        val enumConstants = listOf("AAA", "BBB", "CCC", "DDD", "EEE", "FFF",
                "GGG", "HHH", "III", "JJJ").associateBy({ it }, { EnumField() })
        val schema = EnumSchema("gen.enum", enumConstants)
        val clazz = cc.build(schema)

        assertTrue(clazz.isEnum)
        assertEquals(enumConstants.size, clazz.enumConstants.size)

        var idx = 0
        enumConstants.forEach {
            val constant = clazz.enumConstants[idx] as Enum<*>
            assertEquals(idx++, constant.ordinal)
            assertEquals(it.key, constant.name)
        }
    }

    @Test
    fun assignment() {
        val enumConstants = listOf("AAA", "BBB", "CCC", "DDD", "EEE", "FFF").associateBy({ it }, { EnumField() })
        val schema = EnumSchema("gen.enum", enumConstants)
        val clazz = cc.build(schema)

        assertTrue(clazz.isEnum)
        assertEquals(enumConstants.size, clazz.enumConstants.size)
    }

}