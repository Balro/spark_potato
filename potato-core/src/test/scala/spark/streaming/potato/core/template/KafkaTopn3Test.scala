package spark.streaming.potato.core.template

import kafka.serializer.StringDecoder
import org.apache.spark.internal.Logging
import spark.streaming.potato.core.conf.PotatoConfKeys._
import spark.streaming.potato.core.source.kafka.KafkaSource
import spark.streaming.potato.core.source.kafka.offsets.OffsetsManagerConf

object KafkaTopn3Test extends KafkaSourceTemplate(
  KafkaSource.createDStream[String, String, StringDecoder, StringDecoder, String](
    _, _, mam => mam.message())
) with Logging {
  override def doWork(args: Array[String]): Unit = {
    stream.foreachRDD { rdd =>
      rdd.top(10).foreach(println)
    }
  }

  override def afterConfCreated(args: Array[String]): Unit = {
    super.afterConfCreated(args)
    conf.setMaster("local[10]").setAppName("test")
    conf.set(POTATO_STREAMING_SLIDE_DURATION_SECONDS_KEY, "20")
    conf.set(OffsetsManagerConf.OFFSETS_STORAGE_KEY, "zookeeper")
    conf.set(OffsetsManagerConf.OFFSET_RESET_POLICY, "earliest")
    conf.set(OffsetsManagerConf.SUBSCRIBE_TOPICS_KEY, "test")
    conf.set(OffsetsManagerConf.BOOTSTRAP_SERVERS_KEY, "test01:9092,test02:9092")
    conf.set(OffsetsManagerConf.GROUP_ID_KEY, "kafka_print_test")

    conf.set(POTATO_RUNNING_LOCK_ENABLE_KEY, "true")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_ADDR_KEY, "test02:2181")
    conf.set(POTATO_RUNNING_LOCK_ZOOKEEPER_PATH_KEY, "/potato/lock/test")
  }
}