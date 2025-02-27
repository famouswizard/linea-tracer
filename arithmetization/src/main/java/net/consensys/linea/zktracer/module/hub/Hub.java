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

package net.consensys.linea.zktracer.module.hub;

import static com.google.common.base.Preconditions.*;
import static net.consensys.linea.zktracer.module.hub.HubProcessingPhase.TX_EXEC;
import static net.consensys.linea.zktracer.module.hub.HubProcessingPhase.TX_FINL;
import static net.consensys.linea.zktracer.module.hub.HubProcessingPhase.TX_INIT;
import static net.consensys.linea.zktracer.module.hub.HubProcessingPhase.TX_SKIP;
import static net.consensys.linea.zktracer.module.hub.HubProcessingPhase.TX_WARM;
import static net.consensys.linea.zktracer.module.hub.Trace.MULTIPLIER___STACK_STAMP;
import static net.consensys.linea.zktracer.module.hub.signals.TracedException.*;
import static net.consensys.linea.zktracer.opcode.OpCode.RETURN;
import static net.consensys.linea.zktracer.opcode.OpCode.REVERT;
import static net.consensys.linea.zktracer.types.AddressUtils.effectiveToAddress;
import static org.hyperledger.besu.evm.frame.MessageFrame.Type.*;

import java.math.BigInteger;
import java.nio.MappedByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.consensys.linea.zktracer.ColumnHeader;
import net.consensys.linea.zktracer.container.module.Module;
import net.consensys.linea.zktracer.module.add.Add;
import net.consensys.linea.zktracer.module.bin.Bin;
import net.consensys.linea.zktracer.module.blake2fmodexpdata.BlakeModexpData;
import net.consensys.linea.zktracer.module.blockdata.Blockdata;
import net.consensys.linea.zktracer.module.blockhash.Blockhash;
import net.consensys.linea.zktracer.module.ecdata.EcData;
import net.consensys.linea.zktracer.module.euc.Euc;
import net.consensys.linea.zktracer.module.exp.Exp;
import net.consensys.linea.zktracer.module.ext.Ext;
import net.consensys.linea.zktracer.module.gas.Gas;
import net.consensys.linea.zktracer.module.hub.defer.DeferRegistry;
import net.consensys.linea.zktracer.module.hub.fragment.ContextFragment;
import net.consensys.linea.zktracer.module.hub.fragment.StackFragment;
import net.consensys.linea.zktracer.module.hub.section.*;
import net.consensys.linea.zktracer.module.hub.section.call.CallSection;
import net.consensys.linea.zktracer.module.hub.section.copy.CallDataCopySection;
import net.consensys.linea.zktracer.module.hub.section.copy.CodeCopySection;
import net.consensys.linea.zktracer.module.hub.section.copy.ExtCodeCopySection;
import net.consensys.linea.zktracer.module.hub.section.copy.ReturnDataCopySection;
import net.consensys.linea.zktracer.module.hub.section.halt.ReturnSection;
import net.consensys.linea.zktracer.module.hub.section.halt.RevertSection;
import net.consensys.linea.zktracer.module.hub.section.halt.SelfdestructSection;
import net.consensys.linea.zktracer.module.hub.section.halt.StopSection;
import net.consensys.linea.zktracer.module.hub.signals.Exceptions;
import net.consensys.linea.zktracer.module.hub.signals.PlatformController;
import net.consensys.linea.zktracer.module.hub.transients.Transients;
import net.consensys.linea.zktracer.module.limits.Keccak;
import net.consensys.linea.zktracer.module.limits.L2Block;
import net.consensys.linea.zktracer.module.limits.L2L1Logs;
import net.consensys.linea.zktracer.module.limits.precompiles.BlakeEffectiveCall;
import net.consensys.linea.zktracer.module.limits.precompiles.BlakeRounds;
import net.consensys.linea.zktracer.module.limits.precompiles.EcAddEffectiveCall;
import net.consensys.linea.zktracer.module.limits.precompiles.EcMulEffectiveCall;
import net.consensys.linea.zktracer.module.limits.precompiles.EcPairingFinalExponentiations;
import net.consensys.linea.zktracer.module.limits.precompiles.EcPairingG2MembershipCalls;
import net.consensys.linea.zktracer.module.limits.precompiles.EcPairingMillerLoops;
import net.consensys.linea.zktracer.module.limits.precompiles.EcRecoverEffectiveCall;
import net.consensys.linea.zktracer.module.limits.precompiles.ModexpEffectiveCall;
import net.consensys.linea.zktracer.module.limits.precompiles.RipemdBlocks;
import net.consensys.linea.zktracer.module.limits.precompiles.Sha256Blocks;
import net.consensys.linea.zktracer.module.logdata.LogData;
import net.consensys.linea.zktracer.module.loginfo.LogInfo;
import net.consensys.linea.zktracer.module.mmio.Mmio;
import net.consensys.linea.zktracer.module.mmu.Mmu;
import net.consensys.linea.zktracer.module.mod.Mod;
import net.consensys.linea.zktracer.module.mul.Mul;
import net.consensys.linea.zktracer.module.mxp.Mxp;
import net.consensys.linea.zktracer.module.oob.Oob;
import net.consensys.linea.zktracer.module.rlpaddr.RlpAddr;
import net.consensys.linea.zktracer.module.rlptxn.RlpTxn;
import net.consensys.linea.zktracer.module.rlptxrcpt.RlpTxnRcpt;
import net.consensys.linea.zktracer.module.rom.Rom;
import net.consensys.linea.zktracer.module.romlex.ContractMetadata;
import net.consensys.linea.zktracer.module.romlex.RomLex;
import net.consensys.linea.zktracer.module.shakiradata.ShakiraData;
import net.consensys.linea.zktracer.module.shf.Shf;
import net.consensys.linea.zktracer.module.stp.Stp;
import net.consensys.linea.zktracer.module.tables.bin.BinRt;
import net.consensys.linea.zktracer.module.tables.instructionDecoder.InstructionDecoder;
import net.consensys.linea.zktracer.module.tables.shf.ShfRt;
import net.consensys.linea.zktracer.module.trm.Trm;
import net.consensys.linea.zktracer.module.txndata.TxnData;
import net.consensys.linea.zktracer.module.wcp.Wcp;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.opcode.OpCodeData;
import net.consensys.linea.zktracer.opcode.gas.projector.GasProjector;
import net.consensys.linea.zktracer.runtime.callstack.CallFrame;
import net.consensys.linea.zktracer.runtime.callstack.CallFrameType;
import net.consensys.linea.zktracer.runtime.callstack.CallStack;
import net.consensys.linea.zktracer.runtime.stack.StackContext;
import net.consensys.linea.zktracer.runtime.stack.StackLine;
import net.consensys.linea.zktracer.types.Bytecode;
import net.consensys.linea.zktracer.types.MemorySpan;
import net.consensys.linea.zktracer.types.TransactionProcessingMetadata;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.AccountState;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.worldstate.WorldView;
import org.hyperledger.besu.plugin.data.BlockBody;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;

