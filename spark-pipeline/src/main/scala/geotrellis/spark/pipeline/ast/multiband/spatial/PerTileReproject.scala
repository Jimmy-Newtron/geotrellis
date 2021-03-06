package geotrellis.spark.pipeline.ast.multiband.spatial

import io.circe.syntax._

import geotrellis.raster._
import geotrellis.spark.pipeline.ast._
import geotrellis.spark.pipeline.json.transform
import geotrellis.vector._

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

case class PerTileReproject(
  node: Node[RDD[(ProjectedExtent, MultibandTile)]],
  arg: transform.Reproject
) extends Transform[RDD[(ProjectedExtent, MultibandTile)], RDD[(ProjectedExtent, MultibandTile)]] {
  def asJson = node.asJson :+ arg.asJson
  def eval(implicit sc: SparkContext): RDD[(ProjectedExtent, MultibandTile)] = Transform.perTileReproject(arg)(node.eval)
}
