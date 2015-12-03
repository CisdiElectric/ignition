package com.ignition

import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.xml.Elem

import org.json4s.JValue

/**
 * A wrapper around an array that lazily initialies an element of the array when it is accessed
 * the first time.
 * @param the type of array elements.
 * @param builder a function that is called when the element with a given index does not yet exist.
 * @param length the size of the array.
 */
private[ignition] class LazyArray[A: ClassTag](builder: Int => A)(val length: Int)
  extends IndexedSeq[A] with Serializable {

  private val buffer = Array.ofDim[A](length)

  def apply(idx: Int): A = Option(buffer(idx)) getOrElse {
    buffer(idx) = builder(idx)
    buffer(idx)
  }
}

/**
 * Provides base step functionality and controls serializability of the steps.
 */
abstract class AbstractStep extends Serializable {
  /**
   * Wraps exceptions into ExecutionException instances.
   */
  final protected def wrap[U](body: => U): U = try { body } catch {
    case e: ExecutionException => throw e
    case NonFatal(e) => throw ExecutionException("Step computation failed", e)
  }

  /**
   * Serialization helper. Enables or disables step serialization based on the environment property.
   */
  private def writeObject(out: java.io.ObjectOutputStream): Unit =
    if (System.getProperty(STEPS_SERIALIZABLE) == false.toString)
      throw new java.io.NotSerializableException("Steps should not be serialized")
    else
      out.defaultWriteObject
}

/**
 * A workflow step. It can have an arbitrary number of inputs and outputs, each of which
 * could be connected to inputs and outputs of other steps.
 * @param T the type encapsulating the data that is passed between steps.
 * @param R the type of the runtime context passed to the node for evaluation.
 */
trait Step[T, R] extends AbstractStep with XmlExport with JsonExport {

  /**
   * The maximum number of input ports.
   */
  def inputCount: Int

  /**
   * The number of output ports.
   */
  def outputCount: Int

  /**
   * Specifies if the step should throw an error if one of the inputs is not connected.
   */
  def allInputsRequired = true

  /**
   * Computes a step output value at the specified index. This method is invoked from output()
   * and can safely throw any exception, which will be wrapped into ExecutionException.
   * @param index the output value index.
   * @param preview if true, it indicates the preview mode. The implementation should make use of
   * this parameter to return a limited or simplified version of the output.
   */
  protected def compute(index: Int, preview: Boolean)(implicit runtime: R): T

  /**
   * Computes a step output value at the specified index.
   * @param index the output value index.
   * @param preview if true, it indicates the preview mode. The implementation should make use of
   * this parameter to return a limited or simplified version of the output.
   * @throws ExecutionException in case of an error, or if the step is not connected.
   */
  @throws(classOf[ExecutionException])
  final def output(index: Int, preview: Boolean)(implicit runtime: R): T = wrap {
    assert(0 until outputCount contains index, s"Output index out of range: $index of $outputCount")
    compute(index, preview)
  }

  /**
   * Shortcut for `output(0, preview)`. Computes a step output at index 0.
   * @throws ExecutionException in case of an error, or if the step is not connected.
   */
  @throws(classOf[ExecutionException])
  final def output(preview: Boolean)(implicit runtime: R): T = output(0, preview)

  /**
   * Shortcut for `output(0, false)`. Computes a step output at index 0 with preview mode OFF.
   * @throws ExecutionException in case of an error, or if the step is not connected.
   */
  @throws(classOf[ExecutionException])
  final def output(implicit runtime: R): T = output(false)
}

/**
 * Something TO which a connection can be made, a connection target.
 */
trait ConnectionTarget[T, R] {
  def step: Step[T, R]
  def index: Int

  var inbound: ConnectionSource[T, R] = null
  def from(src: ConnectionSource[T, R]): this.type = { this.inbound = src; this }
}

/**
 * Something FROM which a connection can be made
 */
trait ConnectionSource[T, R] {
  def step: Step[T, R]
  def index: Int

  def to(tgt: ConnectionTarget[T, R]): tgt.type = tgt.from(this)
  def -->(tgt: ConnectionTarget[T, R]): tgt.type = to(tgt)

  def to(tgt: MultiInputStep[T, R]): tgt.type = { to(tgt.in(0)); tgt }
  def -->(tgt: MultiInputStep[T, R]): tgt.type = to(tgt)

  def to(targets: ConnectionTarget[T, R]*): Unit = targets foreach (to(_))
  def -->(targets: ConnectionTarget[T, R]*): Unit = to(targets: _*)

