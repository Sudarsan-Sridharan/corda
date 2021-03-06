package net.corda.docs

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.PluginServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.unwrap
import net.corda.finance.flows.AbstractCashFlow
import net.corda.finance.flows.CashException
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.flows.CashPaymentFlow
import java.util.*

// DOCSTART CustomVaultQuery
object CustomVaultQuery {

    @CordaService
    class Service(val services: PluginServiceHub) : SingletonSerializeAsToken() {
        private companion object {
            val log = loggerFor<Service>()
        }
        fun rebalanceCurrencyReserves(): List<Amount<Currency>> {
            val nativeQuery = """
                select
                    cashschema.ccy_code,
                    sum(cashschema.pennies)
                from
                    vault_states vaultschema
                join
                    contract_cash_states cashschema
                where
                    vaultschema.output_index=cashschema.output_index
                    and vaultschema.transaction_id=cashschema.transaction_id
                    and vaultschema.state_status=0
                group by
                    cashschema.ccy_code
                order by
                    sum(cashschema.pennies) desc
            """
            log.info("SQL to execute: $nativeQuery")
            val session = services.jdbcSession()
            val prepStatement = session.prepareStatement(nativeQuery)
            val rs = prepStatement.executeQuery()
            var topUpLimits: MutableList<Amount<Currency>> = mutableListOf()
            while (rs.next()) {
                val currencyStr = rs.getString(1)
                val amount = rs.getLong(2)
                log.info("$currencyStr : $amount")
                topUpLimits.add(Amount(amount, Currency.getInstance(currencyStr)))
            }
            return topUpLimits
        }
    }
}
// DOCEND CustomVaultQuery

/**
 *  This is a slightly modified version of the IssuerFlow, which uses a 3rd party custom query to
 *  retrieve a list of currencies and top up amounts to be used in the issuance.
 */

object TopupIssuerFlow {
    @CordaSerializable
    data class TopupRequest(val issueToParty: Party,
                            val issuerPartyRef: OpaqueBytes,
                            val notaryParty: Party)
    @InitiatingFlow
    @StartableByRPC
    class TopupIssuanceRequester(val issueToParty: Party,
                                 val issueToPartyRef: OpaqueBytes,
                                 val issuerBankParty: Party,
                                 val notaryParty: Party) : FlowLogic<List<AbstractCashFlow.Result>>() {
        @Suspendable
        @Throws(CashException::class)
        override fun call(): List<AbstractCashFlow.Result> {
            val topupRequest = TopupRequest(issueToParty, issueToPartyRef, notaryParty)
            return sendAndReceive<List<AbstractCashFlow.Result>>(issuerBankParty, topupRequest).unwrap { it }
        }
    }

    @InitiatedBy(TopupIssuanceRequester::class)
    class TopupIssuer(val otherParty: Party) : FlowLogic<List<SignedTransaction>>() {
        companion object {
            object AWAITING_REQUEST : ProgressTracker.Step("Awaiting issuance request")
            object ISSUING : ProgressTracker.Step("Issuing asset")
            object TRANSFERRING : ProgressTracker.Step("Transferring asset to issuance requester")
            object SENDING_TOP_UP_ISSUE_REQUEST : ProgressTracker.Step("Requesting asset issue top up")

            fun tracker() = ProgressTracker(AWAITING_REQUEST, ISSUING, TRANSFERRING, SENDING_TOP_UP_ISSUE_REQUEST)
        }

        override val progressTracker: ProgressTracker = tracker()

        // DOCSTART TopupIssuer
        @Suspendable
        @Throws(CashException::class)
        override fun call(): List<SignedTransaction> {
            progressTracker.currentStep = AWAITING_REQUEST
            val topupRequest = receive<TopupRequest>(otherParty).unwrap {
                it
            }

            val customVaultQueryService = serviceHub.cordaService(CustomVaultQuery.Service::class.java)
            val reserveLimits = customVaultQueryService.rebalanceCurrencyReserves()

            val txns: List<SignedTransaction> = reserveLimits.map { amount ->
                // request asset issue
                logger.info("Requesting currency issue $amount")
                val txn = issueCashTo(amount, topupRequest.issueToParty, topupRequest.issuerPartyRef)
                progressTracker.currentStep = SENDING_TOP_UP_ISSUE_REQUEST
                return@map txn.stx
            }

            send(otherParty, txns)
            return txns
        }
        // DOCEND TopupIssuer

        @Suspendable
        private fun issueCashTo(amount: Amount<Currency>,
                                issueTo: Party,
                                issuerPartyRef: OpaqueBytes): AbstractCashFlow.Result {
            // TODO: pass notary in as request parameter
            val notaryParty = serviceHub.networkMapCache.notaryNodes[0].notaryIdentity
            // invoke Cash subflow to issue Asset
            progressTracker.currentStep = ISSUING
            val issueCashFlow = CashIssueFlow(amount, issuerPartyRef, notaryParty)
            val issueTx = subFlow(issueCashFlow)
            // NOTE: issueCashFlow performs a Broadcast (which stores a local copy of the txn to the ledger)
            // short-circuit when issuing to self
            if (issueTo == serviceHub.myInfo.legalIdentity)
                return issueTx
            // now invoke Cash subflow to Move issued assetType to issue requester
            progressTracker.currentStep = TRANSFERRING
            val moveCashFlow = CashPaymentFlow(amount, issueTo, anonymous = false)
            val moveTx = subFlow(moveCashFlow)
            // NOTE: CashFlow PayCash calls FinalityFlow which performs a Broadcast (which stores a local copy of the txn to the ledger)
            return moveTx
        }
    }
}