@Slf4j
@Accessors(fluent = true)
public class Hub implements Module {

  public static final GasProjector GAS_PROJECTOR = new GasProjector();

  /** accumulate the trace information for the Hub */
  @Getter public final State state = new State();

  /** contain the factories for trace segments that need complex initialization */
  @Getter private final Factories factories = new Factories(this);

  /** provides phase-related volatile information */
  @Getter Transients transients = new Transients(this);

  /**
   * Long-lived states, not used in tracing per se but keeping track of data of the associated
   * lifetime
   */
  @Getter CallStack callStack = new CallStack();

  /** Stores the transaction Metadata of all the transaction of the conflated block */
  @Getter TransactionStack txStack = new TransactionStack();

  /** Stores all the actions that must be deferred to a later time */
  @Getter private final DeferRegistry defers = new DeferRegistry();

  /** stores all data related to failure states & module activation */
  @Getter private final PlatformController pch = new PlatformController(this);

  @Override
  public String moduleKey() {
    return "HUB";
  }

  @Override
  public List<ColumnHeader> columnsHeaders() {
    return Trace.headers(this.lineCount());
  }

  @Override
  public void commit(List<MappedByteBuffer> buffers) {
    final Trace trace = new Trace(buffers);
    state.commit(trace);
  }

  @Override
  public int lineCount() {
    return state.lineCounter().lineCount();
  }

  @Getter private final BigInteger chainId;

  /** List of all modules of the ZK-evm */
  // stateless modules
  @Getter private final Wcp wcp = new Wcp();

  private final Add add = new Add();
  private final Bin bin = new Bin();
  private final Blockhash blockhash = new Blockhash(this, wcp);
  private final Euc euc = new Euc(wcp);
  @Getter private final Ext ext = new Ext(this);
  @Getter private final Gas gas = new Gas(wcp);
  private final Mul mul = new Mul(this);
  private final Mod mod = new Mod();
  private final Shf shf = new Shf();
  @Getter private final Trm trm = new Trm();

  // other
  private final Blockdata blockdata;
  @Getter private final RomLex romLex = new RomLex(this);
  private final Rom rom = new Rom(romLex);
  private final RlpTxn rlpTxn = new RlpTxn(romLex);
  private final Mmio mmio;

  private final TxnData txnData = new TxnData(wcp, euc);
  private final RlpTxnRcpt rlpTxnRcpt = new RlpTxnRcpt();
  private final LogInfo logInfo = new LogInfo(rlpTxnRcpt);
  private final LogData logData = new LogData(rlpTxnRcpt);
  @Getter private final RlpAddr rlpAddr = new RlpAddr(this, trm);

  // modules triggered by sub-fragments of the MISCELLANEOUS / IMC perspective
  @Getter private final Mxp mxp = new Mxp();
  @Getter private final Oob oob = new Oob(this, add, mod, wcp);
  @Getter private final Mmu mmu;
  @Getter private final Stp stp = new Stp(wcp, mod);
  @Getter private final Exp exp = new Exp(this, wcp);

  /*
   * Those modules are not traced, we just compute the number of calls to those
   * precompile to meet the prover limits
   */
  private final Keccak keccak;

  private final Sha256Blocks sha256Blocks = new Sha256Blocks();

