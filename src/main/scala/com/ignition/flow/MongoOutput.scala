package com.ignition.flow

import org.apache.spark.sql.{ DataFrame, Row, SQLContext }
import org.apache.spark.sql.types.StructType
import com.ignition.util.MongoUtils
import com.mongodb.casbah.commons.MongoDBObject

/**
 * Writes rows into a MongoDB collection.
 *
 * @author Vlad Orzhekhovskiy
 */
case class MongoOutput(db: String, coll: String) extends Transformer {

  protected def compute(arg: DataFrame, limit: Option[Int])(implicit ctx: SQLContext): DataFrame = {
    val db = this.db
    val coll = this.coll

    val df = limit map arg.limit getOrElse arg
    df foreachPartition { rows =>
      val collection = MongoUtils.collection(db, coll)
      rows foreach { row =>
        val data = row.schema zip row.toSeq map {
          case (field, value) => field.name -> value
        }
        val doc = MongoDBObject(data: _*)
        collection.save(doc)
      }
    }
    
    df
  }

  protected def computeSchema(inSchema: StructType)(implicit ctx: SQLContext): StructType = inSchema

  private def writeObject(out: java.io.ObjectOutputStream): Unit = unserializable
}