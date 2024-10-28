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

import static net.consensys.linea.zktracer.module.Util.boolToInt;
import static net.consensys.linea.zktracer.module.constants.GlobalConstants.MMEDIUM;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.consensys.linea.zktracer.bytestheta.BaseBytes;
import net.consensys.linea.zktracer.bytestheta.BaseTheta;
import net.consensys.linea.zktracer.bytestheta.BytesArray;
import net.consensys.linea.zktracer.container.ModuleOperation;
import net.consensys.linea.zktracer.module.ext.calculator.AbstractExtCalculator;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.types.UnsignedByte;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

@Accessors(fluent = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class ExtOperation extends ModuleOperation {

  @EqualsAndHashCode.Include @Getter private final OpCode opCode;
  @EqualsAndHashCode.Include @Getter private final BaseBytes arg1;
  @EqualsAndHashCode.Include @Getter private final BaseBytes arg2;
  @EqualsAndHashCode.Include @Getter private final BaseBytes arg3;
  private final boolean isOneLineInstruction;

  private BaseTheta result;
  private BaseTheta aBytes;
  private BaseTheta bBytes;
  private BaseTheta cBytes;
  private BaseTheta deltaBytes;
  private BytesArray hBytes;
  private BaseTheta rBytes;
  private BytesArray iBytes;
  private BytesArray jBytes;
  private BytesArray qBytes;
  private boolean[] cmp = new boolean[8];
  boolean[] overflowH = new boolean[8];
  boolean[] overflowJ = new boolean[8];
  boolean[] overflowRes = new boolean[8];
  boolean[] overflowI = new boolean[8];

  public ExtOperation(OpCode opCode, Bytes32 arg1, Bytes32 arg2, Bytes32 arg3) {
    this.opCode = opCode;
    this.arg1 = BaseBytes.fromBytes32(arg1.copy());
    this.arg2 = BaseBytes.fromBytes32(arg2.copy());
    this.arg3 = BaseBytes.fromBytes32(arg3.copy());
    this.isOneLineInstruction = isOneLineInstruction();
  }

  public UInt256 compute() {
    AbstractExtCalculator computer = AbstractExtCalculator.create(this.opCode);
    return computer.computeResult(
        this.arg1.getBytes32(), this.arg2.getBytes32(), this.arg3.getBytes32());
  }

  public void setup() {
    this.aBytes = BaseTheta.fromBytes32(this.arg1.getBytes32());
    this.bBytes = BaseTheta.fromBytes32(this.arg2.getBytes32());
    this.cBytes = BaseTheta.fromBytes32(this.arg3.getBytes32());
    this.iBytes = new BytesArray(7);
    this.jBytes = new BytesArray(8);
    this.qBytes = new BytesArray(8);
    this.deltaBytes = BaseTheta.fromBytes32(Bytes32.ZERO);
    this.hBytes = new BytesArray(6);

    AbstractExtCalculator computer = AbstractExtCalculator.create(this.opCode);

    final UInt256 result = this.compute();
    this.result = BaseTheta.fromBytes32(result);
    this.rBytes = BaseTheta.fromBytes32(result);

    if (!this.isOneLineInstruction) {
      cmp = computer.computeComparisonFlags(cBytes, rBytes);
      deltaBytes = computer.computeDeltas(cBytes, rBytes);
      jBytes = computer.computeJs(this.arg1.getBytes32(), this.arg2.getBytes32());
      qBytes =
          computer.computeQs(
              this.arg1.getBytes32(), this.arg2.getBytes32(), this.arg3.getBytes32());
      overflowH = computer.computeHs(aBytes, bBytes, hBytes);
      overflowI = computer.computeIs(qBytes, cBytes, iBytes);
      overflowJ = computer.computeOverflowJ(qBytes, cBytes, rBytes, iBytes, getSigma(), getTau());
      overflowRes =
          computer.computeOverflowRes(
              this.arg1, this.arg2, aBytes, bBytes, hBytes, getAlpha(), getBeta());
    }
  }

  public boolean getBit1() {
    return this.opCode == OpCode.MULMOD && this.arg1.isZero();
  }

  public boolean getBit2() {
    return this.opCode == OpCode.MULMOD && this.arg2.isZero();
  }

  public boolean getBit3() {
    return UInt256.ONE.compareTo(UInt256.fromBytes(this.arg3.getBytes32())) >= 0;
  }

  /** Returns true if any of the bit1, bit2, or bit3 flags are set. */
  private boolean isOneLineInstruction() {
    return getBit1() || getBit2() || getBit3();
  }

  private int numberOfRows() {
    return isOneLineInstruction ? 1 : MMEDIUM;
  }

  @Override
  protected int computeLineCount() {
    return this.numberOfRows();
  }

  private UInt256 getSigma() {
    return UInt256.valueOf(boolToInt(overflowI[0]));
  }

  private UInt256 getAlpha() {
    return UInt256.valueOf(boolToInt(overflowH[0]));
  }

  private UInt256 getBeta() {
    return UInt256.valueOf(boolToInt(overflowH[1]) + 2L * boolToInt(overflowH[2]));
  }

  private UInt256 getTau() {
    return UInt256.valueOf(boolToInt(overflowI[1]) + 2L * boolToInt(overflowI[2]));
  }

  void trace(Trace trace, int stamp) {
    this.setup();

    for (int i = 0; i < this.numberOfRows(); i++) {
      final int accLength = i + 1;
      trace
          // Byte A and Acc A
          .byteA0(UnsignedByte.of(aBytes.get(0).get(i)))
          .byteA1(UnsignedByte.of(aBytes.get(1).get(i)))
          .byteA2(UnsignedByte.of(aBytes.get(2).get(i)))
          .byteA3(UnsignedByte.of(aBytes.get(3).get(i)))
          .accA0(aBytes.get(0).slice(0, accLength))
          .accA1(aBytes.get(1).slice(0, accLength))
          .accA2(aBytes.get(2).slice(0, accLength))
          .accA3(aBytes.get(3).slice(0, accLength))
          // Byte B and Acc B
          .byteB0(UnsignedByte.of(bBytes.get(0).get(i)))
          .byteB1(UnsignedByte.of(bBytes.get(1).get(i)))
          .byteB2(UnsignedByte.of(bBytes.get(2).get(i)))
          .byteB3(UnsignedByte.of(bBytes.get(3).get(i)))
          .accB0(bBytes.get(0).slice(0, accLength))
          .accB1(bBytes.get(1).slice(0, accLength))
          .accB2(bBytes.get(2).slice(0, accLength))
          .accB3(bBytes.get(3).slice(0, accLength))
          // Byte C and Acc C
          .byteC0(UnsignedByte.of(cBytes.get(0).get(i)))
          .byteC1(UnsignedByte.of(cBytes.get(1).get(i)))
          .byteC2(UnsignedByte.of(cBytes.get(2).get(i)))
          .byteC3(UnsignedByte.of(cBytes.get(3).get(i)))
          .accC0(cBytes.get(0).slice(0, accLength))
          .accC1(cBytes.get(1).slice(0, accLength))
          .accC2(cBytes.get(2).slice(0, accLength))
          .accC3(cBytes.get(3).slice(0, accLength))
          // Byte Delta and Acc Delta
          .byteDelta0(UnsignedByte.of(deltaBytes.get(0).get(i)))
          .byteDelta1(UnsignedByte.of(deltaBytes.get(1).get(i)))
          .byteDelta2(UnsignedByte.of(deltaBytes.get(2).get(i)))
          .byteDelta3(UnsignedByte.of(deltaBytes.get(3).get(i)))
          .accDelta0(deltaBytes.get(0).slice(0, accLength))
          .accDelta1(deltaBytes.get(1).slice(0, accLength))
          .accDelta2(deltaBytes.get(2).slice(0, accLength))
          .accDelta3(deltaBytes.get(3).slice(0, accLength))
          // Byte H and Acc H
          .byteH0(UnsignedByte.of(hBytes.get(0).get(i)))
          .byteH1(UnsignedByte.of(hBytes.get(1).get(i)))
          .byteH2(UnsignedByte.of(hBytes.get(2).get(i)))
          .byteH3(UnsignedByte.of(hBytes.get(3).get(i)))
          .byteH4(UnsignedByte.of(hBytes.get(4).get(i)))
          .byteH5(UnsignedByte.of(hBytes.get(5).get(i)))
          .accH0(hBytes.get(0).slice(0, accLength))
          .accH1(hBytes.get(1).slice(0, accLength))
          .accH2(hBytes.get(2).slice(0, accLength))
          .accH3(hBytes.get(3).slice(0, accLength))
          .accH4(hBytes.get(4).slice(0, accLength))
          .accH5(hBytes.get(5).slice(0, accLength))
          // Byte I and Acc I
          .byteI0(UnsignedByte.of(iBytes.get(0).get(i)))
          .byteI1(UnsignedByte.of(iBytes.get(1).get(i)))
          .byteI2(UnsignedByte.of(iBytes.get(2).get(i)))
          .byteI3(UnsignedByte.of(iBytes.get(3).get(i)))
          .byteI4(UnsignedByte.of(iBytes.get(4).get(i)))
          .byteI5(UnsignedByte.of(iBytes.get(5).get(i)))
          .byteI6(UnsignedByte.of(iBytes.get(6).get(i)))
          .accI0(iBytes.get(0).slice(0, accLength))
          .accI1(iBytes.get(1).slice(0, accLength))
          .accI2(iBytes.get(2).slice(0, accLength))
          .accI3(iBytes.get(3).slice(0, accLength))
          .accI4(iBytes.get(4).slice(0, accLength))
          .accI5(iBytes.get(5).slice(0, accLength))
          .accI6(iBytes.get(6).slice(0, accLength))
          // Byte J and Acc J
          .byteJ0(UnsignedByte.of(jBytes.get(0).get(i)))
          .byteJ1(UnsignedByte.of(jBytes.get(1).get(i)))
          .byteJ2(UnsignedByte.of(jBytes.get(2).get(i)))
          .byteJ3(UnsignedByte.of(jBytes.get(3).get(i)))
          .byteJ4(UnsignedByte.of(jBytes.get(4).get(i)))
          .byteJ5(UnsignedByte.of(jBytes.get(5).get(i)))
          .byteJ6(UnsignedByte.of(jBytes.get(6).get(i)))
          .byteJ7(UnsignedByte.of(jBytes.get(7).get(i)))
          .accJ0(jBytes.get(0).slice(0, accLength))
          .accJ1(jBytes.get(1).slice(0, accLength))
          .accJ2(jBytes.get(2).slice(0, accLength))
          .accJ3(jBytes.get(3).slice(0, accLength))
          .accJ4(jBytes.get(4).slice(0, accLength))
          .accJ5(jBytes.get(5).slice(0, accLength))
          .accJ6(jBytes.get(6).slice(0, accLength))
          .accJ7(jBytes.get(7).slice(0, accLength))
          // Byte Q and Acc Q
          .byteQ0(UnsignedByte.of(qBytes.get(0).get(i)))
          .byteQ1(UnsignedByte.of(qBytes.get(1).get(i)))
          .byteQ2(UnsignedByte.of(qBytes.get(2).get(i)))
          .byteQ3(UnsignedByte.of(qBytes.get(3).get(i)))
          .byteQ4(UnsignedByte.of(qBytes.get(4).get(i)))
          .byteQ5(UnsignedByte.of(qBytes.get(5).get(i)))
          .byteQ6(UnsignedByte.of(qBytes.get(6).get(i)))
          .byteQ7(UnsignedByte.of(qBytes.get(7).get(i)))
          .accQ0(qBytes.get(0).slice(0, accLength))
          .accQ1(qBytes.get(1).slice(0, accLength))
          .accQ2(qBytes.get(2).slice(0, accLength))
          .accQ3(qBytes.get(3).slice(0, accLength))
          .accQ4(qBytes.get(4).slice(0, accLength))
          .accQ5(qBytes.get(5).slice(0, accLength))
          .accQ6(qBytes.get(6).slice(0, accLength))
          .accQ7(qBytes.get(7).slice(0, accLength))
          // Byte R and Acc R
          .byteR0(UnsignedByte.of(rBytes.get(0).get(i)))
          .byteR1(UnsignedByte.of(rBytes.get(1).get(i)))
          .byteR2(UnsignedByte.of(rBytes.get(2).get(i)))
          .byteR3(UnsignedByte.of(rBytes.get(3).get(i)))
          .accR0(rBytes.get(0).slice(0, accLength))
          .accR1(rBytes.get(1).slice(0, accLength))
          .accR2(rBytes.get(2).slice(0, accLength))
          .accR3(rBytes.get(3).slice(0, accLength))
          // other
          .arg1Hi(arg1.getHigh())
          .arg1Lo(arg1.getLow())
          .arg2Hi(arg2.getHigh())
          .arg2Lo(arg2.getLow())
          .arg3Hi(arg3.getHigh())
          .arg3Lo(arg3.getLow())
          .resHi(result.getHigh())
          .resLo(result.getLow())
          .cmp(cmp[i])
          .ofH(overflowH[i])
          .ofJ(overflowJ[i])
          .ofI(overflowI[i])
          .ofRes(overflowRes[i])
          .ct(i)
          .inst(UnsignedByte.of(opCode.byteValue()))
          .oli(isOneLineInstruction)
          .bit1(getBit1())
          .bit2(getBit2())
          .bit3(getBit3())
          .stamp(stamp)
          .validateRow();
    }
  }
}