  private final EcAddEffectiveCall ecAddEffectiveCall = new EcAddEffectiveCall();
  private final EcMulEffectiveCall ecMulEffectiveCall = new EcMulEffectiveCall();
  private final EcRecoverEffectiveCall ecRecoverEffectiveCall = new EcRecoverEffectiveCall();

  private final EcPairingG2MembershipCalls ecPairingG2MembershipCalls =
      new EcPairingG2MembershipCalls();
  private final EcPairingMillerLoops ecPairingMillerLoops = new EcPairingMillerLoops();
  private final EcPairingFinalExponentiations ecPairingFinalExponentiations =
      new EcPairingFinalExponentiations();

  @Getter private final ModexpEffectiveCall modexpEffectiveCall = new ModexpEffectiveCall();

  private final RipemdBlocks ripemdBlocks = new RipemdBlocks();

  private final BlakeEffectiveCall blakeEffectiveCall = new BlakeEffectiveCall();
  private final BlakeRounds blakeRounds = new BlakeRounds();

  private List<Module> precompileLimitModules() {

    return List.of(
        keccak,
        sha256Blocks,
        ecAddEffectiveCall,
        ecMulEffectiveCall,
        ecRecoverEffectiveCall,
        ecPairingG2MembershipCalls,
        ecPairingMillerLoops,
        ecPairingFinalExponentiations,
        modexpEffectiveCall,
        ripemdBlocks,
        blakeEffectiveCall,
        blakeRounds);
  }

  /*
   * precompile-data modules
   * those module are traced (and could be count)
   */
  @Getter private final ShakiraData shakiraData;

  @Getter
  private final BlakeModexpData blakeModexpData =
      new BlakeModexpData(wcp, modexpEffectiveCall, blakeEffectiveCall, blakeRounds);

  @Getter
  public final EcData ecData =
      new EcData(
          wcp,
          ext,
          ecAddEffectiveCall,
          ecMulEffectiveCall,
          ecRecoverEffectiveCall,
          ecPairingG2MembershipCalls,
          ecPairingMillerLoops,
          ecPairingFinalExponentiations);

  private final L2Block l2Block;
  private final L2L1Logs l2L1Logs;

  /** list of module than can be modified during execution */
  private final List<Module> modules;

  /** reference table modules */
  private final List<Module> refTableModules;

  /**
   * boolean which remembers whether a {@link CreateSection} detected Failure Condition F. Gets
   * reset with every new opcode.
   */
  public boolean failureConditionForCreates = false;

  /**
   * @return a list of all modules for which to generate traces
   */
  public List<Module> getModulesToTrace() {
    return Stream.concat(
            Stream.of(
                this,
                add,
                bin,
                blakeModexpData,
                blockdata,
                blockhash,
                ecData,
                exp,
                ext,
                euc,
                gas,
                logData,
                logInfo,
                mmu, // WARN: must be traced before the MMIO
                mmio,
                mod,
                mul,
                mxp,
                oob,
                rlpAddr,
                rlpTxn,
                rlpTxnRcpt,
                rom,
                romLex,
                shakiraData,
                shf,
                stp,
                trm,
                txnData,
                wcp),
            refTableModules.stream())
        .toList();
  }

  /**
   * List all the modules for which to generate counters. Intersects with, but is not equal to
   * {@code getModulesToTrace}.
   *
   * @return the modules to count
   */
  public List<Module> getModulesToCount() {
    return Stream.concat(
            Stream.of(
                this,
                add,
                bin,
                blakeModexpData,
                blockdata,
                blockhash,
                ecData,
                exp,
                ext,
                euc,
                gas,
                logData,
                logInfo,
                mmu,
                mmio,
                mod,
                mul,
                mxp,
                oob,
                rlpAddr,
                rlpTxn,
                rlpTxnRcpt,
                rom,
                romLex,
                shakiraData,
                shf,
                stp,
                trm,
                txnData,
                wcp,
                l2Block,
                l2L1Logs),
            Stream.concat(refTableModules.stream(), precompileLimitModules().stream()))
        .toList();
  }

  public Hub(
      final Address l2l1ContractAddress,
      final Bytes l2l1Topic,
      final BigInteger nonnegativeChainId) {
    checkState(nonnegativeChainId.signum() >= 0);
    chainId = nonnegativeChainId;
    l2Block = new L2Block(l2l1ContractAddress, LogTopic.of(l2l1Topic));
    l2L1Logs = new L2L1Logs(l2Block);
    keccak = new Keccak(ecRecoverEffectiveCall, l2Block);
    shakiraData = new ShakiraData(wcp, sha256Blocks, keccak, ripemdBlocks);
    blockdata = new Blockdata(wcp, txnData, rlpTxn, chainId);
    mmu = new Mmu(euc, wcp);
    mmio = new Mmio(mmu);

    refTableModules = List.of(new BinRt(), new InstructionDecoder(), new ShfRt());

    modules =
        Stream.concat(
                Stream.of(
                    add,
                    bin,
                    blakeModexpData,
                    blockhash, /* WARN: must be called BEFORE WCP (for traceEndConflation) */
                    ecData,
                    euc,
                    ext,
                    gas,
                    mmio,
                    mmu,
                    mod,
                    mul,
                    mxp,
                    oob,
                    exp,
                    rlpAddr,
                    rlpTxn,
                    rlpTxnRcpt,
                    logData, /* WARN: must be called AFTER rlpTxnRcpt */
                    logInfo, /* WARN: must be called AFTER rlpTxnRcpt */
                    rom,
                    romLex,
                    shakiraData,
                    shf,
                    stp,
                    trm,
                    wcp, /* WARN: must be called BEFORE txnData */
                    txnData,
                    blockdata /* WARN: must be called AFTER txnData */),
                precompileLimitModules().stream())
            .toList();
  }

