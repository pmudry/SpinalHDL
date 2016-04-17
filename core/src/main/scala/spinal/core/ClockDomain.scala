/*
 * SpinalHDL
 * Copyright (c) Dolu, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */

package spinal.core

import scala.collection.mutable.ArrayBuffer

/**
  * Created by PIC18F on 21.08.2014.
  */

trait EdgeKind

object RISING extends EdgeKind

object FALLING extends EdgeKind

trait ResetKind

object ASYNC extends ResetKind

object SYNC extends ResetKind

//trait ClockDomainBoolFunction
//object FUNCTION_CLOCK extends ClockDomainBoolFunction
//object FUNCTION_RESET extends ClockDomainBoolFunction
//object FUNCTION_ENABLE extends ClockDomainBoolFunction
//object FUNCTION_NONE extends ClockDomainBoolFunction

// Default configuration of clock domain is :
// Rising edge clock with optional asyncronous reset active high and optional active high clockEnable
case class ClockDomainConfig(clockEdge: EdgeKind = RISING, resetKind: ResetKind = ASYNC, resetActiveHigh: Boolean = true, clockEnableActiveHigh: Boolean = true) {

}

//To use when you want to define a new clock domain by using internal signals
object ClockDomain {
  def apply(clock: Bool, reset: Bool = null, clockEnable: Bool = null, frequency: IClockDomainFrequency = UnknownFrequency(),config: ClockDomainConfig = GlobalData.get.commonClockConfig): ClockDomain = {
    new ClockDomain(config, clock, reset, clockEnable, frequency)
  }

//  def apply(clock: Bool, reset: Bool, clockEnable: Bool): ClockDomain = {
//    new ClockDomain(GlobalData.get.commonClockConfig, clock, reset, clockEnable)
//  }
//
//  def apply(clock: Bool, reset: Bool): ClockDomain = {
//    new ClockDomain(GlobalData.get.commonClockConfig, clock, reset, null)
//  }
//
//  def apply(clock: Bool): ClockDomain = {
//    new ClockDomain(GlobalData.get.commonClockConfig, clock, null, null)
//  }

  // To use when you want to define a new ClockDomain that thank signals outside the toplevel.
  // (it create input clock, reset, clockenable in the top level)
  def external(name: String,config: ClockDomainConfig = GlobalData.get.commonClockConfig,withReset : Boolean = true,withClockEnable : Boolean = false,frequency: IClockDomainFrequency = UnknownFrequency()): ClockDomain = {
    Component.push(null)
    val clock = Bool()
    clock.setName(if (name != "") name + "_clk" else "clk")

    var reset: Bool = null
    if (withReset) {
      reset = Bool()
      reset.setName((if (name != "") name + "_reset" else "reset") + (if (config.resetActiveHigh) "" else "N"))
    }

    var clockEnable: Bool = null
    if (withClockEnable) {
      clockEnable = Bool()
      clockEnable.setName((if (name != "") name + "_clkEn" else "clkEn") + (if (config.resetActiveHigh) "" else "N"))
    }

    val clockDomain = ClockDomain(clock, reset, clockEnable, frequency,config)
    Component.pop(null)
    clockDomain
  }

//  def apply(name: String, frequency: IClockDomainFrequency): ClockDomain = ClockDomain(name,
//            GlobalData.get.commonClockConfig, true, false, frequency)
//
//  def apply(name: String): ClockDomain = ClockDomain(name, GlobalData.get.commonClockConfig, true, false, UnknownFrequency())
//

  def push(c: ClockDomain): Unit = {
    GlobalData.get.clockDomainStack.push(c)
  }

  def pop(c: ClockDomain): Unit = {
    GlobalData.get.clockDomainStack.pop(c)
  }

  def current = GlobalData.get.clockDomainStack.head()

  def isResetActive = current.isResetActive

  def isClockEnableActive = current.isClockEnableActive

  def readClockWire = current.readClockWire

  def readResetWire = current.readResetWire

  def readClockEnableWire = current.readClockEnableWire

  def getClockDomainDriver(that: Bool): Bool = {
    if (that.spinalTags.exists(_.isInstanceOf[ClockDomainBoolTag])) {
      that
    } else {
      that.inputs(0) match {
        case input: Bool => getClockDomainDriver(input)
        case _ => null
      }
    }
  }

  def getClockDomainTag(that: Bool): ClockDomainBoolTag = {
    val driver = getClockDomainDriver(that)
    if (driver == null) {
      null
    } else {
      driver.spinalTags.find(_.isInstanceOf[ClockDomainBoolTag]).get.asInstanceOf[ClockDomainBoolTag]
    }
  }
}

case class ClockDomainTag(clockDomain: ClockDomain) extends SpinalTag

trait ClockDomainBoolTag extends SpinalTag
case class ClockTag(clockDomain: ClockDomain) extends ClockDomainBoolTag
case class ResetTag(clockDomain: ClockDomain) extends ClockDomainBoolTag
case class ClockEnableTag(clockDomain: ClockDomain) extends ClockDomainBoolTag

class ClockDomain(val config: ClockDomainConfig, val clock: Bool, val reset: Bool = null, val clockEnable: Bool = null, val frequency: IClockDomainFrequency = UnknownFrequency()) {

  clock.addTag(ClockTag(this))
  if (reset != null) reset.addTag(ResetTag(this))
  if (clockEnable != null) clockEnable.addTag(ClockEnableTag(this))


  def hasClockEnable = clockEnable != null
  def hasReset = reset != null
  def push() : Unit = ClockDomain.push(this)
  def pop(): Unit = ClockDomain.pop(this)
  def isResetActive = if (config.resetActiveHigh) readResetWire else !readResetWire
  def isClockEnableActive = if (config.clockEnableActiveHigh) readClockEnableWire else !readClockEnableWire
  def readClockWire = if (null == clock) Bool(config.clockEdge == FALLING) else Data.doPull(clock, Component.current, true, true)
  def readResetWire = if (null == reset) Bool(!config.resetActiveHigh) else Data.doPull(reset, Component.current, true, true)
  def readClockEnableWire = if (null == clockEnable) Bool(config.clockEnableActiveHigh) else Data.doPull(clockEnable, Component.current, true, true)

  val syncroneWith = ArrayBuffer[ClockDomain]()

  def isSyncronousWith(that: ClockDomain): Boolean = {
    if (this == that) return true
    if (this.clock == that.clock) return true
    if (syncroneWith.contains(that)) return true
    return false
  }

  def setSyncronousWith(that: ClockDomain) = {
    this.syncroneWith += that
    that.syncroneWith += this
  }

  def apply(block: => Unit): Unit = {
    push
    block
    pop
  }

  def apply[T <: Data](block: => T): T = {
    push
    val ret: T = block
    pop
    ret
  }


  def clone(config: ClockDomainConfig = config, clock: Bool = clock, reset: Bool = reset, clockEnable: Bool = clockEnable): ClockDomain = new ClockDomain(config, clock, reset, clockEnable, frequency)
}

case class UnknownFrequency() extends IClockDomainFrequency {
  def getValue: Double = ???
  def getMax: Double = ???
  def getMin: Double = ???
}

case class FixedFrequency(value: Double) extends IClockDomainFrequency {
  def getValue: Double = value
  def getMax: Double = value
  def getMin: Double = value
}

trait IClockDomainFrequency {
  def getValue: Double
  def getMax: Double
  def getMin: Double
}