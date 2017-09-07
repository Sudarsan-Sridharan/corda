package net.corda.nodeapi.internal.serialization.carpenter

import org.junit.Test

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
        println (clazz.constructors.size)
        println (clazz.isEnum)
        println (clazz.enumConstants)
//        val i2 = clazz.constructors[0].newInstance()
//        assertEquals(i, (i2 as SimpleFieldAccess)["ref"])
    }
}