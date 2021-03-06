package spinal.lib.com.uart

import UartParityType._
import UartStopType._
import spinal.core._
import spinal.lib.FragmentToBitsStates._
import spinal.lib._

case class UartCtrlConfig(dataWidthMax: Int = 8) extends Bundle {
  val dataLength = UInt(log2Up(dataWidthMax) bit)
  val stop = UartStopType()
  val parity = UartParityType()
}

class UartCtrlIo(dataWidthMax: Int = 8, clockDividerWidth: Int = 20) extends Bundle {
  val config = in(UartCtrlConfig(dataWidthMax))
  val clockDivider = in UInt (clockDividerWidth bit)
  val write = slave Stream (Bits(dataWidthMax bit))
  val read = master Flow (Bits(dataWidthMax bit))
  val uart = master(Uart())
}

class UartCtrl(dataWidthMax: Int = 8, clockDividerWidth: Int = 20, preSamplingSize: Int = 1, samplingSize: Int = 5, postSamplingSize: Int = 2) extends Component {
  val io = new UartCtrlIo(dataWidthMax)
  val txToRxClockDividerFactor = preSamplingSize + samplingSize + postSamplingSize
  assert(isPow2(txToRxClockDividerFactor))

  val tx = new UartCtrlTx(dataWidthMax, clockDividerWidth + log2Up(txToRxClockDividerFactor))
  val rx = new UartCtrlRx(dataWidthMax, clockDividerWidth, preSamplingSize, samplingSize, postSamplingSize)

  tx.io.config := io.config
  rx.io.config := io.config

  tx.io.clockDivider := (io.clockDivider ## BitsSet(log2Up(txToRxClockDividerFactor) bit)).asUInt
  rx.io.clockDivider := io.clockDivider

  tx.io.write << io.write
  rx.io.read >> io.read

  io.uart.txd := tx.io.txd
  rx.io.rxd := io.uart.rxd
}

object UartCtrlTxState extends SpinalEnum {
  val sIdle, sStart, sData, sParity, sStop = newElement()
}

class UartCtrlTx(dataWidthMax: Int = 8, clockDividerWidth: Int = 24) extends Component {
  val io = new Bundle {
    val config = in(new UartCtrlConfig(dataWidthMax))
    val clockDivider = in UInt (clockDividerWidth bit)
    val write = slave Stream (Bits(dataWidthMax bit))
    val txd = out Bool
  }

  val timer = new Area {
    val counter = Reg(io.clockDivider)
    val reset = Bool
    val tick = counter === 0

    counter := counter - 1
    when(tick || reset) {
      counter := io.clockDivider
    }
  }

  val tickCounter = new Area {
    val value = Reg(UInt(Math.max(dataWidthMax, 2) bit))
    val reset = False

    when(timer.tick) {
      value := value + 1
    }
    when(reset) {
      value := 0
    }
  }

  val stateMachine = new Area {

    import UartCtrlTxState._

    val state = RegInit(sIdle())
    val paritySum = Reg(Bool)
    val dataBuffer = Reg(io.write.payload)

    val lookingForJob = Bool
    val txd = Bool

    when(timer.tick) {
      paritySum := paritySum ^ txd
    }

    lookingForJob := False
    timer.reset := False
    txd := True
    switch(state) {
      is(sIdle) {
        lookingForJob := True
      }
      is(sStart) {
        txd := False
        when(timer.tick) {
          state := sData
          paritySum := io.config.parity === eParityOdd
          tickCounter.reset := True
        }
      }
      is(sData) {
        txd := dataBuffer(tickCounter.value)
        when(timer.tick) {
          when(tickCounter.value === io.config.dataLength) {
            tickCounter.reset := True
            when(io.config.parity === eParityNone) {
              state := sStop
            } otherwise {
              state := sParity
            }
          }
        }
      }
      is(sParity) {
        txd := paritySum
        when(timer.tick) {
          state := sStop
          tickCounter.reset := True
        }
      }
      is(sStop) {
        when(timer.tick) {
          when(tickCounter.value === toBitCount(io.config.stop)) {
            state := sIdle
            lookingForJob := True
          }
        }
      }
    }

    io.write.ready := False
    when(lookingForJob && io.write.valid) {
      io.write.ready := True
      timer.reset := True
      dataBuffer := io.write.payload
      state := sStart
    }
  }

