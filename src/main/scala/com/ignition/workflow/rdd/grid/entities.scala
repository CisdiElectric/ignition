package com.ignition.workflow.rdd.grid

import scala.xml.{ Elem, Node }

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import com.ignition.data.{ DataRow, RowMetaData }
import com.ignition.workflow.{ Step0, Step1, Step2, StepN, WorkflowException }

/**
 * The base step of the ignition grid framework.
 *
 * @author Vlad Orzhekhovskiy
 */
trait GridStep {

  /**
   * Returns the output metadata of the step.
   */
  def outMetaData: Option[RowMetaData]

  /**
   * Converts this step into XML.
   */
  def toXml: Elem

  /* spark helpers */

  protected def defaultParallelism(implicit sc: SparkContext) = sc.defaultParallelism
}

/**
 * Grid step without inputs.
 */
trait GridStep0 extends Step0[RDD[DataRow], SparkContext] with GridStep {

  protected def computeRDD(implicit sc: SparkContext): RDD[DataRow]

  protected def compute(sc: SparkContext): RDD[DataRow] = computeRDD(sc)
}

/**
 * Grid step with one input.
 */
trait GridStep1 extends Step1[RDD[DataRow], RDD[DataRow], SparkContext] with GridStep {

  protected def computeRDD(rdd: RDD[DataRow]): RDD[DataRow]

  protected def compute(sc: SparkContext)(rdd: RDD[DataRow]): RDD[DataRow] = computeRDD(rdd)

  protected def inMetaData: Option[RowMetaData] = in flatMap {
    _.asInstanceOf[GridStep].outMetaData
  }
}

/**
 * Grid step with two inputs.
 */
trait GridStep2 extends Step2[RDD[DataRow], RDD[DataRow], RDD[DataRow], SparkContext] with GridStep {

  protected def computeRDD(rdd1: RDD[DataRow], rdd2: RDD[DataRow]): RDD[DataRow]

  protected def compute(sc: SparkContext)(rdd1: RDD[DataRow], rdd2: RDD[DataRow]): RDD[DataRow] =
    computeRDD(rdd1, rdd2)

  protected def inMetaData: Option[(RowMetaData, RowMetaData)] = for {
    input1 <- in1
    meta1 <- input1.asInstanceOf[GridStep].outMetaData
    input2 <- in2
    meta2 <- input2.asInstanceOf[GridStep].outMetaData
  } yield (meta1, meta2)
}

/**
 * Grid step with N inputs of the same type.
 */
trait GridStepN extends StepN[RDD[DataRow], RDD[DataRow], SparkContext] with GridStep {

  protected def computeRDD(rdds: Iterable[RDD[DataRow]])(implicit sc: SparkContext): RDD[DataRow]

  protected def compute(sc: SparkContext)(rdds: Iterable[RDD[DataRow]]): RDD[DataRow] =
    computeRDD(rdds)(sc)

  protected def inMetaData: Option[RowMetaData] = {
    def assertAllEqual[T](list: Iterable[T], elem: T) =
      if (!list.forall(_ == elem)) throw WorkflowException("Input metadata do not match")

    val opts = ins map (_.asInstanceOf[GridStep].outMetaData)
    if (opts.contains(None))
      None // if at least one input does not have metadata set
    else {
      val metas = opts map (_.get)
      metas.headOption map { head => assertAllEqual(metas, head); head }
    }
  }
}

/**
 * Extended by factories that can restore instances from XML.
 */
trait XmlFactory[T] {
  /**
   * Creates an object instance from XML.
   */
  def fromXml(xml: Node): T
}