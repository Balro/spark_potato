package potato.hadoop.utils

import java.util.concurrent.Executors

import org.apache.hadoop.fs.{FileSystem, Path, Trash}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.{SparkConf, SparkContext}
import potato.common.exception.PotatoException
import potato.common.threads.DaemonThreadFactory

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Failure

object HDFSUtil extends Logging {
  /**
   * 利用sparksql合并指定目录，适用于分区目录。
   * 当合并大量非常小的文件时，使用过少的分区数可能导致作业执行时间过长，可以逐次降低分区数，用多次合并的方式来提升效率。
   *
   * 合并后文件大小影响参数，相关参数请在调用方法前设置:
   * * spark并发作业的partition数量，每个启动的partition都会生成一个文件。
   * * * 计算公式: maxSplitBytes = min(maxPartitionBytes,max(totalSize/parallelism,fileOpenCost))
   * * * 参考 [[org.apache.spark.sql.execution.FileSourceScanExec]].createNonBucketedReadRDD#423
   * * 重要参数:
   * * * SparkConf:spark.default.parallelism # 请在创建spark前进行配置。
   * * * SQLConf:spark.sql.files.maxPartitionBytes
   * * * SQLConf:spark.sql.files.openCostInBytes # 降低该值可以使每个分区合并更多的小文件。
   * 对于存在少量子目录，但是每个子目录存在大量小文件的情况来说，尽可能启用分布式分区发现，可以大大提升分区解析效率。
   * * SQLConf:spark.sql.sources.parallelPartitionDiscovery.threshold # 建议设置为0.
   *
   * @param source          数据源目录。
   * @param target          目标目录，默认与数据源目录相同，即新目录会覆盖原目录。
   * @param sourceFormat    源目录文件格式。
   * @param targetFormat    目标目录文件格式。
   * @param overwrite       当目标路径存在时，是否强制替换，默认为true。
   * @param partitionFilter 过滤条件，必须对分区进行过滤，目前尚未对过滤条件是否指定分区外字段进行检查，须调用方法时外部确认。
   * @param maxParallel     同时写入分区启用的最大作业数。
   * @param readerOptions   额外加载入DataFrameReader的参数。
   * @param writerOptions   额外加载入DataFrameWriter的参数。
   * @param compression     写出文件时使用的压缩格式。
   */
  def merge(spark: SparkSession, source: String, target: String, sourceFormat: String, targetFormat: String,
            partitionFilter: String = null, overwrite: Boolean = true, maxParallel: Int = 100,
            readerOptions: Map[String, String] = Map.empty,
            writerOptions: Map[String, String] = Map.empty,
            compression: String = "snappy"): Array[String] = {
    implicit def str2Path(str: String): Path = new Path(str)
    // 不生成_SUCCESS文件。
    spark.sparkContext.hadoopConfiguration.setBoolean("mapreduce.fileoutputcommitter.marksuccessfuljobs", false)
    // 验证服务端是否开启回收站，如服务端未开启回收站，则会抛出异常。
    spark.sparkContext.hadoopConfiguration.setInt("fs.trash.interval", 1)

    val fs = FileSystem.newInstance(spark.sparkContext.hadoopConfiguration)
    val tmpDir = s"${target.stripSuffix("/")}_merge_${System.currentTimeMillis()}_${spark.sparkContext.applicationId}"
    if (fs.exists(tmpDir)) throw new PotatoException(s"Tmp dir exists $tmpDir")

    val sourceDF = spark.read.options(readerOptions).format(sourceFormat).load(source)
    val partitionSpec = getPartitionSpec(sourceDF)
    val partitionStr = partitionSpec.partitionColumns.map(_.name)
    val partitionRow = partitionSpec.partitions.map { ps =>
      Row.fromSeq(ps.values.toSeq(partitionSpec.partitionColumns).map { p =>
        if (p.isInstanceOf[UTF8String]) p.toString else p
      })
    }

    val mergedPath = if (partitionRow.isEmpty) { // 非分区路径。
      logInfo(s"No partition detected, start merge directory $target to tmp $tmpDir")
      sourceDF.write
        .mode(SaveMode.Append)
        .format(targetFormat)
        .options(writerOptions.+("compression" -> compression))
        .save(tmpDir)
      if (rename(fs, tmpDir, target, overwrite))
        Array(target)
      else
        throw new PotatoException(s"Failed to rename $tmpDir to $target.")
    } else { // 分区路径。
      implicit val executor: ExecutionContext = ExecutionContext.fromExecutor(
        Executors.newFixedThreadPool(maxParallel, DaemonThreadFactory)
      )
      val partitionDF = spark.createDataFrame(spark.sparkContext.makeRDD(partitionRow), partitionSpec.partitionColumns)
      // 过滤分区。
      val filteredPartitionDF = if (partitionFilter == null) partitionDF else partitionDF.where(partitionFilter)
      logInfo("Partition detected.")
      filteredPartitionDF.show(Int.MaxValue - 1, truncate = false)
      // 组合分区路径片段，如 Map(ymd->20200101,type->"a") 将组合为 "ymd=20200101/type=a" 。
      val filteredPartFragment = filteredPartitionDF.collect().map { r =>
        partitionStr.zip(r.toSeq.map(_.toString)).toMap
      }.map(p => PartitioningUtils.getPathFragment(p, partitionSpec.partitionColumns))

      // 用于对每一个分区提交子作业。
      val jobs: Future[mutable.ArraySeq[Unit]] = Future.sequence(filteredPartFragment.map { f =>
        Future {
          try {
            logInfo(s"Merging directory $source/$f to $tmpDir/$f started.")
            // 为每个分区单独创建df进行写入，避免全表创建df导致partition数量过多的问题。
            spark.read.options(readerOptions).format(sourceFormat).load(s"$source/$f").write
              .mode(SaveMode.ErrorIfExists)
              .format(targetFormat)
              .options(writerOptions.+("compression" -> compression))
              .save(s"$tmpDir/$f")
            logInfo(s"Merging directory $source/$f to $tmpDir/$f finished.")
          } catch {
            case e: Exception =>
              spark.close() // 当有分区失败时停止作业。
              throw e
          }
        }
      })
      Await.ready(jobs, Duration.Inf)
      jobs.value.get match {
        case Failure(e) => throw e
        case _ =>
      }

      // 单独移动分区。
      filteredPartFragment.foreach { f =>
        if (!rename(fs, s"$tmpDir/$f", s"$target/$f", overwrite))
          throw new PotatoException(s"Failed to rename $tmpDir/$f to $target/$f.")
      }

      // 临时目录存在未移动的文件。
      if (dirOnly(fs, tmpDir)) {
        logInfo(s"Delete tmp directory $tmpDir")
        fs.delete(tmpDir, true)
      } else {
        throw new PotatoException(s"Some file is still in tmpDir $tmpDir, please check.")
      }
      filteredPartFragment.map(f => s"$target/$f")
    }
    fs.close()
    mergedPath
  }

