Introduction
============

.. note:: This tutorial extends the CorDapp built during the :doc:`Hello, World tutorial <hello-world-index>`. You can
   download the final version of the CorDapp produced in that tutorial for 
   `Java <https://github.com/joeldudleyr3/tutorials/tree/master/Tut1Java>`_ or 
   `Kotlin <https://github.com/joeldudleyr3/tutorials/tree/master/Tut1Kotlin>`_.

In the Hello, World tutorial, we built a CorDapp allowing us to model IOUs on ledger. Our CorDapp was made up of three
elements:

* An ``IOUState``, representing IOUs on the ledger
* An ``IOUContract``, controlling the evolution of IOUs over time
* An ``IOUFlow``, orchestrating the process of agreeing the creation of an IOU on-ledger

However, in our original CorDapp, only the IOU's lender was required to sign transactions issuing IOUs. The borrower
had no say in whether the issuance of the IOU was a valid ledger update or not.

In this tutorial, we'll update our code so that the lender requires the borrower's agreement before they can issue an
IOU onto the ledger. We'll need to make two changes:

* The ``IOUContract`` will need to be updated so that transactions involving an ``IOUState`` will require the borrower's
  signature (as well as the lender's) to become valid ledger updates
* The ``IOUFlow`` will need to be updated to allow for the gathering of the borrower's signature

We'll start by updating the contract.
