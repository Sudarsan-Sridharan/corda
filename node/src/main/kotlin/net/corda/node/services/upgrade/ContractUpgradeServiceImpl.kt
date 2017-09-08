package net.corda.node.services.upgrade

import net.corda.core.contracts.StateRef
import net.corda.core.contracts.UpgradedContract
import net.corda.core.node.services.ContractUpgradeService
import net.corda.node.utilities.NODE_DATABASE_PREFIX
import net.corda.node.utilities.PersistentMap
import javax.persistence.*

class ContractUpgradeServiceImpl : ContractUpgradeService {

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}contract_upgrades")
    class DBContractUpgrade(
            @Id
            @Column(name = "state_ref", length = 96)
            var stateRef: String = "",

            /** refers to the UpgradedContract class name*/
            @Column(name = "contract_class_name")
            var upgradedContractClassName: String = ""
    )

    private companion object {
        fun createContractUpgradesMap(): PersistentMap<String, String, DBContractUpgrade, String> {
            return PersistentMap(
                    toPersistentEntityKey = { it },
                    fromPersistentEntity = { Pair(it.stateRef, it.upgradedContractClassName) },
                    toPersistentEntity = { key: String, value: String ->
                        DBContractUpgrade().apply {
                            stateRef = key
                            upgradedContractClassName = value
                        }
                    },
                    persistentEntityClass = DBContractUpgrade::class.java
            )
        }
    }

    private val authorisedUpgrade = createContractUpgradesMap()

    override fun getAuthorisedContractUpgrade(ref: StateRef) = authorisedUpgrade[ref.toString()]

    override fun storeAuthorisedContractUpgrade(ref: StateRef, upgradedContractClass: Class<out UpgradedContract<*, *>>) {
        authorisedUpgrade.put(ref.toString(), upgradedContractClass.name)
    }

    override fun removeAuthorisedContractUpgrade(ref: StateRef) {
        authorisedUpgrade.remove(ref.toString())
    }
}