  /**
   * 将源路径移至目标路径。
   *
   * @param force 如目标路径已存在，则将其置入回收站。
   */
  def rename(fs: FileSystem, source: Path, target: Path, force: Boolean): Boolean = {
    if (fs.exists(target)) {
      if (force) {
        logInfo(s"Target $target exist, move target to trash.")
        moveToTrash(fs, target)
      } else {
        logWarning(s"Target dir $target exists, rename source $source failed.")
        return false
      }
    } else if (!fs.exists(target.getParent))
      fs.mkdirs(target.getParent)
    logInfo(s"Rename $source to $target")
    fs.rename(source, target)
  }

  /**
   * 将给定目录移入回收站。
   */
  def moveToTrash(fs: FileSystem, path: Path): Boolean = {
    if (fs.exists(path)) {
      val trash = new Trash(fs, fs.getConf)
      trash.moveToTrash(path)
    } else {
      true
    }
  }

  /**
   * 检查给定路径下所有子路径是否全为目录，如存在文件，则返回false。
   */
  def dirOnly(fs: FileSystem, path: Path): Boolean = !fs.listFiles(path, true).hasNext

  /**
   * 解析指定目录的数据结构和分区结构。
   * spark内部分为两步来执行:
   * * 1. 查找给定目录的全部子路径信息。
   * * * 受[[SQLConf.PARALLEL_PARTITION_DISCOVERY_THRESHOLD]]影响，可以在driver端进行，也可以启动分布式作业在executor端进行。
   * * * 使用分布式作业可以大大提高文件查找效率。
   * * 2. 根据获取的全部子路径，使用[[org.apache.spark.sql.execution.datasources.PartitioningUtils]]来解析为具体的分区信息。
   *
   * @param spark        sparksession实例。
   * @param format       sparksession.read.format(source:String)支持的文件格式。
   * @param path         需要解析的文件或目录。
   * @param ppdThreshold 允许在driver端解析的路径数量限制，超过该限制会启动并发作业解析schema，可以大大提高解析速度。
   *                     如果设置为0，由于内部循环调用的bug，会导致直接提交一个并行度的作业，等效于driver端分析。
   * @param options      用于提供给DataSource的配置，等同于sparksession.read.option(key:String,value:String)支持的参数。
   * @return (dataSchema,partitionSchema)
   */
  def getFileSchema(spark: SparkSession, format: String, path: String,
                    ppdThreshold: Int = 1,
                    options: Map[String, String] = Map.empty): (StructType, StructType) = {
    spark.conf.set(SQLConf.PARALLEL_PARTITION_DISCOVERY_THRESHOLD.key, ppdThreshold)
    val source = DataSource(spark, paths = Seq(path), className = format, options = options)
    val method = source.getClass.getDeclaredMethod("getOrInferFileFormatSchema", classOf[FileFormat], classOf[FileStatusCache])
    method.setAccessible(true)
    method.invoke(source,
      source.providingClass.newInstance().asInstanceOf[FileFormat],
      FileStatusCache.getOrCreate(spark)).asInstanceOf[(StructType, StructType)]
  }

