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
package net.consensys.linea.zktracer.module.hub.section.halt;

import static com.google.common.base.Preconditions.checkArgument;
import static net.consensys.linea.zktracer.module.hub.fragment.scenario.ReturnScenarioFragment.ReturnScenario.*;
import static net.consensys.linea.zktracer.module.hub.signals.Exceptions.OUT_OF_GAS_EXCEPTION;
import static net.consensys.linea.zktracer.module.hub.signals.Exceptions.memoryExpansionException;
import static net.consensys.linea.zktracer.types.Conversions.bytesToBoolean;
import static org.hyperledger.besu.evm.frame.MessageFrame.Type.*;

import lombok.Getter;
import net.consensys.linea.zktracer.module.hub.AccountSnapshot;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.defer.ContextReEntryDefer;
import net.consensys.linea.zktracer.module.hub.defer.PostRollbackDefer;
import net.consensys.linea.zktracer.module.hub.defer.PostTransactionDefer;
import net.consensys.linea.zktracer.module.hub.fragment.ContextFragment;
import net.consensys.linea.zktracer.module.hub.fragment.DomSubStampsSubFragment;
import net.consensys.linea.zktracer.module.hub.fragment.account.AccountFragment;
import net.consensys.linea.zktracer.module.hub.fragment.imc.ImcFragment;
import net.consensys.linea.zktracer.module.hub.fragment.imc.MxpCall;
import net.consensys.linea.zktracer.module.hub.fragment.imc.mmu.MmuCall;
import net.consensys.linea.zktracer.module.hub.fragment.imc.mmu.opcode.ReturnFromDeploymentMmuCall;
import net.consensys.linea.zktracer.module.hub.fragment.imc.oob.OobCall;
import net.consensys.linea.zktracer.module.hub.fragment.imc.oob.opcodes.DeploymentOobCall;
import net.consensys.linea.zktracer.module.hub.fragment.scenario.ReturnScenarioFragment;
import net.consensys.linea.zktracer.module.hub.section.TraceSection;
import net.consensys.linea.zktracer.module.hub.signals.Exceptions;
import net.consensys.linea.zktracer.module.hub.signals.TracedException;
import net.consensys.linea.zktracer.runtime.callstack.CallFrame;
import net.consensys.linea.zktracer.types.Bytecode;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.worldstate.WorldView;

