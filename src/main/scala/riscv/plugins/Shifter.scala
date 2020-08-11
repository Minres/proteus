package riscv.plugins

import riscv._

import spinal.core._

class Shifter(exeStage: Stage) extends Plugin[Pipeline] {
  object ShiftOp extends SpinalEnum {
    val NONE, SLL, SRL, SRA = newElement()
  }

  object Data {
    object SHIFT_OP extends PipelineData(ShiftOp())
    object SHIFT_USE_IMM extends PipelineData(Bool())
  }

  override def setup(): Unit = {
    pipeline.getService[DecoderService].configure {config =>
      config.addDefault(Map(
        Data.SHIFT_OP -> ShiftOp.NONE,
        Data.SHIFT_USE_IMM -> False
      ))

      val ops = Seq(
        (Opcodes.SLLI, ShiftOp.SLL, true),
        (Opcodes.SRLI, ShiftOp.SRL, true),
        (Opcodes.SRAI, ShiftOp.SRA, true),
        (Opcodes.SLL,  ShiftOp.SLL, false),
        (Opcodes.SRL,  ShiftOp.SRL, false),
        (Opcodes.SRA,  ShiftOp.SRA, false)
      )

      for ((opcode, op, useImm) <- ops) {
        val itype = if (useImm) InstructionType.I else InstructionType.R

        config.addDecoding(opcode, itype, Map(
          Data.SHIFT_OP -> op,
          Data.SHIFT_USE_IMM -> Bool(useImm)
        ))
      }
    }
  }

  override def build(): Unit = {
    exeStage plug new Area {
      import exeStage._

      val src = UInt(config.xlen bits)
      src := value(pipeline.data.RS1_DATA)
      val shamt = UInt(5 bits)
      val useImm = value(Data.SHIFT_USE_IMM)

      when (useImm) {
        shamt := value(pipeline.data.IMM)(4 downto 0)
      } otherwise {
        shamt := value(pipeline.data.RS2_DATA)(4 downto 0)
      }

      val op = value(Data.SHIFT_OP)

      val result = op.mux(
        ShiftOp.NONE -> U(0),
        ShiftOp.SLL  -> (src |<< shamt),
        ShiftOp.SRL  -> (src |>> shamt),
        ShiftOp.SRA  -> (src.asSInt >> shamt).asUInt
      )

      when (arbitration.isValid && op =/= ShiftOp.NONE) {
        arbitration.rs1Needed := True
        arbitration.rs2Needed := !useImm
        output(pipeline.data.RD_DATA) := result
        output(pipeline.data.RD_VALID) := True
      }
    }
  }
}