  @Override
  public void enterTransaction() {
    // Note: txStack.enter(); happens at traceStartTransaction as it requires world, etc
    state.enter();
    transients.conflation().stackHeightChecksForStackUnderflows().enter();
    transients.conflation().stackHeightChecksForStackOverflows().enter();
    for (Module m : modules) {
      m.enterTransaction();
    }
  }

  @Override
  public void popTransaction() {
    txStack.pop();
    state.pop();
    transients.conflation().stackHeightChecksForStackUnderflows().pop();
    transients.conflation().stackHeightChecksForStackOverflows().pop();
    for (Module m : modules) {
      m.popTransaction();
    }
  }

  /** Tracing Operation, triggered by Besu hook */
  @Override
  public void traceStartConflation(long blockCount) {
    for (Module m : modules) {
      m.traceStartConflation(blockCount);
    }
  }

  @Override
  public void traceEndConflation(final WorldView world) {
    romLex.determineCodeFragmentIndex();
    txStack.setCodeFragmentIndex(this);
    defers.resolvePostConflation(this, world);

    for (Module m : modules) {
      m.traceEndConflation(world);
    }
  }

  @Override
  public void traceStartBlock(final ProcessableBlockHeader processableBlockHeader) {
    state.firstAndLastStorageSlotOccurrences.add(new HashMap<>());
    this.transients().block().update(processableBlockHeader);
    txStack.resetBlock();
    for (Module m : modules) {
      m.traceStartBlock(processableBlockHeader);
    }
  }

  @Override
  public void traceEndBlock(final BlockHeader blockHeader, final BlockBody blockBody) {
    for (Module m : modules) {
      m.traceEndBlock(blockHeader, blockBody);
    }
  }

  public void traceStartTransaction(final WorldView world, final Transaction tx) {
    pch.reset();
    txStack.enterTransaction(world, tx, transients.block());

    final TransactionProcessingMetadata transactionProcessingMetadata = txStack.current();

    this.enterTransaction();

    if (!transactionProcessingMetadata.requiresEvmExecution()) {
      state.setProcessingPhase(TX_SKIP);
      new TxSkippedSection(this, world, transactionProcessingMetadata, transients);
    } else {
      if (transactionProcessingMetadata.requiresPrewarming()) {
        state.setProcessingPhase(TX_WARM);
        new TxPreWarmingMacroSection(world, this);
      }
      state.setProcessingPhase(TX_INIT);
      new TxInitializationSection(this, world);
    }

    // Note: for deployment transactions the deployment number / status were updated during the
    // initialization phase. We are thus capturing the respective XXX_NEW's
    transactionProcessingMetadata
        .captureUpdatedInitialRecipientAddressDeploymentInfoAtTransactionStart(this);

    /*
     * TODO: the ID = 0 (universal parent context) context should
     *  1. be shared by all transactions in a conflation (OK)
     *  2. should be the father of all root contexts
     *  3. should have the current root context as its lastCallee()
     */
    callStack.getById(0).universalParentReturnDataContextNumber(this.stamp() + 1);

    for (Module m : modules) {
      m.traceStartTx(world, transactionProcessingMetadata);
    }
  }

  public void traceEndTransaction(
      WorldView world,
      Transaction tx,
      boolean isSuccessful,
      List<Log> logs,
      Set<Address> selfDestructs) {
    // TODO: see issue #875. It is currently unclear which, if any,
    //  rollbacks already took place at traceEndTransaction.

    // TODO: add the following resolution this.defers.resolvePostRollback(this, ...

    txStack.current().completeLineaTransaction(this, isSuccessful, logs, selfDestructs);

    defers.resolvePostTransaction(this, world, tx, isSuccessful);

    // Warn: we need to call MMIO after resolving the defers
    for (Module m : modules) {
      m.traceEndTx(txStack.current());
    }

    // Compute the line counting of the HUB of the current transaction
    state.lineCounter().add(state.currentTxTrace().lineCount());
  }

