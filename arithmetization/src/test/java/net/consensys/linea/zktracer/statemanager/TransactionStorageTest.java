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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import net.consensys.linea.testing.MultiBlockExecutionEnvironment;
import net.consensys.linea.testing.TransactionProcessingResultValidator;
import net.consensys.linea.testing.generated.FrameworkEntrypoint;
import net.consensys.linea.zktracer.StateManagerTestValidator;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.fragment.storage.StorageFragment;
import net.consensys.linea.zktracer.module.hub.transients.StateManagerMetadata;
import net.consensys.linea.zktracer.types.EWord;
import net.consensys.linea.zktracer.types.TransactionProcessingMetadata;
import org.junit.jupiter.api.Test;

public class TransactionStorageTest {
  TestContext tc;

  @Test
  void testTransactionMapStorage() {
    // initialize the test context
    this.tc = new TestContext();
    this.tc.initializeTestContext();
    // prepare the transaction validator
    TransactionProcessingResultValidator resultValidator =
        new StateManagerTestValidator(
            tc.frameworkEntryPointAccount,
            // Creates, writes, reads and self-destructs generate 2 logs,
            // Reverted operations only have 1 log
            List.of(6, 6));
    // fetch the Hub metadata for the state manager maps
    StateManagerMetadata stateManagerMetadata = Hub.stateManagerMetadata();

    // prepare a multi-block execution of transactions
    MultiBlockExecutionEnvironment.builder()
        // initialize accounts
        .accounts(
            List.of(
                tc.initialAccounts[0],
                tc.externallyOwnedAccounts[0],
                tc.initialAccounts[2],
                tc.frameworkEntryPointAccount))
        // Block 1
        .addBlock(
            List.of(
                tc.wrapWrite(
                    tc.externallyOwnedAccounts[0],
                    tc.keyPairs[0],
                    new FrameworkEntrypoint.ContractCall[] {
                      tc.writeToStorageCall(tc.addresses[0], 3L, 1L, false, BigInteger.ONE),
                      tc.writeToStorageCall(tc.addresses[0], 3L, 2L, false, BigInteger.ONE),
                      tc.writeToStorageCall(tc.addresses[0], 3L, 3L, false, BigInteger.ONE),
                    }),
                tc.wrapWrite(
                    tc.externallyOwnedAccounts[0],
                    tc.keyPairs[0],
                    new FrameworkEntrypoint.ContractCall[] {
                      tc.writeToStorageCall(tc.addresses[0], 3L, 4L, false, BigInteger.ONE),
                      tc.writeToStorageCall(tc.addresses[0], 3L, 5L, false, BigInteger.ONE),
                      tc.writeToStorageCall(tc.addresses[0], 3L, 6L, false, BigInteger.ONE),
                    })))
        .transactionProcessingResultValidator(resultValidator)
        .build()
        .run();

    List<TransactionProcessingMetadata> txn =
        Hub.stateManagerMetadata().getHub().txStack().getTransactions();
    ;

    // prepare data for asserts
    // expected first values for the keys we are testing
    int noBlocks = 3;
    EWord[][] expectedFirst = {
      {
        EWord.of(0L),
      },
      {
        EWord.of(3L),
      },
    };
    // expected last values for the keys we are testing
    EWord[][] expectedLast = {
      {
        EWord.of(3L),
      },
      {
        EWord.of(6L),
      },
    };
    // prepare the key pairs
    TransactionProcessingMetadata.AddrStorageKeyPair[] keys = {
      new TransactionProcessingMetadata.AddrStorageKeyPair(
          tc.initialAccounts[0].getAddress(), EWord.of(3L)),
    };

    // blocks are numbered starting from 1
    for (int txCounter = 1; txCounter <= txn.size(); txCounter++) {
      Map<
              TransactionProcessingMetadata.AddrStorageKeyPair,
              TransactionProcessingMetadata.FragmentFirstAndLast<StorageFragment>>
          storageMap = txn.get(txCounter - 1).getStorageFirstAndLastMap();
      for (int i = 0; i < keys.length; i++) {
        TransactionProcessingMetadata.FragmentFirstAndLast<StorageFragment> storageData =
            storageMap.get(keys[i]);
        // asserts for the first and last storage values in conflation
        // -1 due to block numbering
        assertEquals(expectedFirst[txCounter - 1][i], storageData.getFirst().getValueCurrent());
        assertEquals(expectedLast[txCounter - 1][i], storageData.getLast().getValueNext());
      }
    }

    System.out.println("Done");
  }
}
