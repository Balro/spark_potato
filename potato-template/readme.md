# potato-template  

## 简介  
提供spark开发模板，提供快速开发能力与参考。  

## 类说明  
* spark.potato.template.Template  
模板基础特质，主要实现了注册清理方法。  
* spark.potato.template.SparkContextFunc  
SparkContext集成特质，提供了sc管理方法。  
* spark.potato.template.StreamingContextFunc  
StreamingContext集成特质，提供了ssc管理方法。  
* spark.potato.template.batch.BatchTemplate  
批处理作业模板。  
* spark.potato.template.streaming.StreamingTemplate  
流处理作业模板。  

## 演示  
批处理  
```scala
package spark.potato.template.batch

import org.apache.spark.SparkConf
import org.junit.Test
import spark.potato.common.conf._
import spark.potato.common.util.ContextUtil._
import spark.potato.common.util.LocalLauncherUtil
import spark.potato.lock.conf._
import spark.potato.lock.running.ContextRunningLockService

object BatchTemplateTest extends BatchTemplate {
  override def doWork(): Unit = {
    val sc = createContext().stopWhenShutdown
    val rdd = sc.makeRDD(1 until 10)
    println(rdd.sum())
  }

  override def createConf(): SparkConf = {
    super.createConf()
      .set(POTATO_COMMON_ADDITIONAL_SERVICES_KEY,
        Seq(
          classOf[ContextRunningLockService]
        )
          .map(_.getName).mkString(","))

      // running lock
      .set(POTATO_RUNNING_LOCK_ZOOKEEPER_QUORUM_KEY, "test01:2181")
      .set(POTATO_RUNNING_LOCK_ZOOKEEPER_PATH_KEY, "/potato/lock/test")
      .set(POTATO_RUNNING_LOCK_HEARTBEAT_TIMEOUT_MS_KEY, "90000")
      .set(POTATO_RUNNING_LOCK_TRY_INTERVAL_MS_KEY, "5000")
      .set(POTATO_RUNNING_LOCK_HEARTBEAT_INTERVAL_MS_KEY, "5000")
  }
}

class BatchTemplateTest {
  @Test
  def local(): Unit = {
    LocalLauncherUtil.test(BatchTemplateTest)
  }
}
```  
流处理  
```scala
package spark.potato.template.streaming

import java.util.Date
import java.util.concurrent.TimeUnit

import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.StreamingContext
import org.junit.Test
import spark.potato.common.context.StreamingContextUtil
import spark.potato.common.conf._
import spark.potato.common.util.LocalLauncherUtil
import spark.potato.lock.conf._
import spark.potato.lock.running.StreamingRunningLockService
import spark.potato.monitor.backlog.BacklogMonitorService
import spark.potato.monitor.conf._

import scala.collection.mutable

object StreamingTemplateTest extends StreamingTemplate {
  /**
   * 业务逻辑。
   */
  override def doWork(): Unit = {
    val ssc = createStreamingContext(durMS = 5000)

    val source = ssc.queueStream(queue)
    source.print()

    start(ssc)
  }

  private val queue = mutable.Queue.empty[RDD[String]]

  override def afterStart(ssc: StreamingContext): Unit = {
    while (!ssc.sparkContext.isStopped) {
      queue += ssc.sparkContext.makeRDD(Seq(new Date().toString))
      TimeUnit.MILLISECONDS.sleep(StreamingContextUtil.getBatchDuration(ssc).milliseconds)
    }
  }

  override def createConf(): SparkConf = {
    super.createConf()
      // additional service
      .set(POTATO_COMMON_ADDITIONAL_SERVICES_KEY,
        Seq(
          classOf[StreamingRunningLockService],
          classOf[BacklogMonitorService]
        )
          .map(_.getName).mkString(","))

      // backlog monitor
      .set(POTATO_MONITOR_BACKLOG_DELAY_MS_KEY, "1")
      .set(POTATO_MONITOR_BACKLOG_REPORTER_INTERVAL_MS_KEY, "60000")
      .set(POTATO_MONITOR_BACKLOG_REPORTER_MAX_KEY, "60")
      .set(POTATO_MONITOR_BACKLOG_REPORTER_DING_TOKEN_KEY, "https://oapi.dingtalk.com/robot/send?access_token=2aa713587501102395004b0f87650cc5509b0d99af25868921d6509020785483")

      // running lock
      .set(POTATO_RUNNING_LOCK_ZOOKEEPER_QUORUM_KEY, "test01:2181")
      .set(POTATO_RUNNING_LOCK_ZOOKEEPER_PATH_KEY, "/potato/lock/test")
      .set(POTATO_RUNNING_LOCK_HEARTBEAT_TIMEOUT_MS_KEY, "90000")
      .set(POTATO_RUNNING_LOCK_TRY_INTERVAL_MS_KEY, "5000")
      .set(POTATO_RUNNING_LOCK_HEARTBEAT_INTERVAL_MS_KEY, "5000")
  }
}

class StreamingTemplateTest {
  @Test
  def local(): Unit = {
    LocalLauncherUtil.test(StreamingTemplateTest)
  }
}
```  