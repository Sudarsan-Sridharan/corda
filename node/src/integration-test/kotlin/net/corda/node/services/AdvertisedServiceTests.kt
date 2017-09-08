package net.corda.node.services

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.ServiceType
import net.corda.core.utilities.getX500Name
import net.corda.node.services.FlowPermissions.Companion.startFlowPermission
import net.corda.nodeapi.User
import net.corda.testing.driver.driver
import org.junit.Test
import kotlin.test.assertTrue

class AdvertisedServiceTests {
    private val serviceName = CordaX500Name(organisation = "Custom Service", locality = "London", country = "GB")
    private val serviceType = ServiceType.corda.getSubType("custom")
    private val user = "bankA"
    private val pass = "passA"


    @StartableByRPC
    class ServiceTypeCheckingFlow : FlowLogic<Boolean>() {
        @Suspendable
        override fun call(): Boolean {
            return serviceHub.networkMapCache.getAnyServiceOfType(ServiceType.corda.getSubType("custom")) != null
        }
    }

    @Test
    fun `service is accessible through getAnyServiceOfType`() {
        driver(startNodesInProcess = true) {
            val bankA = startNode(rpcUsers = listOf(User(user, pass, setOf(startFlowPermission<ServiceTypeCheckingFlow>())))).get()
            startNode(advertisedServices = setOf(ServiceInfo(serviceType, serviceName))).get()
            bankA.rpcClientToNode().use(user, pass) { connection ->
                val result = connection.proxy.startFlow(::ServiceTypeCheckingFlow).returnValue.get()
                assertTrue(result)
            }
        }
    }
}
