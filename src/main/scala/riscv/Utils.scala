package riscv

import spinal.core._

object Utils {
  def signExtend[T <: BitVector](data: T, width: Int): T = {
    val dataWidth = data.getBitsWidth
    assert(dataWidth <= width && dataWidth > 0)

    (B((width - 1 - dataWidth downto 0) -> data(dataWidth - 1)) ## data).as(data.clone().setWidth(width))
  }

  def zeroExtend[T <: BitVector](data: T, width: Int): T = {
    val dataWidth = data.getBitsWidth
    assert(dataWidth <= width && dataWidth > 0)

    (B((width - 1 - dataWidth downto 0) -> False) ## data).as(data.clone().setWidth(width))
  }

  def twosComplement(data: UInt): UInt = ~data + 1
}