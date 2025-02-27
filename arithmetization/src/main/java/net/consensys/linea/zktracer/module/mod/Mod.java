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

package net.consensys.linea.zktracer.module.mod;

import java.math.BigInteger;
import java.nio.MappedByteBuffer;
import java.util.List;

import lombok.Getter;
import lombok.experimental.Accessors;
import net.consensys.linea.zktracer.ColumnHeader;
import net.consensys.linea.zktracer.container.module.OperationSetModule;
import net.consensys.linea.zktracer.container.stacked.ModuleOperationStackedSet;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.opcode.OpCodeData;
import net.consensys.linea.zktracer.opcode.OpCodes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.evm.frame.MessageFrame;

@Getter
@Accessors(fluent = true)
public class Mod implements OperationSetModule<ModOperation> {
  private final ModuleOperationStackedSet<ModOperation> operations =
      new ModuleOperationStackedSet<>();

  @Override
  public String moduleKey() {
    return "MOD";
  }

  @Override
  public void tracePreOpcode(final MessageFrame frame) {
    final OpCodeData opCodeData = OpCodes.of(frame.getCurrentOperation().getOpcode());
    final Bytes32 arg1 = Bytes32.leftPad(frame.getStackItem(0));
    final Bytes32 arg2 = Bytes32.leftPad(frame.getStackItem(1));

    operations.add(new ModOperation(opCodeData.mnemonic(), arg1, arg2));
  }

  @Override
  public void commit(List<MappedByteBuffer> buffers) {
    final Trace trace = new Trace(buffers);
    int stamp = 0;
    for (ModOperation op : operations.sortOperations(new ModOperationComparator())) {
      op.trace(trace, ++stamp);
    }
  }

  @Override
  public List<ColumnHeader> columnsHeaders() {
    return Trace.headers(this.lineCount());
  }

  /**
   * Performs a context-free call to the DIV opcode in the current trace.
   *
   * @param arg1 the divider
   * @param arg2 the dividend
   */
  public BigInteger callDIV(Bytes32 arg1, Bytes32 arg2) {
    this.operations.add(new ModOperation(OpCode.DIV, arg1, arg2));
    return arg1.toUnsignedBigInteger().divide(arg2.toUnsignedBigInteger());
  }

  /**
   * Performs a context-free call to the MOD opcode in the current trace.
   *
   * @param arg1 the number
   * @param arg2 the module
   */
  public BigInteger callMOD(Bytes32 arg1, Bytes32 arg2) {
    this.operations.add(new ModOperation(OpCode.MOD, arg1, arg2));
    return arg1.toUnsignedBigInteger().mod(arg2.toUnsignedBigInteger());
  }
}
