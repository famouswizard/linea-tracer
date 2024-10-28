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

package net.consensys.linea.zktracer.module.ext;

import static org.assertj.core.api.Assertions.assertThat;

import net.consensys.linea.testing.BytecodeCompiler;
import net.consensys.linea.testing.BytecodeRunner;
import net.consensys.linea.zktracer.opcode.OpCode;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

public class TestDuplicatedOperations {

  Bytes maxUint256 =
      Bytes.fromHexString("0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
  Bytes twoToThe128 = Bytes.fromHexString("0100000000000000000000000000000000");

  @Test
  void testDuplicate() {
    BytecodeRunner.of(
            BytecodeCompiler.newProgram()
                .push(maxUint256)
                .push(0)
                .push(0)
                .op(OpCode.MULMOD)
                .push(maxUint256)
                .push(0)
                .push(0)
                .op(OpCode.MULMOD)
                .compile())
        .zkTracerValidator(
            zkTracer -> {
              assertThat(zkTracer.getModulesLineCount().get("EXT")).isEqualTo(8);
            })
        .run();
  }

  @Test
  void testSimpleMulmod() {
    BytecodeRunner.of(
            BytecodeCompiler.newProgram()
                .push(maxUint256)
                .push(twoToThe128)
                .push(twoToThe128)
                .op(OpCode.MULMOD)
                .compile())
        .run();
  }

  @Test
  void testWcpVsExt() {
    BytecodeRunner.of(
            BytecodeCompiler.newProgram()
                .push(0)
                .push(1)
                .op(OpCode.LT) // false
                .push(maxUint256)
                .push(1)
                .push(maxUint256)
                .op(OpCode.ADDMOD)
                .compile())
        .run();
  }
}
