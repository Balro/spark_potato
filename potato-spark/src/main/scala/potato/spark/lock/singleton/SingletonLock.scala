package potato.spark.lock.singleton

import org.apache.spark.internal.Logging
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException, SessionExpiredException}
import org.apache.zookeeper._

trait SingletonLock {
  /**
   * 加锁，并附加状态信息。
   *
   * @return 是否成功加锁。
   */
  def tryLock(msg: String): Boolean

  /**
   * 释放锁。
   */
  def release(): Unit

  /**
   * 清理旧锁。
   *
   * @return 旧锁存在返回true，旧锁不存在返回false。
   */
  def clean(): Boolean

  /**
   * 获取锁的状态信息。
   *
   * @return (是否已加锁，当前锁信息)
   */
  def getMsg: (Boolean, String)

  /**
   * 更新锁的状态信息。
   */
  def setMsg(msg: String): Unit
}

/**
 * SingletonLock的zookeeper实现。
 *
 * @param lockService 用于在锁异常时对app进行反馈，一般用于直接停止app作业。
 * @param quorum      zookeeper地址。
 * @param timeout     zookeeper连接超时时间。
 * @param path        锁路径。
 * @param id          作业名。
 */
class ZookeeperSingletonLock(lockService: SingletonLockService, quorum: String, timeout: Int, path: String, id: String) extends SingletonLock
  with Watcher with Logging {
  val zookeeper = new ZooKeeper(quorum, timeout, this)
  val lockPath: String = path + "/" + id + ".lock"
  checkPath(path)

  /**
   * 初始化锁路径，如锁路径不存在，则自动创建。
   */
  def checkPath(path: String): Unit = {
    if (path.nonEmpty && zookeeper.exists(path, false) == null) {
      checkPath(path.substring(0, path.lastIndexOf("/")))
      zookeeper.create(path, Array.emptyByteArray, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
    }
  }

  override def tryLock(msg: String): Boolean = {
    try {
      zookeeper.create(lockPath, msg.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
      zookeeper.exists(lockPath, new LockDeleteWatcher())
      logInfo("Lock successfully.")
      true
    } catch {
      case e: NodeExistsException =>
        logWarning(s"Old lock exists, msg -> ${getMsg._2}", e)
        false
    }
  }

  override def release(): Unit = {
    logInfo("Release lock.")
    zookeeper.close()
  }

  override def clean(): Boolean = {
    try {
      zookeeper.delete(lockPath, -1)
    } catch {
      case _: NoNodeException =>
        logWarning("Old lock not found.")
        return false
      case e: Throwable => throw e
    }
    logInfo("Old lock cleared.")
    true
  }

  override def getMsg: (Boolean, String) = {
    try {
      true -> new String(zookeeper.getData(lockPath, false, null))
    } catch {
      case _: NoNodeException => false -> ""
    }
  }

  override def setMsg(msg: String): Unit = {
    zookeeper.setData(lockPath, msg.getBytes(), -1)
  }

  /**
   * Watcher的空实现。
   */
  override def process(event: WatchedEvent): Unit = {}

  /**
   * 监听锁节点delete和连接超时事件，用于通过监听锁状态来实现作业管理，当锁节点被删除时停止作业。
   */
  class LockDeleteWatcher() extends Watcher {
    override def process(event: WatchedEvent): Unit = {
      if (event.getType == Watcher.Event.EventType.NodeDeleted) {
        logError("Lock has been deleted, stop app.")
        lockService.checkAndStop()
        return
      } else if (event.getState == Watcher.Event.KeeperState.Expired) {
        logError("Lock has been expired, stop app.")
        lockService.checkAndStop()
        return
      }

      var registered = false
      while (!registered) {
        try {
          val state = zookeeper.exists(lockPath, this)
          if (state == null) {
            logError("Lock has lost, stop app.")
            lockService.checkAndStop()
          }
          registered = true
        } catch {
          case e: SessionExpiredException =>
            logWarning("Session expired, stop app.", e)
            lockService.checkAndStop()
            return
          case e: Throwable =>
            logWarning("Lock delete watcher meet err, try re register.", e)
        }
      }
    }
  }

}