  @Override
  public void traceContextEnter(MessageFrame frame) {
    pch.reset();

    // root and transaction call data context's
    if (frame.getDepth() == 0) {
      final TransactionProcessingMetadata currentTransaction = transients().tx();
      final Address recipientAddress = frame.getRecipientAddress();
      final Address senderAddress = frame.getSenderAddress();
      final boolean isDeployment = frame.getType() == CONTRACT_CREATION;
      final Wei value = frame.getValue();
      final long initiallyAvailableGas = frame.getRemainingGas();

      checkArgument(
          recipientAddress.equals(effectiveToAddress(currentTransaction.getBesuTransaction())));
      checkArgument(senderAddress.equals(currentTransaction.getBesuTransaction().getSender()));
      checkArgument(isDeployment == currentTransaction.getBesuTransaction().getTo().isEmpty());
      checkArgument(
          value.equals(
              Wei.of(currentTransaction.getBesuTransaction().getValue().getAsBigInteger())));
      checkArgument(frame.getRemainingGas() == currentTransaction.getInitiallyAvailableGas());

      final boolean copyTransactionCallData = currentTransaction.copyTransactionCallData();
      if (copyTransactionCallData) {
        callStack.newTransactionCallDataContext(
            callDataContextNumber(true), currentTransaction.getBesuTransaction().getData().get());
      }

      callStack.newRootContext(
          newChildContextNumber(),
          senderAddress,
          recipientAddress,
          new Bytecode(
              currentTransaction.isDeployment()
                  ? currentTransaction.getBesuTransaction().getInit().orElse(Bytes.EMPTY)
                  : Optional.ofNullable(frame.getWorldUpdater().get(recipientAddress))
                      .map(AccountState::getCode)
                      .orElse(Bytes.EMPTY)),
          value,
          initiallyAvailableGas,
          callDataContextNumber(copyTransactionCallData),
          transients.tx().getBesuTransaction().getData().orElse(Bytes.EMPTY),
          this.deploymentNumberOf(recipientAddress),
          this.deploymentNumberOf(recipientAddress),
          this.deploymentStatusOf(recipientAddress));

      this.currentFrame().initializeFrame(frame);
    }

    // internal transaction (CALL) or internal deployment (CREATE)
    if (frame.getDepth() > 0) {
      final OpCode currentOpCode = callStack.currentCallFrame().opCode();
      checkState(currentOpCode.isCall() || currentOpCode.isCreate());
      checkState(
          currentTraceSection() instanceof CallSection
              || currentTraceSection() instanceof CreateSection);
      final boolean isDeployment = frame.getType() == CONTRACT_CREATION;
      final CallFrameType frameType =
          frame.isStatic() ? CallFrameType.STATIC : CallFrameType.STANDARD;

      final long callDataOffset =
          isDeployment
              ? 0
              : Words.clampedToLong(
                  callStack
                      .currentCallFrame()
                      .frame()
                      .getStackItem(currentOpCode.callMayNotTransferValue() ? 2 : 3));

      final long callDataSize =
          isDeployment
              ? 0
              : Words.clampedToLong(
                  callStack
                      .currentCallFrame()
                      .frame()
                      .getStackItem(currentOpCode.callMayNotTransferValue() ? 3 : 4));

      final long callDataContextNumber = callStack.currentCallFrame().contextNumber();

      currentFrame().rememberGasNextBeforePausing();
      currentFrame().pauseCurrentFrame();

      MemorySpan returnDataTargetInCaller =
          (currentTraceSection() instanceof CallSection)
              ? ((CallSection) currentTraceSection()).getCallProvidedReturnDataTargetSpan()
              : MemorySpan.empty();

      callStack.enter(
          frameType,
          newChildContextNumber(),
          this.deploymentStatusOf(frame.getContractAddress()),
          frame.getValue(),
          frame.getRemainingGas(),
          frame.getRecipientAddress(),
          this.deploymentNumberOf(frame.getRecipientAddress()),
          frame.getContractAddress(),
          this.deploymentNumberOf(frame.getContractAddress()),
          new Bytecode(frame.getCode().getBytes()),
          frame.getSenderAddress(),
          frame.getInputData(),
          callDataOffset,
          callDataSize,
          callDataContextNumber,
          returnDataTargetInCaller);

      this.currentFrame().initializeFrame(frame);

      defers.resolveUponContextEntry(this);

      for (Module m : modules) {
        m.traceContextEnter(frame);
      }
    }
  }

  @Override
  public void traceContextExit(MessageFrame frame) {
    this.currentFrame().initializeFrame(frame); // TODO: is it needed ?

    exitDeploymentFromDeploymentInfoPov(frame);

    // We take a snapshot before exiting the transaction
    if (frame.getDepth() == 0) {
      final long leftOverGas = frame.getRemainingGas();
      final long gasRefund = frame.getGasRefund();
      final boolean coinbaseIsWarm = frame.isAddressWarm(txStack.current().getCoinbase());

      txStack
          .current()
          .setPreFinalisationValues(
              leftOverGas,
              gasRefund,
              coinbaseIsWarm,
              txStack.getAccumulativeGasUsedInBlockBeforeTxStart());

      if (state.getProcessingPhase() != TX_SKIP) {
        state.setProcessingPhase(TX_FINL);
        new TxFinalizationSection(this, frame.getWorldUpdater());
      }
    }

    defers.resolveUponContextExit(this, this.currentFrame());
    // TODO: verify me please @Olivier
    if (this.currentFrame().opCode() == REVERT || Exceptions.any(pch.exceptions())) {
      defers.resolvePostRollback(this, frame, this.currentFrame());
    }

    if (frame.getDepth() > 0) {
      callStack.exit();
    }
  }

