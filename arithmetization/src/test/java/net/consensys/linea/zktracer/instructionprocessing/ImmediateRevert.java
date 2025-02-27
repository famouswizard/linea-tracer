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
package net.consensys.linea.zktracer.instructionprocessing;

import net.consensys.linea.testing.BytecodeCompiler;
import net.consensys.linea.testing.BytecodeRunner;
import net.consensys.linea.zktracer.opcode.OpCode;
import org.junit.jupiter.api.Test;

public class ImmediateRevert {

  @Test
  void testImmediatePop() {
    BytecodeRunner.of(BytecodeCompiler.newProgram().op(OpCode.POP).compile()).run();
  }

  @Test
  void testPopWithinCreate() {
    BytecodeRunner.of(
            BytecodeCompiler.newProgram()
                .push(0x50) // POP
                .push(0)
                .op(OpCode.MSTORE8)
                .push(1) // size
                .push(0) // offset
                .push(1) // value
                .op(OpCode.CREATE)
                .compile())
        .run();
  }
}
