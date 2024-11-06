package net.consensys.linea.zktracer.exceptions;

import static net.consensys.linea.zktracer.module.hub.signals.TracedException.MEMORY_EXPANSION_EXCEPTION;
import static net.consensys.linea.zktracer.module.mxp.MxpTestUtils.opCodesType2;
import static net.consensys.linea.zktracer.module.mxp.MxpTestUtils.opCodesType3;
import static net.consensys.linea.zktracer.module.mxp.MxpTestUtils.opCodesType4;
import static net.consensys.linea.zktracer.module.mxp.MxpTestUtils.opCodesType5;
import static net.consensys.linea.zktracer.module.mxp.MxpTestUtils.triggerNonTrivialButMxpxOrRoobForOpCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.consensys.linea.testing.BytecodeCompiler;
import net.consensys.linea.testing.BytecodeRunner;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.opcode.gas.MxpType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class MemoryExpansionExceptionTest {

  @ParameterizedTest
  @MethodSource("memoryExpansionExceptionTestSource")
  public void memoryExpansionExceptionTest(boolean triggerRoob, MxpType mxpType, OpCode opCode) {
    BytecodeCompiler program = BytecodeCompiler.newProgram();
    triggerNonTrivialButMxpxOrRoobForOpCode(program, triggerRoob, mxpType, opCode);
    BytecodeRunner bytecodeRunner = BytecodeRunner.of(program.compile());
    bytecodeRunner.run();
    assertEquals(
        MEMORY_EXPANSION_EXCEPTION,
        bytecodeRunner.getHub().previousTraceSection().commonValues.tracedException());
    assertEquals(triggerRoob, bytecodeRunner.getHub().mxp().operations().getLast().isRoob());
    assertTrue(bytecodeRunner.getHub().mxp().operations().getLast().getMxpCall().isMxpx());
  }

  private static Stream<Arguments> memoryExpansionExceptionTestSource() {
    List<Arguments> arguments = new ArrayList<>();
    for (OpCode opCode : opCodesType2) {
      arguments.add(Arguments.of(false, MxpType.TYPE_2, opCode));
      arguments.add(Arguments.of(true, MxpType.TYPE_2, opCode));
    }
    for (OpCode opCode : opCodesType3) {
      arguments.add(Arguments.of(false, MxpType.TYPE_3, opCode));
      arguments.add(Arguments.of(true, MxpType.TYPE_3, opCode));
    }
    for (OpCode opCode : opCodesType4) {
      arguments.add(Arguments.of(false, MxpType.TYPE_4, opCode));
      arguments.add(Arguments.of(true, MxpType.TYPE_4, opCode));
    }
    for (OpCode opCode : opCodesType5) {
      arguments.add(Arguments.of(false, MxpType.TYPE_5, opCode));
      arguments.add(Arguments.of(true, MxpType.TYPE_5, opCode));
    }
    return arguments.stream();
  }
}
