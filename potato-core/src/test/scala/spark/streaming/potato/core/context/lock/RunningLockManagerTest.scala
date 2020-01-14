package spark.streaming.potato.core.context.lock

import java.util.concurrent.TimeUnit

import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.junit.Test
import spark.streaming.potato.core.conf.PotatoConfKeys._

class RunningLockManagerTest {
  @Test
  def initTest(): Unit = {
    val conf = new SparkConf()
    conf.setMaster("local[8]").setAppName("test")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_ADDR_KEY, "test02:2181")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_PATH_KEY, "/potato/lock/test")

    val ssc = new StreamingContext(conf, Seconds(10))

    val lockManager = new RunningLockManager(ssc)
  }

  @Test
  def tryLockTest(): Unit = {
    val conf = new SparkConf()
    conf.setMaster("local[8]").setAppName("test")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_ADDR_KEY, "test02:2181")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_PATH_KEY, "/potato/lock/test")

    val ssc = new StreamingContext(conf, Seconds(10))

    val lockManager = new RunningLockManager(ssc)

    println(lockManager.locked)

    lockManager.tryLock(3, 5000)

    println(lockManager.locked)

    TimeUnit.MINUTES.sleep(10)
  }

  @Test
  def releaseTest(): Unit = {
    val conf = new SparkConf()
    conf.setMaster("local[8]").setAppName("test")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_ADDR_KEY, "test02:2181")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_PATH_KEY, "/potato/lock/test")

    val ssc = new StreamingContext(conf, Seconds(10))

    val lockManager = new RunningLockManager(ssc)

    println(lockManager.locked + lockManager.lock.getLock.toString())
    lockManager.tryLock(3, 5000)
    println(lockManager.locked + lockManager.lock.getLock.toString())
    lockManager.release()

    TimeUnit.MINUTES.sleep(10)
  }
}

object HeartbeatTest {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
    conf.setMaster("local[8]").setAppName("test")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_ADDR_KEY, "test02:2181")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_PATH_KEY, "/potato/lock/test")
    conf.set(POTATO_RUNNING_LOCK_HEARTBEAT_TIMEOUT_MS_KEY, "90000")
    conf.set(POTATO_RUNNING_LOCK_TRY_INTERVAL_MS_KEY, "5000")
    conf.set(POTATO_RUNNING_LOCK_HEARTBEAT_INTERVAL_MS_KEY, "5000")

    val ssc = new StreamingContext(conf, Seconds(10))

    val lockManager = new RunningLockManager(ssc)

    lockManager.startHeartbeat()
  }
}