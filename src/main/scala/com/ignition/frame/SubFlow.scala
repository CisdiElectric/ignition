package com.ignition.frame

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.StructType

import com.ignition.{ SparkRuntime, Step }

/**
 * Flow input data.
 */
case class FlowInput(override val inputCount: Int) extends FrameModule(inputCount, inputCount) {

  override val allInputsRequired: Boolean = false

  protected def compute(args: Seq[DataFrame], index: Int,
    limit: Option[Int])(implicit runtime: SparkRuntime): DataFrame = args(index)

  protected def computeSchema(inSchemas: Seq[StructType], index: Int)(implicit runtime: SparkRuntime): StructType =
    computedSchema(index)

  private def writeObject(out: java.io.ObjectOutputStream): Unit = unserializable
}

/**
 * Flow output data.
 */
case class FlowOutput(override val outputCount: Int) extends FrameModule(outputCount, outputCount) {

  protected def compute(args: Seq[DataFrame], index: Int,
    limit: Option[Int])(implicit runtime: SparkRuntime): DataFrame = args(index)

  protected def computeSchema(inSchemas: Seq[StructType], index: Int)(implicit runtime: SparkRuntime): StructType =
    inSchemas(index)

  private def writeObject(out: java.io.ObjectOutputStream): Unit = unserializable
}

/**
 * Sub-flow.
 */
case class SubFlow(input: FlowInput, output: FlowOutput) extends FrameModule(input.inputCount, output.outputCount) {

  override def connectFrom(inIndex: Int, step: Step[DataFrame], outIndex: Int): this.type = {
    input.connectFrom(inIndex, step, outIndex)
    this
  }

  override def output(index: Int, limit: Option[Int] = None)(implicit runtime: SparkRuntime): DataFrame =
    output.output(index, limit)

  override def outSchema(index: Int)(implicit runtime: SparkRuntime): StructType =
    wrap { output.outSchema(index) }

  /* not used */
  protected def compute(args: Seq[DataFrame], index: Int, limit: Option[Int])(implicit runtime: SparkRuntime): DataFrame = ???

  /* not used */
  protected def computeSchema(inSchemas: Seq[StructType], index: Int)(implicit runtime: SparkRuntime): StructType = ???

  private def writeObject(out: java.io.ObjectOutputStream): Unit = unserializable
}

/**
 * SubFlow companion object.
 */
object SubFlow {

  def apply(inputCount: Int, outputCount: Int)(body: (FlowInput, FlowOutput) => Unit): SubFlow = {
    val input = FlowInput(inputCount)
    val output = FlowOutput(outputCount)
    body(input, output)
    SubFlow(input, output)
  }

  def apply(outputCount: Int)(body: (FlowOutput) => Unit): SubFlow = {
    val input = FlowInput(0)
    val output = FlowOutput(outputCount)
    body(output)
    SubFlow(input, output)
  }
}