package spinal

import scala.collection.immutable.Range
import scala.language.experimental.macros

package object core extends BaseTypeFactory with BaseTypeCast {

  import languageFeature.implicitConversions

  implicit lazy val implicitConversions = scala.language.implicitConversions
  implicit lazy val reflectiveCalls = scala.language.reflectiveCalls
  implicit lazy val postfixOps = scala.language.postfixOps

  implicit def IntToBuilder(value: Int) : IntBuilder = new IntBuilder(value)

  implicit def BigIntToBuilder(value: BigInt) : BigIntBuilder = new BigIntBuilder(value)

  implicit def DoubleToBuilder(value: Double) : DoubleBuilder = new DoubleBuilder(value)

  def enum(param: Symbol*): Any = macro MacroTest.enum_impl
  //def enum(param: Symbol*) = MacroTest.enum(param)

  implicit def EnumElementToCraft[T <: SpinalEnum](element: SpinalEnumElement[T]): SpinalEnumCraft[T] = element()
  //  implicit def EnumElementToCraft[T <: SpinalEnum](enumDef : T) : SpinalEnumCraft[T] = enumDef.craft().asInstanceOf[SpinalEnumCraft[T]]
  //  implicit def EnumElementToCraft2[T <: SpinalEnum](enumDef : SpinalEnumElement[T]) : SpinalEnumCraft[T] = enumDef.craft().asInstanceOf[SpinalEnumCraft[T]]

  class IntBuilder(val i: Int) extends AnyVal {
    //    def x[T <: Data](dataType : T) : Vec[T] = Vec(dataType,i)

    def downto(start: Int): Range.Inclusive = Range.inclusive(start, i)

    def bit = new BitCount(i)
    def exp = new ExpCount(i)
    def hr = new STime(i * 3600)
    def min = new STime(i * 60)
    def sec = new STime(i * 1)
    def ms = new STime(i * 1e-3)
    def us = new STime(i * 1e-6)
    def ns = new STime(i * 1e-9)
    def ps = new STime(i * 1e-12)
    def fs = new STime(i * 1e-15)
  }

  case class BigIntBuilder(i: BigInt) {
    def bit = new BitCount(i.toInt)
    def exp = new ExpCount(i.toInt)
  }

  case class DoubleBuilder(d: Double) {
    def hr = new STime(d * 3600)
    def min = new STime(d * 60)
    def sec = new STime(d * 1)
    def ms = new STime(d * 1e-3)
    def us = new STime(d * 1e-6)
    def ns = new STime(d * 1e-9)
    def ps = new STime(d * 1e-12)
    def fs = new STime(d * 1e-15)
  }

  def True = Bool(true) //Should be def, not val, else it will create cross hierarchy usage of the same instance
  def False = Bool(false)

  // implicit def RegRefToReg[T <: Data](that : RegRef[T]) : T = that.getReg
  implicit def IntToUInt(that: Int) : UInt = U(that)
  implicit def BigIntToUInt(that: BigInt) : UInt = U(that)
  implicit def IntToSInt(that: Int) : SInt = S(that)
  implicit def BigIntToSInt(that: BigInt) : SInt = S(that)
  implicit def IntToBits(that: Int) : Bits = B(that)
  implicit def BigIntToBits(that: BigInt) : Bits = B(that)
  implicit def StringToBits(that: String) : Bits = bitVectorStringParser(spinal.core.B, that, signed = false)
  implicit def StringToUInt(that: String) : UInt = bitVectorStringParser(spinal.core.U, that, signed = false)
  implicit def StringToSInt(that: String) : SInt = bitVectorStringParser(spinal.core.S, that, signed = true)

  implicit class LiteralBuilder(private val sc: StringContext) extends AnyVal {
    def B(args: Any*): Bits = bitVectorStringParser(spinal.core.B, getString(args), signed = false)
    def U(args: Any*): UInt = bitVectorStringParser(spinal.core.U, getString(args), signed = false)
    def S(args: Any*): SInt = bitVectorStringParser(spinal.core.S, getString(args), signed = true)
    def M(args: Any*): MaskedLiteral = MaskedLiteral(sc.parts(0))
    def Bits(args: Any*): Bits = B(args)
    def UInt(args: Any*): UInt = U(args)
    def SInt(args: Any*): SInt = S(args)

    private def getString(args: Any*): String = {
      // println(sc.parts.size + " " + args.size)
      // println(sc.parts.head + "-----" + args.head)
      // sc.standardInterpolator(_.toString(), args)

      val pi = sc.parts.iterator
      val ai = args.iterator
      val bldr = new StringBuilder(pi.next().toString)

      while (ai.hasNext) {
        if (ai.hasNext && !ai.next.isInstanceOf[List[_]]) bldr append ai.next
        if (pi.hasNext && !pi.next.isInstanceOf[List[_]]) bldr append pi.next
      }

      //println(bldr.result)
      bldr.result.replace("_", "")
    }
  }

  private[core] def bitVectorStringParser[T <: BitVector](builder: BitVectorLiteralFactory[T], arg: String, signed: Boolean): T = {
    def error() = SpinalError(s"$arg literal is not well formed [bitCount'][radix]value")

    def strBinToInt(valueStr: String, radix: Int, bitCount: Int) = if (!signed) {
      BigInt(valueStr, radix)
    } else {
      val v = BigInt(valueStr, radix)
      val bitCountPow2 = BigInt(1) << bitCount
      if (v >= bitCountPow2) SpinalError("Value is bigger than bit count")
      if (!v.testBit(bitCount - 1)) v else -bitCountPow2 + v
    }

    var str = arg.replace("_", "").toLowerCase
    if (str == "") return builder(0, 0 bit)

    var bitCount = -1

    if (str.contains(''')) {
      val split = str.split(''')
      bitCount = split(0).toInt
      str = split(1)
    }

    var radix = -1

    if ("01".contains(str.charAt(0))) {
      radix = 2
    } else {
      radix = str.charAt(0) match {
        case 'x' => 16
        case 'h' => 16
        case 'd' => 10
        case 'o' => 8
        case 'b' => 2
        case c => SpinalError(s"$c is not a valid radix specification. x-d-o-b are allowed")
      }
      str = str.tail
    }

    val minus = if (str.charAt(0) == '-') {
      str = str.tail
      if (radix != 10) SpinalError("Can't have minus on non decimal values")
      true
    } else {
      false
    }

    val digitCount = str.length
    if (bitCount == -1) bitCount = radix match {
      case 16 => digitCount * 4
      case 8 => digitCount * 3
      case 2 => digitCount
      case _ => -1
    }

    val value = radix match {
      case 10 => if (minus) -BigInt(str, radix) else BigInt(str, radix)
      case _ => strBinToInt(str, radix, bitCount)
    }

    if (bitCount == -1) {
      builder(value)
    } else {
      builder(value, bitCount bit)
    }
  }

  implicit def DataPimped[T <: Data](that: T) : DataPimper[T] = new DataPimper(that)
  implicit def BitVectorPimped[T <: BitVector](that: T)  : BitVectorPimper[T] = new BitVectorPimper(that)

  // @TODO Should those be removed ?
  // implicit def UIntToLitBuilder(sc: StringContext) = new UIntLitBuilder(sc)
  // implicit def IntToUInt(that : Int) = UInt(that lit)
  // implicit def BigIntToUInt(that : BigInt) = UInt(that lit)
  // implicit def BooleanToBool(that : Boolean) = Bool(that)
  // implicit def autoCast[T <: Data, T2 <: T](that: T): T2#SSelf = that.asInstanceOf[T2#SSelf]
  // implicit def autoCast[T <: Data](that: T): T#SSelf = that.asInstanceOf[T#SSelf]

  implicit class SIntPimper(pimped: SInt) {
    def toSFix: SFix = {
      val width = pimped.getWidth
      val fix = SFix(width - 1 exp, width bit)
      fix.raw := pimped
      fix
    }
  }

  implicit class UIntPimper(pimped: UInt) {
    def toUFix: UFix = {
      val width = pimped.getWidth
      val fix = UFix(width exp, width bit)
      fix.raw := pimped
      fix
    }
  }
}