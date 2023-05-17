package riscv

import spinal.core.{ClockDomain, ClockDomainTag, ClockingArea, Component, SpinalVerilog}
import spinal.lib.bus.amba4.axi.Axi4SpecRenamer
import spinal.lib.master

class CoreAxi extends Component {
  setDefinitionName("TGCX")
  val coreClockDomain = ClockDomain(
    clock = clockDomain.clock,
    reset = clockDomain.reset
  )

  val core = new ClockingArea(coreClockDomain) {
    implicit val config = new Config(BaseIsa.RV32I)
    val pipeline = createDynamicPipeline(build = true)(config)

    val memService = pipeline.service[MemoryService]
    val ibus = memService.getExternalIBus
    val dbus = memService.getExternalDBus
    val interruptService = pipeline.serviceOption[InterruptService].get
  }

  //val ibusAxi = core.ibus.toAxi4ReadOnly().setName("ibus").asMaster()
  Axi4SpecRenamer(master(core.ibus.toAxi4ReadOnly()).setName("iBusAxi").addTag(ClockDomainTag(ClockDomain.current)))
  //val dbusAxi = core.dbus.toAxi4Shared().toAxi4().setName("dbus").asMaster()
  Axi4SpecRenamer(master(core.dbus.toAxi4Shared().toAxi4()).setName("dBusAxi").addTag(ClockDomainTag(ClockDomain.current)))
  val external_update = core.interruptService.getExternalIrqIo.update toIo()
  val external_interruptPending = core.interruptService.getExternalIrqIo.interruptPending toIo()
  val mtimer_update = core.interruptService.getMachineTimerIrqIo.update toIo()
  val mtimer_interruptPending = core.interruptService.getMachineTimerIrqIo.interruptPending toIo()
}

object CoreAxi {
  def main(args: Array[String]) {
    SpinalVerilog(new CoreAxi)
  }
}