  public void traceContextReEnter(MessageFrame frame) {
    // Note: the update of the currentId call frame is made during traceContextExit of the child
    // frame
    this.currentFrame().initializeFrame(frame); // TODO: is it needed ?
    defers.resolveUponContextReEntry(this, this.currentFrame());
    this.unlatchStack(frame, this.currentFrame().childSpanningSection());
  }

  public void tracePreExecution(final MessageFrame frame) {
    checkArgument(
        this.state().processingPhase == TX_EXEC,
        "There can't be any execution if the HUB is not in execution phase");

    this.processStateExec(frame);
  }

  public void tracePostExecution(MessageFrame frame, Operation.OperationResult operationResult) {
    checkArgument(
        this.state().processingPhase == TX_EXEC,
        "There can't be any execution if the HUB is not in execution phase");

    final TraceSection currentSection = state.currentTxTrace().currentSection();

    compareLineaAndBesuGasCosts(frame, operationResult);

    /*
     * NOTE: whenever there is an exception, a context row
     * is added at the end of the section; its purpose is
     * to update the caller / creator context with empty
     * return data.
     */
    if (isExceptional()) {
      this.currentTraceSection()
          .addFragments(ContextFragment.executionProvidesEmptyReturnData(this));
      this.squashCurrentFrameOutputData();
      this.squashParentFrameReturnData();
    }

    defers.resolvePostExecution(this, frame, operationResult);

    if (!this.currentFrame().opCode().isCall() && !this.currentFrame().opCode().isCreate()) {
      this.unlatchStack(frame, currentSection);
    }
  }

  /**
   * Compares the gas costs between Linea and Besu. The total cost should be the same for both, but
   * it is batched/split differently. This is especially true for opcodes requiring memory
   * expansion. In Linea's arithmetization, the cost of CALLs and CREATEs doesn't include the gas
   * paid to the child context. This cost is accounted for separately. The deployment cost is
   * included in the arithmetization but paid separately in Besu.
   *
   * @param frame the current message frame
   * @param operationResult the result of the operation being executed
   */
  private void compareLineaAndBesuGasCosts(
      MessageFrame frame, Operation.OperationResult operationResult) {
    TraceSection currentSection = state.currentTxTrace().currentSection();
    long besuGasCost = operationResult.getGasCost();
    long lineaGasCost = currentSection.commonValues.gasCost();
    long lineaGasCostExcludingDeploymentCost =
        currentSection.commonValues.gasCostExcluduingDeploymentCost();

    if (operationResult.getHaltReason() != null) {
      return;
    }

    if (returnFromDeployment(frame)) {
      checkState(
          besuGasCost == lineaGasCostExcludingDeploymentCost,
          "besuGasCost: %d, lineaGasCostExcludingDeploymentCost: %d",
          besuGasCost,
          lineaGasCostExcludingDeploymentCost);
      return;
    }

    // TODO: same check but for CALL and CREATE's
    if (!opCode().isCall() && !opCode().isCreate()) {
      checkState(
          besuGasCost == lineaGasCost,
          "besuGasCost: %d, lineaGasCost: %d",
          besuGasCost,
          lineaGasCost);
    }
  }

  public boolean isUnexceptional() {
    return currentTraceSection().commonValues.tracedException() == NONE;
  }

  public boolean isExceptional() {
    return !isUnexceptional();
  }

  public boolean raisesOogxOrIsUnexceptional() {
    return currentTraceSection().commonValues.tracedException() == OUT_OF_GAS_EXCEPTION
        || isUnexceptional();
  }

  /**
   * If the current execution context is a deployment context the present method "exits" that
   * deployment in the sense that it updates the relevant deployment information.
   */
  private void exitDeploymentFromDeploymentInfoPov(MessageFrame frame) {

    // sanity check
    final Address bytecodeAddress = this.currentFrame().byteCodeAddress();
    checkArgument(bytecodeAddress.equals(frame.getContractAddress()));
    checkArgument(bytecodeAddress.equals(this.bytecodeAddress()));

    /**
     * Explanation: if the current address isn't under deployment there is nothing to do.
     *
     * <p>If the transaction is of TX_SKIP type then it is a deployment it has empty code and is
     * immediately set to the deployed state
     */
    if (state.processingPhase == TX_SKIP) {
      checkArgument(!deploymentStatusOfBytecodeAddress());
      return;
    }
    /**
     * We can't say anything if the current frame is a message call: we might have attempted a call
     * to an address that is undergoing deployment (or a normal one.)
     */
    if (frame.getType() == MESSAGE_CALL) {
      return;
    }

    // from here on out:
    // - state.processingPhase != TX_SKIP
    // - messageFrame.type == CONTRACT_CREATION

    /**
     * Note: we can't a priori know the deployment status of an address where a CREATE(2) raised the
     * Failure Condition F. We also do not want to modify its deployment status. Deployment might
     * still be underway, e.g.
     *
     * <p>bytecode A executes CREATE2; bytecode B is the init code; bytecode B executes a CALL to
     * address A; bytecode A executes exactly the same CREATE2 raising the Failure Condition F for
     * address B;
     */
    if (failureConditionForCreates) {
      return;
    }
    // from here on out: no failure condition
    // we must still distinguish between 'empty' deployments and 'nonempty' ones

    final boolean emptyDeployment = messageFrame().getCode().getBytes().isEmpty();

    // empty deployments are immediately considered as 'deployed' i.e.
    // deploymentStatus = false
    checkArgument(deploymentStatusOfBytecodeAddress() == !emptyDeployment);

    if (emptyDeployment) return;
    // from here on out nonempty deployments

    // we transition 'nonempty deployments' from 'underDeployment' to 'deployed'
    transients.conflation().deploymentInfo().markAsNotUnderDeployment(bytecodeAddress);
  }

