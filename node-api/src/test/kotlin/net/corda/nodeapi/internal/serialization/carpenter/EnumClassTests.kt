package net.corda.nodeapi.internal.serialization.carpenter

import org.junit.Test

class EnumClassTests : AmqpCarpenterBase() {
    @Test
    fun oneValue() {
        val enumConstants = mapOf ("A" to EnumField("A"))

        val schema = EnumSchema("gen.enum", enumConstants)

        cc.build(schema)
    }
}