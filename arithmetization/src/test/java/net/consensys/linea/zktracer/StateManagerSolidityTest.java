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

package net.consensys.linea.zktracer;

import lombok.Getter;
import lombok.NoArgsConstructor;
import net.consensys.linea.testing.*;
import net.consensys.linea.testing.generated.FrameworkEntrypoint;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.fragment.account.AccountFragment;
import net.consensys.linea.zktracer.module.hub.fragment.storage.StorageFragment;
import net.consensys.linea.zktracer.module.hub.transients.StateManagerMetadata;
import net.consensys.linea.zktracer.types.EWord;
import net.consensys.linea.zktracer.types.TransactionProcessingMetadata;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.*;

public class StateManagerSolidityTest {
  TestContext tc;
  @Test
  void testBuildingBlockOperations() {
    // initialize the test context
    this.tc = new TestContext();
    this.tc.initializeTestContext();
    TransactionProcessingResultValidator resultValidator = new StateManagerTestValidator(
            tc.frameworkEntryPointAccount,
            // Creates and self-destructs generate 2 logs,
            // Transfers generate 3 logs, the 1s are for reverted operations
            List.of(2, 2, 3, 2, 2)
    );

    MultiBlockExecutionEnvironment.builder()
            .accounts(List.of(tc.initialAccounts[0], tc.initialAccounts[1], tc.initialAccounts[2], tc.frameworkEntryPointAccount))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], 123L, 1L, false, BigInteger.ZERO)))
            .addBlock(List.of(tc.readFromStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], 123L, false, BigInteger.ZERO)))
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], tc.addresses[2], 8L, false, BigInteger.ONE)))
             // test operations above, before self-destructing a snippet in the next line
            .addBlock(List.of(tc.selfDestruct(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], tc.frameworkEntryPointAddress, false, BigInteger.ONE))) // use BigInteger.ONE, otherwise the framework entry point gets destroyed
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000002", TestContext.snippetsCodeForCreate2)))
            .transactionProcessingResultValidator(resultValidator)
            .build()
            .run();
    System.out.println("Done");
  }

  // Create 2 has a weird behavior and does not seem to work with the
  // bytecode output by the function SmartContractUtils.getYulContractByteCode("StateManagerSnippets.yul")
  @Test
  void testCreate2Snippets() {
// initialize the test context
    this.tc = new TestContext();
    this.tc.initializeTestContext();
    TransactionProcessingResultValidator resultValidator = new StateManagerTestValidator(
            tc.frameworkEntryPointAccount,
            List.of(2)
    );
    MultiBlockExecutionEnvironment.builder()
            .accounts(List.of(tc.initialAccounts[0], tc.initialAccounts[1], tc.frameworkEntryPointAccount))
            .addBlock(List.of(
                    tc.deployWithCreate2(tc.initialAccounts[1],
                                      tc.initialKeyPairs[1],
                                      tc.frameworkEntryPointAddress,
                              "0x0000000000000000000000000000000000000000000000000000000000000002",
                                      TestContext.snippetsCodeForCreate2)))
            .transactionProcessingResultValidator(resultValidator)
            .build()
            .run();
    System.out.println("Done");
  }



  @Test
  void testConflationMapStorage() {
    // initialize the test context
    this.tc = new TestContext();
    this.tc.initializeTestContext();
    // prepare the transaction validator
    TransactionProcessingResultValidator resultValidator = new StateManagerTestValidator(
            tc.frameworkEntryPointAccount,
            // Creates, writes, reads and self-destructs generate 2 logs,
            // Reverted operations only have 1 log
            List.of(2, 2, 2, 2, 2,
                    2, 2, 2, 2,
                    2, 2, 2, 2, 2, 2, 2,
                    2, 2, 1, 1)
    );
    // fetch the Hub metadata for the state manager maps
    StateManagerMetadata stateManagerMetadata = Hub.stateManagerMetadata();
    // compute the addresses for several accounts that will be deployed later
    tc.addresses[3] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000002");
    tc.addresses[4] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000003");
    tc.addresses[5] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000004");

    // prepare a multi-block execution of transactions
    MultiBlockExecutionEnvironment.builder()
            // initialize accounts
            .accounts(List.of(tc.initialAccounts[0], tc.initialAccounts[1], tc.initialAccounts[2], tc.frameworkEntryPointAccount))
            // test storage operations for an account prexisting in the state
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], 123L, 8L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.readFromStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], 123L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], 123L, 10L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.readFromStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], 123L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], 123L, 15L, false, BigInteger.ONE)))
            // deploy another account and perform storage operations on it
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000002", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[3], 345L, 20L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.readFromStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[3], 345L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[3], 345L, 40L, false, BigInteger.ONE)))
            // deploy another account and self destruct it at the end, redeploy it and change the storage again
            // the salt will be the same twice in a row, which will be on purpose
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000003", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[4], 400L, 12L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.readFromStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[4], 400L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[4], 400L, 13L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.selfDestruct(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[4], tc.frameworkEntryPointAddress, false, BigInteger.ONE)))
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000003", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[4], 400L, 99L, false, BigInteger.ONE)))
            // deploy a new account and check revert operations on it
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000004", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[5], 500L, 23L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[5], 500L, 53L, true, BigInteger.ONE))) // revert flag on
            .addBlock(List.of(tc.writeToStorage(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[5], 500L, 63L, true, BigInteger.ONE))) // revert flag on
            .transactionProcessingResultValidator(resultValidator)
            .build()
            .run();

    Map<Address, TransactionProcessingMetadata. FragmentFirstAndLast<AccountFragment>>
            conflationMap = stateManagerMetadata.getAccountFirstLastConflationMap();
    Map<TransactionProcessingMetadata. AddrStorageKeyPair, TransactionProcessingMetadata. FragmentFirstAndLast<StorageFragment>>
            conflationStorage = stateManagerMetadata.getStorageFirstLastConflationMap();

    // prepare data for asserts
    // expected first values for the keys we are testing
    EWord[] expectedFirst = {
            EWord.of(8L),
            EWord.of(20L),
            EWord.of(12L),
            EWord.of(23L)
    };
    // expected last values for the keys we are testing
    EWord[] expectedLast = {
            EWord.of(15L),
            EWord.of(40L),
            EWord.of(99L),
            EWord.of(23L)
    };
    // prepare the key pairs
    TransactionProcessingMetadata.AddrStorageKeyPair[] keys = {
            new TransactionProcessingMetadata.AddrStorageKeyPair(tc.initialAccounts[0].getAddress(), EWord.of(123L)),
            new TransactionProcessingMetadata.AddrStorageKeyPair(tc.addresses[3], EWord.of(345L)),
            new TransactionProcessingMetadata.AddrStorageKeyPair(tc.addresses[4], EWord.of(400L)),
            new TransactionProcessingMetadata.AddrStorageKeyPair(tc.addresses[5], EWord.of(500L))
    };

    for (int i = 0; i < keys.length; i++) {
      TransactionProcessingMetadata. FragmentFirstAndLast<StorageFragment>
              storageData = conflationStorage.get(keys[i]);
      // asserts for the first and last storage values in conflation
      assertEquals(storageData.getFirst().getValueNext(), expectedFirst[i]);
      assertEquals(storageData.getLast().getValueNext(), expectedLast[i]);
    }
    System.out.println("Done");
  }


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
    tc.addresses[3] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000002");
    tc.addresses[4] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000003");
    tc.addresses[5] = tc.getCreate2AddressForSnippet("0x0000000000000000000000000000000000000000000000000000000000000004");

    // prepare a multi-block execution of transactions
    MultiBlockExecutionEnvironment.builder()
            // initialize accounts
            .accounts(List.of(tc.initialAccounts[0], tc.initialAccounts[1], tc.initialAccounts[2], tc.frameworkEntryPointAccount))
            // test account operations for an account prexisting in the state
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], tc.addresses[2], 8L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[2], tc.addresses[0], 20L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], tc.addresses[2], 50L, true, BigInteger.ONE))) // this action is reverted
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], tc.addresses[2], 10L, false, BigInteger.ONE)))
            // deploy another account ctxt.addresses[3] and perform account operations on it
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000002", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], tc.addresses[3], 49L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[3], tc.addresses[0], 27L, false, BigInteger.ONE)))
            // deploy another account and self destruct it at the end, redeploy it and change its balance  again
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000003", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], tc.addresses[4], 98L, false, BigInteger.ONE)))
            .addBlock(List.of(tc.selfDestruct(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[4], tc.addresses[2], false, BigInteger.ONE)))
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000003", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[0], tc.addresses[4], 123L, false, BigInteger.ONE)))
            // deploy a new account and check revert operations on it
            .addBlock(List.of(tc.deployWithCreate2(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.frameworkEntryPointAddress, "0x0000000000000000000000000000000000000000000000000000000000000004", TestContext.snippetsCodeForCreate2)))
            .addBlock(List.of(tc.transferTo(tc.initialAccounts[1], tc.initialKeyPairs[1], tc.addresses[2], tc.addresses[5], 1L, true, BigInteger.ONE)))
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
            Wei.of(123L)
    };

    // prepare the key pairs
    Address[] keys = {
            tc.initialAccounts[0].getAddress(),
            tc.initialAccounts[2].getAddress(),
            tc.addresses[3],
            tc.addresses[4]
    };

    for (int i = 0; i < keys.length; i++) {
      System.out.println("Index is "+i);
      TransactionProcessingMetadata. FragmentFirstAndLast<AccountFragment>
              accountData = conflationMap.get(keys[i]);
      // asserts for the first and last storage values in conflation
      assertEquals(accountData.getFirst().oldState().balance(), expectedFirst[i]);
      assertEquals(accountData.getLast().newState().balance(), expectedLast[i]);
    }


    System.out.println("Done");
  }



}

