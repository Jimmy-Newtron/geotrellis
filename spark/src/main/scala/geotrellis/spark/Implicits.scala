package geotrellis.spark

import geotrellis.raster._
import geotrellis.raster.io.geotiff._
import geotrellis.vector._
import geotrellis.proj4._
import geotrellis.util._
import geotrellis.spark.tiling._
import geotrellis.spark.ingest._
import geotrellis.spark.crop._
import geotrellis.spark.filter._
import org.apache.spark.{Partitioner, SparkContext}
import org.apache.spark.rdd._

import scala.reflect.ClassTag
import java.time.Instant


object Implicits extends Implicits

trait Implicits
    extends buffer.Implicits
    with clip.Implicits
    with costdistance.Implicits
    with crop.Implicits
    with density.Implicits
    with distance.Implicits
    with equalization.Implicits
    with filter.Implicits
    with join.Implicits
    with knn.Implicits
    with mapalgebra.focal.hillshade.Implicits
    with mapalgebra.focal.Implicits
    with mapalgebra.Implicits
    with mapalgebra.local.Implicits
    with mapalgebra.local.temporal.Implicits
    with mapalgebra.zonal.Implicits
    with mask.Implicits
    with matching.Implicits
    with merge.Implicits
    with partition.Implicits
    with regrid.Implicits
    with reproject.Implicits
    with resample.Implicits
    with rasterize.Implicits
    with sigmoidal.Implicits
    with split.Implicits
    with stitch.Implicits
    with summary.Implicits
    with summary.polygonal.Implicits
    with tiling.Implicits
    with timeseries.Implicits
    with viewshed.Implicits
    with Serializable {

  /**
    * This is a type class required by the [[geotrellis.spark.filter.ToSpatial]] function.
    * `map` applies a function `A => B` on the keys from this Metadata's [[KeyBounds]],
    * which allows for the transformation:
    * {{{TileLayerMetadata[A] => TileLayerMetadata[B]}}}
    */
  implicit class TileLayerMetadataFunctor[A](val self: TileLayerMetadata[A]) extends Functor[TileLayerMetadata, A] {
    def map[B](f: A => B): TileLayerMetadata[B] = self.bounds match {
      case KeyBounds(minKey, maxKey) => self.copy(bounds = KeyBounds(f(minKey), f(maxKey)))
      case EmptyBounds => self.copy(bounds = EmptyBounds)
    }
  }

  /** Auto wrap a partitioner when something is requestion an Option[Partitioner];
    * useful for Options that take an Option[Partitioner]
    */
  implicit def partitionerToOption(partitioner: Partitioner): Option[Partitioner] =
    Some(partitioner)

  implicit def longToInstant(millis: Long): Instant = Instant.ofEpochMilli(millis)

  /** Necessary for Contains.forPoint query */
  implicit def tileLayerMetadataToMapKeyTransform[K](tm: TileLayerMetadata[K]): MapKeyTransform = tm.mapTransform

  implicit class WithContextWrapper[K, V, M](val rdd: RDD[(K, V)] with Metadata[M]) {
    def withContext[K2, V2](f: RDD[(K, V)] => RDD[(K2, V2)]) =
      new ContextRDD(f(rdd), rdd.metadata)

    def mapContext[M2](f: M => M2) =
      new ContextRDD(rdd, f(rdd.metadata))
  }

  implicit class WithContextCollectionWrapper[K, V, M](val seq: Seq[(K, V)] with Metadata[M]) {
    def withContext[K2, V2](f: Seq[(K, V)] => Seq[(K2, V2)]) =
      new ContextCollection(f(seq), seq.metadata)

    def mapContext[M2](f: M => M2) =
      new ContextCollection(seq, f(seq.metadata))
  }

  implicit def tupleToRDDWithMetadata[K, V, M](tup: (RDD[(K, V)], M)): RDD[(K, V)] with Metadata[M] =
    ContextRDD(tup._1, tup._2)

  implicit class withContextRDDMethods[
    K: ClassTag: SpatialComponent,
    V <: CellGrid: ClassTag,
    M: GetComponent[?, LayoutDefinition]
  ](rdd: RDD[(K, V)] with Metadata[M]) extends ContextRDDMethods[K, V, M](rdd) {

    def toRasters: RDD[(K, Raster[V])] = {
      val mt = rdd.metadata.getComponent[LayoutDefinition].mapTransform

      rdd.mapPartitions({ partition =>
        partition.map { case (k, v) =>
          (k, Raster(v, mt(k.getComponent[SpatialKey])))
        }
      }, preservesPartitioning = true)
    }
  }

  implicit class withTileLayerRDDMethods[K: SpatialComponent: ClassTag](val self: TileLayerRDD[K])
    extends TileLayerRDDMethods[K] {
    def toGeoTiffs(
      tags: Tags = Tags.empty,
      options: GeoTiffOptions = GeoTiffOptions.DEFAULT
    ): RDD[(K, SinglebandGeoTiff)] = {
      val mt = self.metadata.layout.mapTransform
      val crs = self.metadata.crs

      self.mapPartitions({ partition =>
        partition.map { case (k, v) =>
          (k, SinglebandGeoTiff(v, mt(k.getComponent[SpatialKey]), crs, tags, options))
        }
      }, preservesPartitioning = true)
    }
  }

  implicit class withTileLayerCollectionMethods[K: SpatialComponent](val self: TileLayerCollection[K])
    extends TileLayerCollectionMethods[K]

  implicit class withMultibandTileLayerRDDMethods[K: SpatialComponent: ClassTag](val self: MultibandTileLayerRDD[K])
    extends MultibandTileLayerRDDMethods[K] {

    def toGeoTiffs(
      tags: Tags = Tags.empty,
      options: GeoTiffOptions = GeoTiffOptions.DEFAULT
    ): RDD[(K, MultibandGeoTiff)] = {
      val mt = self.metadata.layout.mapTransform
      val crs = self.metadata.crs

      self.mapPartitions({ partition =>
        partition.map { case (k, v) =>
          (k, MultibandGeoTiff(v, mt(k.getComponent[SpatialKey]), crs, tags, options))
        }
      }, preservesPartitioning = true)
    }
  }

  implicit class withCellGridLayoutRDDMethods[K: SpatialComponent: ClassTag, V <: CellGrid, M: GetComponent[?, LayoutDefinition]](val self: RDD[(K, V)] with Metadata[M])
      extends CellGridLayoutRDDMethods[K, V, M]

  implicit class withCellGridLayoutCollectionMethods[K: SpatialComponent, V <: CellGrid, M: GetComponent[?, LayoutDefinition]](val self: Seq[(K, V)] with Metadata[M])
    extends CellGridLayoutCollectionMethods[K, V, M]

  implicit class withProjectedExtentRDDMethods[K: Component[?, ProjectedExtent], V <: CellGrid](val rdd: RDD[(K, V)]) {
    def toRasters: RDD[(K, Raster[V])] =
      rdd.mapPartitions({ partition =>
        partition.map { case (key, value) =>
          (key, Raster(value, key.getComponent[ProjectedExtent].extent))
        }
      }, preservesPartitioning = true)
  }

  implicit class withTileProjectedExtentRDDMethods[K: Component[?, ProjectedExtent]: Component[?, CRS]](val rdd: RDD[(K, Tile)]) {
    def toGeoTiffs(
      tags: Tags = Tags.empty,
      options: GeoTiffOptions = GeoTiffOptions.DEFAULT
    ): RDD[(K, SinglebandGeoTiff)] =
      rdd.mapPartitions({ partition =>
        partition.map { case (key, value) =>
          (key, SinglebandGeoTiff(value, key.getComponent[ProjectedExtent].extent, key.getComponent[CRS], tags, options))
        }
      }, preservesPartitioning = true)
  }

  implicit class withMultibandTileProjectedExtentRDDMethods[K: Component[?, ProjectedExtent]: Component[?, CRS]](val rdd: RDD[(K, MultibandTile)]) {
    def toGeoTiffs(
      tags: Tags = Tags.empty,
      options: GeoTiffOptions = GeoTiffOptions.DEFAULT
    ): RDD[(K, MultibandGeoTiff)] =
      rdd.mapPartitions({ partition =>
        partition.map { case (key, value) =>
          (key, MultibandGeoTiff(value, key.getComponent[ProjectedExtent].extent, key.getComponent[CRS], tags, options))
        }
      }, preservesPartitioning = true)
  }

  implicit class withSpatialContextRDDMethods[V: ClassTag](val self: RDD[(SpatialKey, V)] with Metadata[TileLayerMetadata[SpatialKey]])
    extends ContextRDDMethods[SpatialKey, V, TileLayerMetadata[SpatialKey]](self) {

    def keyToProjectedExtent: RDD[(ProjectedExtent, V)] = {
      val mt = self.metadata.layout.mapTransform
      val crs = self.metadata.crs

      self.mapPartitions { partition =>
        partition.map { case (k, v) => (ProjectedExtent(mt(k), crs), v) }
      }
    }
  }

  implicit class withTemporalContextRDDMethods[V: ClassTag](val self: RDD[(SpaceTimeKey, V)] with Metadata[TileLayerMetadata[SpaceTimeKey]])
    extends ContextRDDMethods[SpaceTimeKey, V, TileLayerMetadata[SpaceTimeKey]](self) {

    def keyToTemporalProjectedExtent: RDD[(TemporalProjectedExtent, V)] = {
      val mt = self.metadata.layout.mapTransform
      val crs = self.metadata.crs

      self.mapPartitions { partition =>
        partition.map { case (k, v) => (TemporalProjectedExtent(mt(k), crs, k.instant), v) }
      }
    }
  }

  implicit class withCollectionConversionMethods[K, V, M](val rdd: RDD[(K, V)] with Metadata[M]) {
    def toCollection: Seq[(K, V)] with Metadata[M] = ContextCollection(rdd.collect(), rdd.metadata)
  }

  implicit class withRddConversionMethods[K, V, M](val seq: Seq[(K, V)] with Metadata[M]) {
    def toRDD(implicit sc: SparkContext): RDD[(K, V)] with Metadata[M] = ContextRDD(sc.parallelize(seq), seq.metadata)
  }

  implicit class withProjectedExtentTemporalTilerKeyMethods[K: Component[?, ProjectedExtent]: Component[?, TemporalKey]](val self: K) extends TilerKeyMethods[K, SpaceTimeKey] {
    def extent = self.getComponent[ProjectedExtent].extent
    def translate(spatialKey: SpatialKey): SpaceTimeKey = SpaceTimeKey(spatialKey, self.getComponent[TemporalKey])
  }

  implicit class withProjectedExtentTilerKeyMethods[K: Component[?, ProjectedExtent]](val self: K) extends TilerKeyMethods[K, SpatialKey] {
    def extent = self.getComponent[ProjectedExtent].extent
    def translate(spatialKey: SpatialKey) = spatialKey
  }

  implicit class withCollectMetadataMethods[K1, V <: CellGrid](rdd: RDD[(K1, V)]) extends Serializable {
    /** The `Int` is the zoom level if ingested with the produced Metadata. */
    def collectMetadata[K2: Boundable: SpatialComponent](crs: CRS, layoutScheme: LayoutScheme)
        (implicit ev: K1 => TilerKeyMethods[K1, K2]): (Int, TileLayerMetadata[K2]) = {
      TileLayerMetadata.fromRdd[K1, V, K2](rdd, crs, layoutScheme)
    }

    def collectMetadata[K2: Boundable: SpatialComponent](crs: CRS, layout: LayoutDefinition)
        (implicit ev: K1 => TilerKeyMethods[K1, K2]): TileLayerMetadata[K2] = {
      TileLayerMetadata.fromRdd[K1, V, K2](rdd, crs, layout)
    }

    /** The `Int` is the zoom level if ingested with the produced Metadata. */
    def collectMetadata[K2: Boundable: SpatialComponent](layoutScheme: LayoutScheme)
        (implicit ev: K1 => TilerKeyMethods[K1, K2], ev1: GetComponent[K1, ProjectedExtent]): (Int, TileLayerMetadata[K2]) = {
      TileLayerMetadata.fromRdd[K1, V, K2](rdd, layoutScheme)
    }

    /** The `Int` is the zoom level if ingested with the produced Metadata. */
    def collectMetadata[K2: Boundable: SpatialComponent](crs: CRS, size: Int, zoom: Int)
        (implicit ev: K1 => TilerKeyMethods[K1, K2], ev1: GetComponent[K1, ProjectedExtent]): (Int, TileLayerMetadata[K2]) = {
      TileLayerMetadata.fromRdd[K1, V, K2](rdd, ZoomedLayoutScheme(crs, size), zoom)
    }

    def collectMetadata[K2: Boundable: SpatialComponent](layout: LayoutDefinition)
        (implicit ev: K1 => TilerKeyMethods[K1, K2], ev1: GetComponent[K1, ProjectedExtent]): TileLayerMetadata[K2] = {
      TileLayerMetadata.fromRdd[K1, V, K2](rdd, layout)
    }
  }
}
