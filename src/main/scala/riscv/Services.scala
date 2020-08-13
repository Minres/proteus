package riscv

import spinal.core._
import spinal.lib._

trait MemoryService {
  /**
    * Returns the Pipeline's instruction bus.
    */
  def getExternalIBus: MemBus

  /**
    * Returns the Pipeline's data bus.
    */
  def getExternalDBus: MemBus

  /**
    * Creates a new instruction bus to be used in stage.
    */
  def createInternalIBus(stage: Stage): MemBus

  /**
    * Creates a new data bus to be used in stage.
    */
  def createInternalDBus(stage: Stage): MemBus

  /**
   * Return all stages that have an internal DBUS. I.e., the stages that are
   * able to put requests on the DBUS.
   */
  def getDBusStages: Seq[Stage]

  type MemBusFilter = (Stage, MemBus, MemBus) => Unit

  /**
    * The `filter` function is called on every created data bus. The passed
    * arguments are 1) the stage in which the data bus is created, 2) the data
    * bus that is used in this stage, and 2) the data bus that will be connected
    * to the external data bus. When `filter` is called, the two data buses are
    * fully connected. Inside `filter`, these connection can be arbitrarily
    * altered or broken. The `filter` function is called in the context of the
    * top-level Pipeline component.
    */
  def filterDBus(filter: MemBusFilter): Unit

  type MemBusObserver = (Stage, MemBus) => Unit

  /**
    * The `observer` function is called on every created data bus. The passed
    * arguments are 1) the stage in which the data bus is created, and 2) the
    * data bus that is used in this stage. The `observer` function is called in
    * the context of the top-level Pipeline component.
    */
  def observeDBus(observer: MemBusObserver): Unit
}

trait DecoderService {
  type Action = Map[PipelineData[_ <: Data], Data]

  trait DecoderConfig {
    def addDecoding(opcode: MaskedLiteral,
                    itype: InstructionType,
                    action: Action): Unit
    def addDecoding(opcode: MaskedLiteral, action: Action): Unit
    def addDefault(action: Action): Unit
    def addDefault(data: PipelineData[_ <: Data], value: Data): Unit
  }

  protected val decoderConfig: DecoderConfig
  protected def stage: Stage

  def configure(f: DecoderConfig => Unit): Unit = {
    stage.rework(f(decoderConfig))
  }

  def getSupportedOpcodes: Iterable[MaskedLiteral]
}

trait IssueService {
  def setDestination(opcode: MaskedLiteral, stage: Stage): Unit = {
    setDestinations(opcode, Set(stage))
  }

  def setDestinations(opcode: MaskedLiteral, stages: Set[Stage]): Unit
}

trait IntAluService {
  object AluOp extends SpinalEnum {
    val ADD, SUB, SLT, SLTU, XOR, OR, AND, SRC2 = newElement()
  }

  object Src1Select extends SpinalEnum {
    val RS1, PC = newElement()
  }

  object Src2Select extends SpinalEnum {
    val RS2, IMM = newElement()
  }

  def addOperation(opcode: MaskedLiteral,
                   op: SpinalEnumElement[AluOp.type],
                   src1: SpinalEnumElement[Src1Select.type],
                   src2: SpinalEnumElement[Src2Select.type]): Unit

  def resultData: PipelineData[UInt]
}

trait JumpService {
  def jump(stage: Stage, target: UInt, isTrap: Boolean = false): Unit

  type PcUpdateObserver = (Stage, UInt, UInt) => Unit

  /**
    * Register a callback to be called whenever the program counter is updated;
    * whether explicitly through a jump or implicitly after each instruction.
    * The parameters passed to `observer` are 1) the stage that caused the
    * update, 2) the current PC, and 3) the new PC. It is called on the context
    * of the top-level Pipeline.
    */
  def onPcUpdate(observer: PcUpdateObserver): Unit
}

trait TrapService {
  def trap(stage: Stage, cause: TrapCause): Unit
  def hasTrapped(stage: Stage): Bool
  def hasException(stage: Stage): Bool
  def hasInterrupt(stage: Stage): Bool
}

trait Csr extends Area {
  def read(): UInt

  /**
    * Called for internal writes through the CsrIo interface.
    */
  def write(value: UInt): Unit = assert(false, "Cannot write RO CSR")

  /**
    * Called for software writes (CSR* instructions).
    */
  def swWrite(value: UInt): Unit = write(value)
}

class CsrIo(implicit config: Config) extends Bundle with IMasterSlave {
  val rdata, wdata = UInt(config.xlen bits)
  val write = Bool()

  def read(): UInt = rdata

  def write(value: UInt): Unit = {
    write := True
    wdata := value
  }

  override def asMaster(): Unit = {
    in(wdata, write)
    out(rdata)
  }

  override def asSlave(): Unit = {
    super.asSlave()

    write := False
    write.allowOverride
    wdata.assignDontCare()
    wdata.allowOverride
  }
}

trait CsrService {
  def registerCsr[T <: Csr](id: Int, reg: => T): T
  def getCsr(id: Int): CsrIo
}

class IrqIo extends Bundle with IMasterSlave {
  val update = Bool()
  val interruptPending = Bool()

  def init(): Unit = {
    if (isMasterInterface) {
      update := False
      interruptPending := False
    }
  }

  def postInterrupt(): Unit = {
    assert(isMasterInterface)

    update := True
    interruptPending := True
  }

  def clearInterrupt(): Unit = {
    assert(isMasterInterface)

    update := True
    interruptPending := False
  }

  override def asMaster(): Unit = {
    out(update, interruptPending)
  }
}

trait InterruptService {
  def getMachineTimerIrqIo: IrqIo
  def getExternalIrqIo: IrqIo
}

case class DataHazardInfo[T <: Data](registerType: SpinalEnumElement[RegisterType.type],
                                     rs1Data: PipelineData[T],
                                     rs2Data: PipelineData[T],
                                     rdData: PipelineData[T]
                                    )

trait DataHazardService {
  def addHazard[T <: Data](info: DataHazardInfo[T]): Unit

  /**
   * Stalls pipeline stages until the given hazard is resolved. More
   * specifically, conflicts() is called for each stage. Its first argument
   * is the stage itself, the second one is a sequence of all stages holding
   * non-retired earlier instructions. When there is a potential conflict (e.g.,
   * the stage reads a register that will be written by one of the later stages)
   * conflicts() should return True and the stage will be stalled.
   *
   * Note: this function should be called from Plugin.finish().
   */
  def resolveHazard(conflicts: (Stage, Seq[Stage]) => Bool)
}

trait FormalService {
  def lsuDefault(stage: Stage)
  def lsuOnLoad(stage: Stage, addr: UInt, rmask: Bits, rdata: UInt)
  def lsuOnStore(stage: Stage, addr: UInt, wmask: Bits, wdata: UInt)
  def lsuOnMisaligned(stage: Stage): Unit
}
