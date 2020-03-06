package spark.potato.template.streaming

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.streaming.StreamingContext
import spark.potato.common.context.PotatoContextUtil
import spark.potato.common.exception.SparkContextNotInitializedException
import spark.potato.common.service.Service
import spark.potato.lock.conf.LockConfigKeys._
import spark.potato.lock.runninglock.RunningLockManager
import spark.potato.monitor.backlog.BacklogMonitor
import spark.potato.monitor.conf.MonitorConfigKeys._

import scala.collection.mutable.ListBuffer

/**
 * Streaming快捷模板，通过继承该抽象类快速构建Streaming作业。
 */
abstract class GeneralStreamingTemplate extends Logging {
  protected var cmdArgs: Array[String] = _
  lazy val ssc: StreamingContext = createContext(cmdArgs)
  lazy val conf: SparkConf = createConf(cmdArgs)
  private val additionalServices = ListBuffer.empty[Service]

  def getConf: SparkConf = {
    if (ssc == null)
      throw SparkContextNotInitializedException()
    ssc.sparkContext.getConf
  }

  def getSsc: StreamingContext = ssc

  def main(args: Array[String]): Unit = {
    this.cmdArgs = args

    additionalServices ++= registerDefaultService() ++= registerAdditionalService()

    try {
      startServices()
      doWork(args)
      ssc.start()
      afterStart(args)
      ssc.awaitTermination()
    } finally {
      stopServices()
      afterStop(args)
    }
  }

  // 业务逻辑。
  def doWork(args: Array[String]): Unit

  def createConf(args: Array[String]): SparkConf = {
    logInfo("Method createConf has been called.")
    new SparkConf()
  }

  def createContext(args: Array[String], conf: SparkConf): StreamingContext = {
    logInfo("Method createContext has been called.")

    if (conf == null)
      throw new Exception("Spark conf is not initialized.")

    PotatoContextUtil.createStreamingContextWithDuration(conf)
  }

  def afterStart(args: Array[String]): Unit = {
    logInfo("Method afterStart has been called.")
  }

  def afterStop(args: Array[String]): Unit = {
    logInfo("Method afterStop has been called.")
  }

  private def registerDefaultService(): Seq[Service] = {
    logInfo("Method registerDefaultService has been called.")
    defaultServices.filter { info =>
      getConf.getBoolean(info.key, info.default)
    }.map { info =>
      logInfo(s"Register default service ${info.clazz}")
      Class.forName(info.clazz).getConstructor(classOf[StreamingContext]).newInstance(ssc).asInstanceOf[Service]
    }
  }

  def registerAdditionalService(args: Array[String]): Seq[Service] = {
    logInfo("Method registerAdditionalService has been called.")
    Seq.empty
  }

  private def startServices(): Unit = {
    additionalServices.foreach {
      service =>
        service.start()
        logInfo(s"Service: $service started.")
    }
  }

  private def stopServices(): Unit = {
    additionalServices.foreach {
      service =>
        service.stop()
        logInfo(s"Service: $service stopped.")
    }
  }

}