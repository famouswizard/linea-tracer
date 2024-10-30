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

package net.consensys.linea.zktracer.module.hub.fragment.storage;

import static com.google.common.base.Preconditions.*;
import static net.consensys.linea.zktracer.types.AddressUtils.highPart;
import static net.consensys.linea.zktracer.types.AddressUtils.lowPart;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.State;
import net.consensys.linea.zktracer.module.hub.Trace;
import net.consensys.linea.zktracer.module.hub.fragment.DomSubStampsSubFragment;
import net.consensys.linea.zktracer.module.hub.fragment.TraceFragment;
import net.consensys.linea.zktracer.module.hub.fragment.account.AccountFragment;
import net.consensys.linea.zktracer.module.hub.transients.StateManagerMetadata;
import net.consensys.linea.zktracer.types.EWord;
import net.consensys.linea.zktracer.types.TransactionProcessingMetadata;

@RequiredArgsConstructor
@Getter
public final class StorageFragment implements TraceFragment {
  private final State hubState;
  private final State.StorageSlotIdentifier storageSlotIdentifier;
  private final EWord valueOriginal;
  private final EWord valueCurrent;
  private final EWord valueNext;
  private final boolean incomingWarmth;
  private final boolean outgoingWarmth;
  @Getter private final DomSubStampsSubFragment domSubStampsSubFragment;
  private final int blockNumber;
  private final StorageFragmentPurpose purpose; // for debugging purposes

  private final int deploymentNumber;

  final TransactionProcessingMetadata transactionProcessingMetadata;

  public Trace trace(Trace trace) {

    final HashMap<State.StorageSlotIdentifier, State.StorageFragmentPair> current =
        hubState.firstAndLastStorageSlotOccurrences.get(blockNumber - 1);

    boolean match =
        current.keySet().stream()
            .anyMatch(key -> key.getAddress().equals(storageSlotIdentifier.getAddress()));
    boolean containsActualStorageSlotIdentifier = current.containsKey(storageSlotIdentifier);

    // TODO: maybe remove in the future ?
    checkArgument(match);
    checkArgument(containsActualStorageSlotIdentifier);

    final boolean isFirstOccurrence =
        current.get(storageSlotIdentifier).getFirstOccurrence() == this;
    final boolean isFinalOccurrence =
        current.get(storageSlotIdentifier).getFinalOccurrence() == this;

    // tracing
    domSubStampsSubFragment.trace(trace);

    Map<TransactionProcessingMetadata.AddrStorageKeyPair, TransactionProcessingMetadata.FragmentFirstAndLast<StorageFragment>>
        storageFirstAndLastMap = transactionProcessingMetadata.getStorageFirstAndLastMap();
    TransactionProcessingMetadata.AddrStorageKeyPair currentAddressKeyPair = new TransactionProcessingMetadata.AddrStorageKeyPair(storageSlotIdentifier.getAddress(), storageSlotIdentifier.getStorageKey());

    StateManagerMetadata stateManagerMetadata = Hub.stateManagerMetadata();
    Map<StateManagerMetadata.AddrStorageKeyBlockNumTuple,
            TransactionProcessingMetadata.FragmentFirstAndLast<StorageFragment>>
            storageFirstLastBlockMap = stateManagerMetadata.getStorageFirstLastBlockMap();
    StateManagerMetadata.AddrStorageKeyBlockNumTuple storageKeyBlockNumTuple = new StateManagerMetadata.AddrStorageKeyBlockNumTuple(currentAddressKeyPair, this.blockNumber);

    TransactionProcessingMetadata.FragmentFirstAndLast<StorageFragment> storageFirstLastConflationPair = stateManagerMetadata.getStorageFirstLastConflationMap().get(currentAddressKeyPair);

    StateManagerMetadata.AddrBlockPair addressBlockPair = new StateManagerMetadata.AddrBlockPair(storageSlotIdentifier.getAddress(), transactionProcessingMetadata.getRelativeBlockNumber());
    long minDeploymentNumberInBlock = stateManagerMetadata.getMinDeplNoBlock().get(addressBlockPair);
    long maxDeploymentNumberInBlock = stateManagerMetadata.getMaxDeplNoBlock().get(addressBlockPair);

    return trace
        .peekAtStorage(true)
        .pStorageAddressHi(highPart(storageSlotIdentifier.getAddress()))
        .pStorageAddressLo(lowPart(storageSlotIdentifier.getAddress()))
        .pStorageDeploymentNumber(storageSlotIdentifier.getDeploymentNumber())
        .pStorageStorageKeyHi(EWord.of(storageSlotIdentifier.getStorageKey()).hi())
        .pStorageStorageKeyLo(EWord.of(storageSlotIdentifier.getStorageKey()).lo())
        .pStorageValueOrigHi(valueOriginal.hi())
        .pStorageValueOrigLo(valueOriginal.lo())
        .pStorageValueCurrHi(valueCurrent.hi())
        .pStorageValueCurrLo(valueCurrent.lo())
        .pStorageValueNextHi(valueNext.hi())
        .pStorageValueNextLo(valueNext.lo())
        .pStorageWarmth(incomingWarmth)
        .pStorageWarmthNew(outgoingWarmth)
        .pStorageValueOrigIsZero(valueOriginal.isZero())
        .pStorageValueCurrIsOrig(valueCurrent.equals(valueOriginal))
        .pStorageValueCurrIsZero(valueCurrent.isZero())
        .pStorageValueNextIsCurr(valueNext.equals(valueCurrent))
        .pStorageValueNextIsZero(valueNext.isZero())
        .pStorageValueNextIsOrig(valueNext.equals(valueOriginal))
        .pStorageUnconstrainedFirst(isFirstOccurrence)
        .pStorageUnconstrainedFinal(isFinalOccurrence)
        .pStorageFirstInTxn(this == storageFirstAndLastMap.get(currentAddressKeyPair).getFirst())
        .pStorageAgainInTxn(this != storageFirstAndLastMap.get(currentAddressKeyPair).getFirst())
        .pStorageFinalInTxn(this == storageFirstAndLastMap.get(currentAddressKeyPair).getLast())
        .pStorageFirstInBlk(this == storageFirstLastBlockMap.get(storageKeyBlockNumTuple).getFirst())
        .pStorageAgainInBlk(this != storageFirstLastBlockMap.get(storageKeyBlockNumTuple).getFirst())
        .pStorageFinalInBlk(this == storageFirstLastBlockMap.get(storageKeyBlockNumTuple).getLast())
        .pStorageFirstInCnf(this == storageFirstLastConflationPair.getFirst())
        .pStorageAgainInCnf(this != storageFirstLastConflationPair.getFirst())
        .pStorageFinalInCnf(this == storageFirstLastConflationPair.getLast())
        .pStorageDeploymentNumberFirstInBlock(minDeploymentNumberInBlock)
        .pStorageDeploymentNumberFinalInBlock(maxDeploymentNumberInBlock)
        ;
  }
}