  public int getCfiByMetaData(
      final Address address, final int deploymentNumber, final boolean deploymentStatus) {
    return this.romLex()
        .getCodeFragmentIndexByMetadata(
            ContractMetadata.make(address, deploymentNumber, deploymentStatus));
  }

  public int callDataContextNumber(final boolean shouldCopyTxCallData) {
    return shouldCopyTxCallData ? this.stamp() : 0;
  }

  public static int newIdentifierFromStamp(int h) {
    return 1 + h;
  }

  public int newChildContextNumber() {
    return newIdentifierFromStamp(this.stamp());
  }

  public CallFrame currentFrame() {
    return callStack().isEmpty() ? CallFrame.EMPTY : callStack.currentCallFrame();
  }

  public final MessageFrame messageFrame() {
    return callStack.currentCallFrame().frame();
  }

  private void handleStack(MessageFrame frame) {
    this.currentFrame()
        .stack()
        .processInstruction(this, frame, MULTIPLIER___STACK_STAMP * (stamp() + 1));
  }

  void triggerModules(MessageFrame frame) {
    if (pch.signals().add()) {
      add.tracePreOpcode(frame);
    }
    if (pch.signals().bin()) {
      bin.tracePreOpcode(frame);
    }
    if (pch.signals().mul()) {
      mul.tracePreOpcode(frame);
    }
    if (pch.signals().ext()) {
      ext.tracePreOpcode(frame);
    }
    if (pch.signals().mod()) {
      mod.tracePreOpcode(frame);
    }
    if (pch.signals().wcp()) {
      wcp.tracePreOpcode(frame);
    }
    if (pch.signals().shf()) {
      shf.tracePreOpcode(frame);
    }
    if (pch.signals().blockhash()) {
      blockhash.tracePreOpcode(frame);
    }
  }

  public int stamp() {
    return state.stamps().hub();
  }

  public OpCodeData opCodeData() {
    return this.currentFrame().opCodeData();
  }

  public OpCode opCode() {
    return this.currentFrame().opCode();
  }

  TraceSection currentTraceSection() {
    return state.currentTxTrace().currentSection();
  }

  public void addTraceSection(TraceSection section) {
    state.currentTxTrace().add(section);
  }

  public void unlatchStack(MessageFrame frame, TraceSection section) {
    if (this.currentFrame().pending() == null) {
      return;
    }

    final StackContext pending = this.currentFrame().pending();
    for (int i = 0; i < pending.lines().size(); i++) {
      final StackLine line = pending.lines().get(i);

      if (line.needsResult()) {
        Bytes result = Bytes.EMPTY;
        // Only pop from the stack if no exceptions have been encountered
        // TODO: when we call this from contextReenter, pch.exceptions is not the one from the
        // caller/creater ?
        if (Exceptions.none(pch.exceptions())) {
          result = frame.getStackItem(0).copy();
        }

        // This works because we are certain that the stack chunks are the first.
        ((StackFragment) section.fragments().get(i))
            .stackOps()
            .get(line.resultColumn() - 1)
            .value(result);
      }
    }
  }

  void processStateExec(MessageFrame frame) {
    pch.setup(frame);

    this.handleStack(frame);
    this.triggerModules(frame);

    if (currentFrame().stack().isOk()) {
      this.traceOpcode(frame);
    } else {
      this.squashCurrentFrameOutputData();
      this.squashParentFrameReturnData();
      new EarlyExceptionSection(this);
    }

    if (Exceptions.any(pch().exceptions()) || opCode() == REVERT) {
      currentFrame().setRevertStamps(callStack, stamp());
    }
  }

  // TODO: how do these implementations of remainingGas()
  //  and expectedGas() behave with respect to resuming
  //  execution after a CALL / CREATE ? One of them is
  //  necessarily false ...
  public long remainingGas() {
    return this.state().getProcessingPhase() == TX_EXEC
        ? this.currentFrame().frame().getRemainingGas()
        : 0;
  }

  public int cumulatedTxCount() {
    return state.txCount();
  }