/*
    TransactionProcessingResultValidator resultValidator =
            (Transaction transaction, TransactionProcessingResult result) -> {
              // One event from the snippet
              // One event from the framework entrypoint about contract call
              //assertEquals(result.getLogs().size(), 1);
              System.out.println("Number of logs: "+result.getLogs().size());
              var noTopics = result.getLogs().size();
              for (Log log : result.getLogs()) {
                String logTopic = log.getTopics().getFirst().toHexString();
                String callEventSignature = EventEncoder.encode(FrameworkEntrypoint.CALLEXECUTED_EVENT);
                String writeEventSignature = EventEncoder.encode(FrameworkEntrypoint.WRITE_EVENT);
                String readEventSignature = EventEncoder.encode(FrameworkEntrypoint.READ_EVENT);
                String destructEventSignature = EventEncoder.encode(FrameworkEntrypoint.CONTRACTDESTROYED_EVENT);
                if (callEventSignature.equals(logTopic)) {
                  FrameworkEntrypoint.CallExecutedEventResponse response =
                          FrameworkEntrypoint.getCallExecutedEventFromLog(Web3jUtils.fromBesuLog(log));
                  assertTrue(response.isSuccess);
                  assertEquals(response.destination, this.testContext.initialAccounts[0].getAddress().toHexString());
                  continue;
                }
                if (writeEventSignature.equals(logTopic)) {
                  // write event
                  continue;
                }
                if (readEventSignature.equals(logTopic)) {
                  // read event
                  continue;
                }
                if (destructEventSignature.equals(logTopic)) {
                  // self destruct
                  continue;
                }
                fail();
              }
            };
     */