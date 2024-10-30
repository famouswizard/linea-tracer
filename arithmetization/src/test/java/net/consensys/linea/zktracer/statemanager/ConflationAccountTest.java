/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package net.consensys.linea.zktracer.statemanager;

import net.consensys.linea.testing.MultiBlockExecutionEnvironment;
import net.consensys.linea.testing.TransactionProcessingResultValidator;
import net.consensys.linea.zktracer.StateManagerTestValidator;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.fragment.account.AccountFragment;
import net.consensys.linea.zktracer.module.hub.fragment.storage.StorageFragment;
import net.consensys.linea.zktracer.module.hub.transients.StateManagerMetadata;
import net.consensys.linea.zktracer.types.EWord;
import net.consensys.linea.zktracer.types.TransactionProcessingMetadata;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConflationAccountTest {
  TestContext tc;
  @Test
  void testConflationMapAccount() {
    // initialize the test context
    this.tc = new TestContext();
    this.tc.initializeTestContext();
    // prepare the transaction validator
    TransactionProcessingResultValidator resultValidator = new StateManagerTestValidator(
            tc.frameworkEntryPointAccount,
            // Creates and self-destructs generate 2 logs,
            // Transfers generate 3 logs, the 1s are for reverted operations
            List.of(3, 3, 1, 3,
                    2, 3, 3,
                    2, 3, 2, 2, 3,
                    2, 1)
    );
    // fetch the Hub metadata for the state manager maps
    StateManagerMetadata stateManagerMetadata = Hub.stateManagerMetadata();

    // compute the addresses for several accounts that will be deployed later
    tc.newAddresses[0] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000002");
    tc.newAddresses[1] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000003");
    tc.newAddresses[2] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000004");

    // prepare a multi-block execution of transactions
    MultiBlockExecutionEnvironment.builder()
            // initialize accounts
            .accounts(List.of(tc.initialAccounts[0], tc.externallyOwnedAccounts[0], tc.initialAccounts[2], tc.frameworkEntryPointAccount))
            // test account operations for an account prexisting in the state
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.addresses[0], tc.addresses[2], 8L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.addresses[2], tc.addresses[0], 20L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.addresses[0], tc.addresses[2], 50L, true, BigInteger.ONE))) // this action is reverted
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.addresses[0], tc.addresses[2], 10L, false, BigInteger.ONE)))
            // deploy another account ctxt.addresses[3] and perform account operations on it
            .addBlock(List.of(tc.deployWithCreate2(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000002", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.addresses[0], tc.newAddresses[0], 49L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.newAddresses[0], tc.addresses[0], 27L, false, BigInteger.ONE)))
            // deploy another account and self destruct it at the end, redeploy it and change its balance  again
            .addBlock(List.of(tc.deployWithCreate2(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000003", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.addresses[0], tc.newAddresses[1], 98L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.selfDestruct(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.newAddresses[1], tc.addresses[2], false, BigInteger.ONE)))
            .addBlock(List.of(tc.deployWithCreate2(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000003", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.addresses[0], tc.newAddresses[1], 123L, false, BigInteger.ONE)))
            // deploy a new account and check revert operations on it
            .addBlock(List.of(tc.deployWithCreate2(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000004", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.transferTo(tc.externallyOwnedAccounts[0], tc.keyPairs[0], tc.addresses[2], tc.newAddresses[2], 1L, true, BigInteger.ONE)))
            .transactionProcessingResultValidator(resultValidator)
            .build()
            .run();

    Map<Address, TransactionProcessingMetadata. FragmentFirstAndLast<AccountFragment>>
            conflationMap = stateManagerMetadata.getAccountFirstLastConflationMap();

    // prepare data for asserts
    // expected first values for the keys we are testing
    Wei[] expectedFirst = {
            TestContext.defaultBalance,
            TestContext.defaultBalance,
            Wei.of(0L),
            Wei.of(0L),
            Wei.of(0L)
    };
    // expected last values for the keys we are testing
    Wei[] expectedLast = {
            TestContext.defaultBalance.subtract(8L).add(20L).
                    subtract(10L).subtract(49L).add(27L)
                            .subtract(98L).subtract(123L),
            TestContext.defaultBalance.add(8L).subtract(20L).add(10L)
                            .add(98L), // 98L obtained from the self destruct of the account at ctxt.addresses[4]
            Wei.of(0L).add(49L).subtract(27L),
            Wei.of(123L),
            Wei.of(0L)
    };

    // prepare the key pairs
    Address[] keys = {
            tc.initialAccounts[0].getAddress(),
            tc.initialAccounts[2].getAddress(),
            tc.newAddresses[0],
            tc.newAddresses[1],
            tc.newAddresses[2]
    };

    for (int i = 0; i < keys.length; i++) {
      System.out.println("Index is "+i);
      TransactionProcessingMetadata. FragmentFirstAndLast<AccountFragment>
              accountData = conflationMap.get(keys[i]);
      // asserts for the first and last storage values in conflation
      assertEquals(expectedFirst[i], accountData.getFirst().oldState().balance());
      assertEquals(expectedLast[i], accountData.getLast().newState().balance());
    }


    System.out.println("Done");
  }
}