  io.txd := RegNext(stateMachine.txd, True)

}

object UartCtrlRxState extends SpinalEnum {
  val sIdle, sStart, sData, sParity, sStop = newElement()
}

class UartCtrlRx(dataWidthMax: Int = 8, clockDividerWidth: Int = 21, preSamplingSize: Int = 1, samplingSize: Int = 5, postSamplingSize: Int = 2) extends Component {
  if ((samplingSize & 1) != 1)
    SpinalWarning(s"It's not nice to have a even samplingSize value at ${ScalaLocated.getScalaTraceSmart}")

  val io = new Bundle {
    val config = in(new UartCtrlConfig(dataWidthMax))
    val clockDivider = in UInt (clockDividerWidth bit)
    val read = master Flow (Bits(dataWidthMax bit))
    val rxd = in Bool
  }

  val clockDivider = new Area {
    val counter = RegInit(U(0, clockDividerWidth bit))
    val tick = counter === 0

    counter := counter - 1
    when(tick) {
      counter := io.clockDivider
    }
  }

  val sampler = new Area {
    val frontBuffer = BufferCC(io.rxd)
    val samples = RegInit(BitsSet(samplingSize bit))
    when(clockDivider.tick) {
      samples :<= samples ## frontBuffer
    }
    val value = RegNext(MajorityVote(samples))
    val event = RegNext(clockDivider.tick)
  }

  val baud = new Area {
    val counter = Reg(UInt(log2Up(preSamplingSize + samplingSize + postSamplingSize) bit))
    val tick = Bool

    tick := False
    when(sampler.event) {
      counter := counter - 1
      when(counter === 0) {
        tick := True
        counter := U(preSamplingSize + samplingSize + postSamplingSize - 1)
      }
    }

    def reset: Unit = counter := U(preSamplingSize + (samplingSize - 1) / 2 - 1)
    def value = sampler.value
  }

  //      that.size - that.size/2 - 1


  val baudCounter = new Area {
    val value = Reg(UInt(Math.max(dataWidthMax, 2) bit))
    def reset: Unit = value := 0

    when(baud.tick) {
      value := value + 1
    }
  }

  val stateMachine = new Area {

    import UartCtrlRxState._

    //implicit def valueToCraft[T <: SpinalEnum](element: SpinalEnumElement[T])= element.craft()

    val state = RegInit(sIdle())
    val paritySum = Reg(Bool)
    val dataBuffer = Reg(io.read.payload)

    when(baud.tick) {
      paritySum := paritySum ^ baud.value
    }

    io.read.valid := False
    switch(state) {
      is(sIdle) {
        when(sampler.value === False) {
          state := sStart
          baud.reset
        }
      }
      is(sStart) {
        when(baud.tick) {
          state := sData
          baudCounter.reset
          paritySum := io.config.parity === eParityOdd
          when(baud.value === True) {
            state := sIdle
          }
        }
      }
      is(sData) {
        when(baud.tick) {
          dataBuffer(baudCounter.value) := baud.value
          when(baudCounter.value === io.config.dataLength) {
            baudCounter.reset
            when(io.config.parity === eParityNone) {
              state := sStop
            } otherwise {
              state := sParity
            }
          }
        }
      }
      is(sParity) {
        when(baud.tick) {
          state := sStop
          baudCounter.reset
          when(paritySum =/= baud.value) {
            state := sIdle
          }
        }
      }
      is(sStop) {
        when(baud.tick) {
          when(!baud.value) {
            state := sIdle
          }.elsewhen(baudCounter.value === toBitCount(io.config.stop)) {
            state := sIdle
            io.read.valid := True
          }
        }
      }
    }
  }
  io.read.payload := stateMachine.dataBuffer
}