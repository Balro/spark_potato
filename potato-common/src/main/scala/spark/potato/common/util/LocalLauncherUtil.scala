package spark.potato.common.util

import java.util.Properties
import scala.collection.JavaConversions.propertiesAsScalaMap

/**
 * 本地测试工具类。
 */
object LocalLauncherUtil {
  /**
   * @param clazz       待测试的object，必须具有main方法。
   * @param propFile    配置文件类路径，配置文件中的spark.master会替换为local
   * @param masterCores local模式的核数，默认为 * 即本地cpu核数。
   * @param cmdArgs     测试main方法的命令行参数。
   */
  def test(clazz: Any, propFile: String, masterCores: String = "*", cmdArgs: Array[String] = Array.empty[String]): Unit = {
    val props = new Properties()
    props.load(clazz.getClass.getResourceAsStream(propFile))
    props.setProperty("spark.master", s"local[$masterCores]")
    props.foreach { prop =>
      System.setProperty(prop._1, prop._2)
    }
    clazz.getClass.getMethod("main", classOf[Array[String]]).invoke(clazz, cmdArgs)
  }
}