@Getter
public class ReturnSection extends TraceSection
    implements ContextReEntryDefer, PostRollbackDefer, PostTransactionDefer {

  final boolean returnFromMessageCall;
  final boolean returnFromDeployment;
  boolean nonemptyByteCode;
  final ReturnScenarioFragment returnScenarioFragment;
  AccountFragment deploymentFragment;

  AccountSnapshot preDeploymentAccountSnapshot;
  AccountSnapshot postDeploymentAccountSnapshot;
  AccountSnapshot undoingDeploymentAccountSnapshot;
  ContextFragment squashParentContextReturnData;
  Address deploymentAddress;

  boolean successfulMessageCallExpected = false; // for sanity check
  boolean successfulDeploymentExpected = false; // for sanity check

  public ReturnSection(Hub hub) {
    super(hub, maxNumberOfRows(hub));

    final CallFrame callFrame = hub.currentFrame();
    final MessageFrame messageFrame = callFrame.frame();

    returnFromMessageCall = callFrame.isMessageCall();
    returnFromDeployment = callFrame.isDeployment();

    checkArgument(callFrame.isDeployment() == (messageFrame.getType().equals(CONTRACT_CREATION)));

    checkArgument(
        returnFromDeployment
            == hub.transients()
                .conflation()
                .deploymentInfo()
                .getDeploymentStatus(messageFrame.getContractAddress()));

    returnScenarioFragment = new ReturnScenarioFragment();
    final ContextFragment currentContextFragment = ContextFragment.readCurrentContextData(hub);
    final ImcFragment firstImcFragment = ImcFragment.empty(hub);
    final MxpCall mxpCall = new MxpCall(hub);
    firstImcFragment.callMxp(mxpCall);

    this.addStackAndFragments(
        hub, returnScenarioFragment, currentContextFragment, firstImcFragment);

    final short exceptions = hub.pch().exceptions();

    if (Exceptions.any(exceptions)) {
      returnScenarioFragment.setScenario(RETURN_EXCEPTION);
    }

    checkArgument(mxpCall.mxpx == memoryExpansionException(exceptions));

    if (mxpCall.mxpx) {
      commonValues.setTracedException(TracedException.MEMORY_EXPANSION_EXCEPTION);
      return;
    }

    // Note: in case of returnFromMessageCall, we check for outOfGasException.
    // In case of returnFromDeployment, we check for maxCodeSize & invalidCodePrefixException before
    // OOGX.
    if (Exceptions.outOfGasException(exceptions) && returnFromMessageCall) {
      checkArgument(exceptions == OUT_OF_GAS_EXCEPTION);
      commonValues.setTracedException(TracedException.OUT_OF_GAS_EXCEPTION);
      return;
    }

    if (Exceptions.any(exceptions)) {
      checkArgument(returnFromDeployment);
    }

    // maxCodeSizeException case
    final boolean triggerOobForMaxCodeSizeException = Exceptions.maxCodeSizeException(exceptions);
    if (triggerOobForMaxCodeSizeException) {
      final OobCall oobCall = new DeploymentOobCall();
      firstImcFragment.callOob(oobCall);
      commonValues.setTracedException(TracedException.MAX_CODE_SIZE_EXCEPTION);
      return;
    }

    // invalidCodePrefixException case
    final boolean nontrivialMmuOperation = mxpCall.mayTriggerNontrivialMmuOperation;
    final boolean triggerMmuForInvalidCodePrefix = Exceptions.invalidCodePrefix(exceptions);
    if (triggerMmuForInvalidCodePrefix) {
      checkArgument(returnFromDeployment && nontrivialMmuOperation);

      final MmuCall actuallyInvalidCodePrefixMmuCall = MmuCall.invalidCodePrefix(hub);
      firstImcFragment.callMmu(actuallyInvalidCodePrefixMmuCall);

      checkArgument(!actuallyInvalidCodePrefixMmuCall.successBit());
      commonValues.setTracedException(TracedException.INVALID_CODE_PREFIX);
      return;
    }

    // OOGX case
    if (Exceptions.outOfGasException(exceptions) && returnFromDeployment) {
      checkArgument(exceptions == OUT_OF_GAS_EXCEPTION);
      commonValues.setTracedException(TracedException.OUT_OF_GAS_EXCEPTION);
      return;
    }

    // Unexceptional RETURN's
    // (we have exceptions ≡ ∅ by the checkArgument below)
    //////////////////////////////////////////////////////
    checkArgument(Exceptions.none(exceptions));

    // RETURN_FROM_MESSAGE_CALL cases
    if (returnFromMessageCall) {
      successfulMessageCallExpected = true;
      final boolean messageCallReturnTouchesRam =
          !callFrame.isRoot()
              && nontrivialMmuOperation // [size ≠ 0] ∧ ¬MXPX
              && !callFrame.returnDataTargetInCaller().isEmpty(); // [r@c ≠ 0]

      returnScenarioFragment.setScenario(
          messageCallReturnTouchesRam
              ? RETURN_FROM_MESSAGE_CALL_WILL_TOUCH_RAM
              : RETURN_FROM_MESSAGE_CALL_WONT_TOUCH_RAM);

      if (messageCallReturnTouchesRam) {
        final MmuCall returnFromMessageCall = MmuCall.returnFromMessageCall(hub);
        firstImcFragment.callMmu(returnFromMessageCall);
      }

      final ContextFragment updateCallerReturnData =
          ContextFragment.executionProvidesReturnData(
              hub,
              hub.callStack().getById(callFrame.callerId()).contextNumber(),
              callFrame.contextNumber());
      this.addFragment(updateCallerReturnData);

      return;
    }

    // RETURN_FROM_DEPLOYMENT cases
    if (returnFromDeployment) {
      successfulDeploymentExpected = true;

      // TODO: @Olivier and @François: what happens when "re-entering" the root's parent context ?
      //  we may need to improve the triggering of the resolution to also kick in at transaction
      //  end for stuff that happens after the root returns ...
      hub.defers()
          .scheduleForContextReEntry(
              this, hub.callStack().parent()); // post deployment account snapshot
      hub.defers().scheduleForPostRollback(this, callFrame); // undo deployment
      hub.defers().scheduleForPostTransaction(this); // inserting the final context row;

      squashParentContextReturnData = ContextFragment.executionProvidesEmptyReturnData(hub);
      deploymentAddress = messageFrame.getRecipientAddress();
      nonemptyByteCode = mxpCall.mayTriggerNontrivialMmuOperation;
      preDeploymentAccountSnapshot = AccountSnapshot.canonical(hub, deploymentAddress);
      returnScenarioFragment.setScenario(
          nonemptyByteCode
              ? RETURN_FROM_DEPLOYMENT_NONEMPTY_CODE_WONT_REVERT
              : RETURN_FROM_DEPLOYMENT_EMPTY_CODE_WONT_REVERT);

      final Bytes byteCodeSize = messageFrame.getStackItem(1);
      checkArgument(nonemptyByteCode == (!byteCodeSize.isZero()));

      // Empty deployments
      if (!nonemptyByteCode) {
        if (hub.messageFrame().getDepth() == 0) {
          this.addDeploymentAccountFragmentIfRoot(hub, mxpCall);
        }
        return;
      }

      hub.romLex().callRomLex(messageFrame);

      final MmuCall invalidCodePrefixCheckMmuCall = MmuCall.invalidCodePrefix(hub);
      firstImcFragment.callMmu(invalidCodePrefixCheckMmuCall);

      final DeploymentOobCall maxCodeSizeOobCall = new DeploymentOobCall();
      firstImcFragment.callOob(maxCodeSizeOobCall);

      // sanity checks
      checkArgument(invalidCodePrefixCheckMmuCall.successBit());
      checkArgument(!maxCodeSizeOobCall.isMaxCodeSizeException());

      final ImcFragment secondImcFragment = ImcFragment.empty(hub);
      this.addFragment(secondImcFragment);

      final ReturnFromDeploymentMmuCall nonemptyDeploymentMmuCall =
          MmuCall.returnFromDeployment(hub);
      secondImcFragment.callMmu(nonemptyDeploymentMmuCall);

      triggerHashInfo(nonemptyDeploymentMmuCall.hashResult());

      if (hub.messageFrame().getDepth() == 0) {
        this.addDeploymentAccountFragmentIfRoot(hub, mxpCall);
      }
    }
  }

  @Override
  public void resolveAtContextReEntry(Hub hub, CallFrame frame) {

    // TODO: optional sanity check that may be removed
    if (returnFromMessageCall) {
      final Bytes topOfTheStack = hub.messageFrame().getStackItem(0);
      boolean messageCallWasSuccessful = bytesToBoolean(topOfTheStack);
      checkArgument(messageCallWasSuccessful == successfulMessageCallExpected);
    }

    // TODO: optional sanity check that may be removed
    if (returnFromDeployment) {
      final Bytes topOfTheStack = hub.messageFrame().getStackItem(0);
      boolean deploymentWasSuccess = !topOfTheStack.isZero();
      checkArgument(deploymentWasSuccess == successfulDeploymentExpected);
    }

    postDeploymentAccountSnapshot = AccountSnapshot.canonical(hub, deploymentAddress);
    final AccountFragment deploymentAccountFragment =
        hub.factories()
            .accountFragment()
            .make(
                preDeploymentAccountSnapshot,
                postDeploymentAccountSnapshot,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 0));

    if (nonemptyByteCode) {
      deploymentAccountFragment.requiresRomlex(true);
    }

    this.addFragment(deploymentAccountFragment);
  }

  @Override
  public void resolvePostRollback(Hub hub, MessageFrame messageFrame, CallFrame callFrame) {

    checkArgument(returnFromDeployment);
    returnScenarioFragment.setScenario(
        nonemptyByteCode
            ? RETURN_FROM_DEPLOYMENT_NONEMPTY_CODE_WILL_REVERT
            : RETURN_FROM_DEPLOYMENT_EMPTY_CODE_WILL_REVERT);

    undoingDeploymentAccountSnapshot = AccountSnapshot.canonical(hub, deploymentAddress);

    // TODO: does this account for updates to
    //  - deploymentNumber and status ?
    //  - MARKED_FOR_SELF_DESTRUCT(_NEW) ?
    final AccountFragment undoingDeploymentAccountFragment =
        hub.factories()
            .accountFragment()
            .make(
                postDeploymentAccountSnapshot,
                undoingDeploymentAccountSnapshot,
                DomSubStampsSubFragment.revertWithCurrentDomSubStamps(
                    this.hubStamp(), hub.callStack().currentCallFrame().revertStamp(), 1));

    this.addFragment(undoingDeploymentAccountFragment);
  }

  @Override
  public void resolvePostTransaction(
      Hub hub, WorldView state, Transaction tx, boolean isSuccessful) {

    checkArgument(returnFromDeployment);
    this.addFragment(squashParentContextReturnData);
  }

  private void addDeploymentAccountFragmentIfRoot(Hub hub, MxpCall mxpCall) {
    // in case of zero depth we don't have a ContextReEntry step so we have to add the
    // deployment account fragment manually
    postDeploymentAccountSnapshot = AccountSnapshot.canonical(hub, deploymentAddress);
    postDeploymentAccountSnapshot.code(
        new Bytecode(
            hub.messageFrame()
                .shadowReadMemory(
                    Words.clampedToLong(mxpCall.offset1), Words.clampedToLong(mxpCall.size1))));
    postDeploymentAccountSnapshot.deploymentStatus(false);

    final AccountFragment deploymentAccountFragment =
        hub.factories()
            .accountFragment()
            .make(
                preDeploymentAccountSnapshot,
                postDeploymentAccountSnapshot,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 0));

    this.addFragment(deploymentAccountFragment);
  }

  private static short maxNumberOfRows(Hub hub) {
    return (short)
        (hub.opCode().numberOfStackRows() + (Exceptions.any(hub.pch().exceptions()) ? 4 : 7));
  }
}