  void traceOpcode(MessageFrame frame) {

    // TODO: supremely ugly hack, somebody please clean up this mess
    failureConditionForCreates = false;

    switch (this.opCodeData().instructionFamily()) {
      case ADD, MOD, SHF, BIN, WCP, EXT, BATCH, PUSH_POP, DUP, SWAP -> new StackOnlySection(this);
      case MACHINE_STATE -> {
        switch (this.opCode()) {
          case OpCode.MSIZE -> new MsizeSection(this);
          default -> new StackOnlySection(this);
        }
      }
      case MUL -> {
        switch (this.opCode()) {
          case OpCode.EXP -> new ExpSection(this);
          case OpCode.MUL -> new StackOnlySection(this);
          default -> throw new IllegalStateException(
              String.format("opcode %s not part of the MUL instruction family", this.opCode()));
        }
      }
      case HALT -> {
        final CallFrame parentFrame = callStack.parent();
        parentFrame.returnDataContextNumber(this.currentFrame().contextNumber());
        final Bytes outputData = transients.op().outputData();
        this.currentFrame().outputDataSpan(transients.op().outputDataSpan());
        this.currentFrame().outputData(outputData);

        // The output data always becomes return data of the caller when REVERT'ing
        // and in all other cases becomes return data of the caller iff the present
        // context is a message call context
        final boolean outputDataBecomesParentReturnData =
            (this.opCode() == REVERT || this.currentFrame().isMessageCall());

        if (outputDataBecomesParentReturnData) {
          parentFrame.returnData(outputData);
          parentFrame.returnDataSpan(transients.op().outputDataSpan());
        } else {
          this.squashParentFrameReturnData();
        }

        switch (this.opCode()) {
          case RETURN -> new ReturnSection(this);
          case REVERT -> new RevertSection(this);
          case STOP -> new StopSection(this);
          case SELFDESTRUCT -> new SelfdestructSection(this);
        }
      }

      case KEC -> new KeccakSection(this);
      case CONTEXT -> new ContextSection(this);
      case LOG -> new LogSection(this);
      case ACCOUNT -> new AccountSection(this);
      case COPY -> {
        switch (this.opCode()) {
          case OpCode.CALLDATACOPY -> new CallDataCopySection(this);
          case OpCode.RETURNDATACOPY -> new ReturnDataCopySection(this);
          case OpCode.CODECOPY -> new CodeCopySection(this);
          case OpCode.EXTCODECOPY -> new ExtCodeCopySection(this);
          default -> throw new RuntimeException(
              "Invalid instruction: " + this.opCode().toString() + " not in the COPY family");
        }
      }

      case TRANSACTION -> new TransactionSection(this);

      case STACK_RAM -> {
        switch (this.currentFrame().opCode()) {
          case CALLDATALOAD -> new CallDataLoadSection(this);
          case MLOAD, MSTORE, MSTORE8 -> new StackRamSection(this);
          default -> throw new IllegalStateException("unexpected STACK_RAM opcode");
        }
      }

      case STORAGE -> {
        switch (this.currentFrame().opCode()) {
          case SSTORE -> new SstoreSection(this, frame.getWorldUpdater());
          case SLOAD -> new SloadSection(this, frame.getWorldUpdater());
          default -> throw new IllegalStateException("invalid operation in family STORAGE");
        }
      }

      case JUMP -> new JumpSection(this);

      case CREATE -> new CreateSection(this);

      case CALL -> new CallSection(this);
      case INVALID -> new EarlyExceptionSection(this);
    }
  }

  public void squashCurrentFrameOutputData() {
    this.currentFrame().outputDataSpan(MemorySpan.empty());
    this.currentFrame().outputData(Bytes.EMPTY);
  }

  public void squashParentFrameReturnData() {
    final CallFrame parentFrame = callStack.parent();
    parentFrame.returnData(Bytes.EMPTY);
    parentFrame.returnDataSpan(MemorySpan.empty());
  }

  public CallFrame getLastChildCallFrame(final CallFrame parentFrame) {
    return callStack.getById(parentFrame.childFramesId().getLast());
  }

  // Quality of life deployment info related functions
  public final int deploymentNumberOf(Address address) {
    return transients.conflation().deploymentInfo().deploymentNumber(address);
  }

  public final boolean deploymentStatusOf(Address address) {
    return transients.conflation().deploymentInfo().getDeploymentStatus(address);
  }

  // methods related to the byte code address
  // (c in the definition of \Theta in the EYP)
  public final Address bytecodeAddress() {
    return this.messageFrame().getContractAddress();
  }

  public final int deploymentNumberOfBytecodeAddress() {
    return deploymentNumberOf(bytecodeAddress());
  }

  public final boolean deploymentStatusOfBytecodeAddress() {
    return deploymentStatusOf(bytecodeAddress());
  }

  // methods related to the account address
  // (r in the definition of \Theta in the EYP)
  // (also I_a in the EYP)
  public final Address accountAddress() {
    return this.messageFrame().getRecipientAddress();
  }

  public final int deploymentNumberOfAccountAddress() {
    return deploymentNumberOf(this.accountAddress());
  }

  public final boolean deploymentStatusOfAccountAddress() {
    return deploymentStatusOf(this.accountAddress());
  }

  public final boolean returnFromMessageCall(MessageFrame frame) {
    return opCode() == RETURN && frame.getType() == MESSAGE_CALL;
  }

  public final boolean returnFromDeployment(MessageFrame frame) {
    return opCode() == RETURN && frame.getType() == CONTRACT_CREATION;
  }
}
