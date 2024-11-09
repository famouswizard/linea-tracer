package net.consensys.linea.zktracer.exceptions;

import static net.consensys.linea.zktracer.module.hub.signals.TracedException.OUT_OF_GAS_EXCEPTION;
import static net.consensys.linea.zktracer.opcode.OpCodes.opCodeToOpCodeDataMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.consensys.linea.testing.BytecodeCompiler;
import net.consensys.linea.testing.BytecodeRunner;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.opcode.OpCodeData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class OutOfGasExceptionTest {

  @ParameterizedTest
  @MethodSource("outOfGasExceptionSource")
  void outOfGasExceptionTest(OpCode opCode, int cost, int nPushes) {
    BytecodeCompiler program = BytecodeCompiler.newProgram();
    for (int i = 0; i < nPushes; i++) {
      program.push(0);
    }
    program.op(opCode);
    BytecodeRunner bytecodeRunner = BytecodeRunner.of(program.compile());
    bytecodeRunner.run((long) 21000 + cost - 1);
    assertEquals(
        OUT_OF_GAS_EXCEPTION,
        bytecodeRunner.getHub().previousTraceSection().commonValues.tracedException());
  }

  static Stream<Arguments> outOfGasExceptionSource() {
    List<Arguments> arguments = new ArrayList<>();
    for (OpCodeData opCodeData : opCodeToOpCodeDataMap.values()) {
      OpCode opCode = opCodeData.mnemonic();
      int cost = opCodeData.stackSettings().staticGas().cost();
      int delta = opCodeData.stackSettings().delta(); // number of items popped from the stack
      if (cost > 0) {
        arguments.add(Arguments.of(opCode, cost, delta));
      }
    }
    return arguments.stream();
  }
}