  /**
   * 解析指定目录的数据结构和分区结构。
   *
   * @param conf   用于创建sparksession实例的配置。
   * @param format sparksession.read.format()支持的文件格式。
   * @param path   需要解析的文件或目录。
   * @return (dataSchema,partitionSchema)
   */
  def getFileSchema(conf: SparkConf, format: String, path: String): (StructType, StructType) = {
    getFileSchema(SparkSession.builder().config(conf).getOrCreate(), format, path)
  }

  /**
   * 解析指定目录的数据结构和分区结构。
   *
   * @param sc     用于创建sparksession实例的context。
   * @param format sparksession.read.format()支持的文件格式。
   * @param path   需要解析的文件或目录。
   * @return (dataSchema,partitionSchema)
   */
  def getFileSchema(sc: SparkContext, format: String, path: String): (StructType, StructType) = {
    getFileSchema(SparkSession.builder().config(sc.getConf).getOrCreate(), format, path)
  }

  /**
   * 从dataset中获取分区信息。
   */
  def getPartitionSpec[T](ds: Dataset[T]): PartitionSpec = {
    val logicalPlanField = ds.getClass.getDeclaredField("logicalPlan")
    logicalPlanField.setAccessible(true)
    val logicalRelation = logicalPlanField.get(ds).asInstanceOf[LogicalRelation]
    val hadoopFsRelation = logicalRelation.relation.asInstanceOf[HadoopFsRelation]
    val inMemoryFileIndex = hadoopFsRelation.location.asInstanceOf[InMemoryFileIndex]
    inMemoryFileIndex.partitionSpec()
  }
}