  def value(preview: Boolean)(implicit runtime: R): T
}

/* inputs */
trait NoInputStep[T, R] extends Step[T, R] {
  val inputCount = 0
}

trait SingleInputStep[T, R] extends Step[T, R] with ConnectionTarget[T, R] {
  val step = this
  val index = 0
  val inputCount = 1
  def input(preview: Boolean)(implicit runtime: R) = {
    if (inbound == null && allInputsRequired) throw ExecutionException("Input is not connected")
    Option(inbound) map (_.value(preview)) getOrElse null.asInstanceOf[T]
  }
}

trait MultiInputStep[T, R] extends Step[T, R] { self =>
  val in = new LazyArray[InPort](idx => InPort(idx))(inputCount)

  def inputs(preview: Boolean)(implicit runtime: R) = in.map { p =>
    for {
      port <- Option(p)
      ib <- Option(port.inbound)
    } yield ib.value(preview)
  }.zipWithIndex map {
    case (None, idx) if allInputsRequired => throw ExecutionException(s"Input$idx is not connected")
    case (x, _) => x getOrElse null.asInstanceOf[T]
  }

  case class InPort(index: Int) extends ConnectionTarget[T, R] {
    val step = self
    override def toString = s"$step.in($index)"
  }
}

/* outputs */
trait SingleOutputStep[T, R] extends Step[T, R] with ConnectionSource[T, R] {
  val step = this
  val index = 0
  val outputCount = 1
  def outbounds(index: Int) = { assert(index == 0); this }
  def value(preview: Boolean)(implicit runtime: R): T = output(0, preview)
}

trait MultiOutputStep[T, R] extends Step[T, R] { self =>
  val out = new LazyArray[OutPort](idx => OutPort(idx))(outputCount)

  def to(tgt: ConnectionTarget[T, R]): tgt.type = out(0).to(tgt)
  def -->(tgt: ConnectionTarget[T, R]): tgt.type = to(tgt)

  def to(tgt: MultiInputStep[T, R]): tgt.type = { to(tgt.in(0)); tgt }
  def -->(tgt: MultiInputStep[T, R]): tgt.type = to(tgt)

  def to(targets: ConnectionTarget[T, R]*): Unit = targets.zipWithIndex foreach {
    case (tgt, outIndex) => out(outIndex) to tgt
  }
  def -->(targets: ConnectionTarget[T, R]*): Unit = to(targets: _*)

  def outbounds(index: Int) = out(index)

  case class OutPort(index: Int) extends ConnectionSource[T, R] {
    val step = self
    def value(preview: Boolean)(implicit runtime: R) = self.output(index, preview)
    override def toString = s"$step.out($index)"
  }
}

/* templates */
abstract class Producer[T, R] extends SingleOutputStep[T, R] with NoInputStep[T, R] {
  protected def compute(index: Int, preview: Boolean)(implicit runtime: R): T =
    compute(preview)
  protected def compute(preview: Boolean)(implicit runtime: R): T
}

abstract class Transformer[T, R] extends SingleOutputStep[T, R] with SingleInputStep[T, R] {
  override val step = this
  override val index = 0
  protected def compute(index: Int, preview: Boolean)(implicit runtime: R): T =
    compute(input(preview), preview)
  protected def compute(arg: T, preview: Boolean)(implicit runtime: R): T
}

abstract class Splitter[T, R] extends SingleInputStep[T, R] with MultiOutputStep[T, R] {
  protected def compute(index: Int, preview: Boolean)(implicit runtime: R): T =
    compute(input(preview), index, preview)
  protected def compute(arg: T, index: Int, preview: Boolean)(implicit runtime: R): T
}

abstract class Merger[T, R] extends MultiInputStep[T, R] with SingleOutputStep[T, R] {
  protected def compute(index: Int, preview: Boolean)(implicit runtime: R): T =
    compute(inputs(preview), preview)
  protected def compute(args: IndexedSeq[T], preview: Boolean)(implicit runtime: R): T
}

abstract class Module[T, R] extends MultiInputStep[T, R] with MultiOutputStep[T, R] {
  protected def compute(index: Int, preview: Boolean)(implicit runtime: R): T =
    compute(inputs(preview), index, preview)
  protected def compute(args: IndexedSeq[T], index: Int, preview: Boolean)(implicit runtime: R): T
}

/**
 * XML serialization.
 */
trait XmlExport {
  def toXml: Elem
}

/**
 * JSON serialization.
 */
trait JsonExport {
  def toJson: JValue
}