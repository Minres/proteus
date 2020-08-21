package riscv.plugins.capabilities

import riscv._

import spinal.core._

class Access(stage: Stage)(implicit context: Context) extends Plugin[Pipeline] {
  object FieldSelect extends SpinalEnum {
    val PERM, BASE, LEN, TAG, OFFSET, ADDR = newElement()
  }

  object Modification extends SpinalEnum {
    val SET_BOUNDS, CLEAR_TAG = newElement()
  }

  object Data {
    object CGET extends PipelineData(Bool())
    object CFIELD extends PipelineData(FieldSelect())
    object CMODIFY extends PipelineData(Bool())
    object CMODIFICATION extends PipelineData(Modification())
  }

  override def setup(): Unit = {
    pipeline.getService[DecoderService].configure {config =>
      config.addDefault(Map(
        Data.CGET -> False,
        Data.CMODIFY -> False
      ))

      val getters = Map(
        Opcodes.CGetPerm   -> FieldSelect.PERM,
        Opcodes.CGetBase   -> FieldSelect.BASE,
        Opcodes.CGetLen    -> FieldSelect.LEN,
        Opcodes.CGetTag    -> FieldSelect.TAG,
        Opcodes.CGetOffset -> FieldSelect.OFFSET,
        Opcodes.CGetAddr   -> FieldSelect.ADDR
      )

      for ((opcode, selector) <- getters) {
        config.addDecoding(opcode, InstructionType.R_CxR, Map(
          Data.CGET -> True,
          Data.CFIELD -> selector
        ))
      }

      val modifiers = Seq(
        (Opcodes.CSetBounds,      Modification.SET_BOUNDS, InstructionType.R_CRC),
        (Opcodes.CSetBoundsExact, Modification.SET_BOUNDS, InstructionType.R_CRC),
        (Opcodes.CSetBoundsImm,   Modification.SET_BOUNDS, InstructionType.I_CxC)
      )

      for ((opcode, modification, itype) <- modifiers) {
        config.addDecoding(opcode, itype, Map(
          Data.CMODIFY -> True,
          Data.CMODIFICATION -> modification
        ))
      }
    }
  }

  override def build(): Unit = {
    stage plug new Area {
      import stage._

      when (arbitration.isValid) {
        when (value(Data.CGET)) {
          arbitration.rs1Needed := True
          val cap = value(context.data.CS1_DATA)

          when (!arbitration.isStalled) {
            val rd = value(Data.CFIELD).mux(
              FieldSelect.PERM -> cap.perms.asIsaBits.asUInt.resized,
              FieldSelect.BASE -> cap.base,
              FieldSelect.LEN -> cap.length,
              FieldSelect.TAG -> cap.tag.asUInt.resized,
              FieldSelect.OFFSET -> cap.offset,
              FieldSelect.ADDR -> (cap.base + cap.offset) // TODO: use ALU
            )

            output(pipeline.data.RD_DATA) := rd
            output(pipeline.data.RD_VALID) := True
          }
        }

        when (value(Data.CMODIFY)) {
          arbitration.rs1Needed := True
          val cs = value(context.data.CS1_DATA)

          val bounds = UInt(config.xlen bits)

          when (value(pipeline.data.IMM_USED)) {
            // Since the decoder sign-extends the immediate in the I-format but
            // the CHERI modification instructions use an unsigned immediate, we
            // slice the original immediate out of the sign-extended one.
            bounds := value(pipeline.data.IMM)(11 downto 0).resized
          } otherwise {
            arbitration.rs2Needed := True
            bounds := value(pipeline.data.RS2_DATA)
          }

          val cd = Capability()
          cd := cs

          when (!arbitration.isStalled) {
            switch(value(Data.CMODIFICATION)) {
              is(Modification.SET_BOUNDS) {
                val exceptionHandler = pipeline.getService[ExceptionService]

                def except(cause: ExceptionCause) = {
                  exceptionHandler.except(stage, cause, value(pipeline.data.RS1))
                }

                val newTop = cs.address + bounds

                when(!cs.tag) {
                  except(ExceptionCause.TagViolation)
                } elsewhen (cs.address < cs.base) {
                  except(ExceptionCause.LengthViolation)
                } elsewhen (newTop > cs.top) {
                  except(ExceptionCause.LengthViolation)
                } otherwise {
                  cd.base := cs.address
                  cd.length := bounds
                  cd.offset := 0
                }
              }
            }

            output(context.data.CD_DATA) := cd
            output(pipeline.data.RD_VALID) := True
          }
        }
      }
    }
  }
}
