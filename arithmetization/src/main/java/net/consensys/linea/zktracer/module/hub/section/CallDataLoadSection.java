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

package net.consensys.linea.zktracer.module.hub.section;

import static com.google.common.base.Preconditions.checkArgument;
import static net.consensys.linea.zktracer.module.hub.fragment.ContextFragment.readCurrentContextData;

import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.fragment.ContextFragment;
import net.consensys.linea.zktracer.module.hub.fragment.imc.ImcFragment;
import net.consensys.linea.zktracer.module.hub.fragment.imc.mmu.MmuCall;
import net.consensys.linea.zktracer.module.hub.fragment.imc.oob.opcodes.CallDataLoadOobCall;
import net.consensys.linea.zktracer.module.hub.signals.Exceptions;
import net.consensys.linea.zktracer.opcode.OpCode;

public class CallDataLoadSection extends TraceSection {

  public CallDataLoadSection(Hub hub) {
    super(hub, (short) (hub.opCode().equals(OpCode.CALLDATALOAD) ? 4 : 3));
    this.addStack(hub);

    final short exception = hub.pch().exceptions();

    final ImcFragment imcFragment = ImcFragment.empty(hub);
    this.addFragment(imcFragment);

    final CallDataLoadOobCall oobCall = new CallDataLoadOobCall();
    imcFragment.callOob(oobCall);

    if (Exceptions.none(exception)) {
      if (!oobCall.isCdlOutOfBounds()) {
        final MmuCall mmuCall = MmuCall.callDataLoad(hub);
        imcFragment.callMmu(mmuCall);
      }
    } else {
      // Sanity check
      checkArgument(Exceptions.outOfGasException(exception));
    }

    final ContextFragment context = readCurrentContextData(hub);
    this.addFragment(context);
  }
}
