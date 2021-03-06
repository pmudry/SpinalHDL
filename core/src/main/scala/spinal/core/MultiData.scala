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

object MultiData {
  // var tab = 0
}
abstract class MultiData extends Data {



  def elements: ArrayBuffer[(String, Data)]


  override def addTag(spinalTag: SpinalTag): this.type = {
    super.addTag(spinalTag)
    elements.foreach(_._2.addTag(spinalTag))
    this
  }

  def find(name: String): Data = {
    val temp = elements.find((tuple) => tuple._1 == name).getOrElse(null)
    if (temp == null) return null
    temp._2
  }

  override def asBits: Bits = {
    var ret: Bits = null
    for ((eName, e) <- elements) {
      if (ret == null.asInstanceOf[Object]) ret = e.asBits
      else ret = e.asBits ## ret
    }
    if (ret.asInstanceOf[Object] == null) ret = Bits(0 bit)
    ret
  }

  protected override def nameChangeEvent(weak: Boolean): Unit = {
    super.nameChangeEvent(weak)
    for ((eName, e) <- elements) e match {
      case nameable: Nameable => {
        if (eName == "")
          nameable.setName(getName(), weak)
        else if(getName() == "")
          nameable.setName(eName, weak)
        else
          nameable.setName(getName() + "_" + eName, weak)
      }
    }
  }


  override def getBitsWidth: Int = {
    var accumulateWidth = 0
    for ((_, e) <- elements) {
      val width = e.getBitsWidth
      if (width == -1) SpinalError("Can't return bits width")
      accumulateWidth += width
    }
    accumulateWidth
  }

  override def asInput(): this.type = {
    super.asInput()
    elements.foreach(_._2.asInput());

    this
  }


  override def asOutput(): this.type = {
    super.asOutput()
    elements.foreach(_._2.asOutput());
    this
  }


  override def flatten: Seq[BaseType] = {
    elements.map(_._2.flatten).foldLeft(List[BaseType]())(_ ++ _)
  }
  override def flattenLocalName: Seq[String] = {
    val result = ArrayBuffer[String]()
    for((localName,e) <- elements){
      result ++= e.flattenLocalName.map(name => if(name == "") localName else localName + "_" + name)
    }
    result // for fun elements.map{case (localName,e) => e.flattenLocalName.map(name => if(name == "") localName else localName + "_" + name)}.reduce(_ ++: _)
  }


  // = (this.flatten, that.flatten).zipped.map((a, b) => a.isNotEguals(b)).reduceLeft(_ || _)

  override def assignFromBits(bits: Bits): Unit = {
    var offset = 0
    for ((_, e) <- elements) {
      val width = e.getBitsWidth
      e.assignFromBits(bits(offset, width bit))
      offset = offset + width
    }
  }

  override def assignFromBits(bits: Bits,hi : Int,lo : Int): Unit = {
    var offset = 0
    var bitsOffset = 0
    for ((_, e) <- elements) {
      val width = e.getBitsWidth
      if (hi >= offset && lo < offset + width) {
        val high = Math.min(hi-offset,width-1)
        val low = Math.max(lo-offset,0)
        val bitUsage = high - low + 1
        e.assignFromBits(bits(bitsOffset,bitUsage bit), high,low)
        bitsOffset += bitUsage
      }
      offset = offset + width
    }

  }

  private[core] def isEguals(that: Any): Bool = {
    that match {
      case that: MultiData => {
        zippedMap(that, _ === _).reduce(_ && _)
      }
      case _ => SpinalError("Can't do that")
    }
  }


  private[core] def isNotEguals(that: Any): Bool = {
    that match {
      case that: MultiData => {
        zippedMap(that, _ =/= _).reduce(_ || _)
      }
      case _ => SpinalError("Can't do that")
    }
  }

  private[core] override def autoConnect(that: Data): Unit = {
    that match {
      case that: MultiData => {
        zippedMap(that, _ autoConnect _)
      }
      case _ => SpinalError("Can't do that")
    }
  }

  private[core] def zippedMap[T](that: MultiData, task: (Data, Data) => T): Seq[T] = {
    if (that.elements.length != this.elements.length) SpinalError("Can't do that")
    this.elements.map(x => {
      val (n, e) = x
      val other = that.find(n)
      if (e == null) SpinalError("Can't do that")
      task(e, other)
    })
  }
  override def getZero: this.type = {
    val ret = clone()
    ret.elements.foreach(e => {
      e._2 := e._2.getZero
    })
    ret
  }
  override def flip(): this.type  = {
    for ((_,e) <- elements) {
      e.flip()
    }
    dir match {
      case `in` => dir = out
      case `out` => dir = in
      case _ =>
    }
    this
  }
}
