package spark.potato.monitor

import spark.potato.common.conf.POTATO_PREFIX

package object conf {
  val POTATO_MONITOR_PREFIX: String = POTATO_PREFIX + "monitor."

  val POTATO_MONITOR_BACKLOG_PREFIX: String = POTATO_MONITOR_PREFIX + "backlog."
  val POTATO_MONITOR_BACKLOG_THRESHOLD_MS_KEY: String = POTATO_MONITOR_BACKLOG_PREFIX + "threshold.ms"

  val POTATO_MONITOR_BACKLOG_REPORTER_PREFIX: String = POTATO_MONITOR_BACKLOG_PREFIX + "reporter."
  val POTATO_MONITOR_BACKLOG_REPORTER_INTERVAL_MS_KEY: String = POTATO_MONITOR_BACKLOG_REPORTER_PREFIX + "interval.ms"
  val POTATO_MONITOR_BACKLOG_REPORTER_INTERVAL_MS_DEFAULT: Long = 900000
  val POTATO_MONITOR_BACKLOG_REPORTER_MAX_KEY: String = POTATO_MONITOR_BACKLOG_REPORTER_PREFIX + "max"
  val POTATO_MONITOR_BACKLOG_REPORTER_MAX_DEFAULT: Int = 3

  val POTATO_MONITOR_BACKLOG_NOTIFY_PREFIX: String = POTATO_MONITOR_BACKLOG_PREFIX + "notify."
  val POTATO_MONITOR_BACKLOG_NOTIFY_TYPE_KEY: String = POTATO_MONITOR_BACKLOG_NOTIFY_PREFIX + "type"
  val POTATO_MONITOR_BACKLOG_NOTIFY_TYPE_DEFAULT: String = "ding"
  val POTATO_MONITOR_BACKLOG_NOTIFY_DING_PREFIX: String = POTATO_MONITOR_BACKLOG_NOTIFY_PREFIX + "ding."
  val POTATO_MONITOR_BACKLOG_NOTIFY_DING_TOKEN_KEY: String = POTATO_MONITOR_BACKLOG_NOTIFY_DING_PREFIX + "token"
  val POTATO_MONITOR_BACKLOG_NOTIFY_DING_AT_ALL_KEY: String = POTATO_MONITOR_BACKLOG_NOTIFY_DING_PREFIX + "at.all"
  val POTATO_MONITOR_BACKLOG_NOTIFY_DING_AT_ALL_DEFAULT: Boolean = false
  val POTATO_MONITOR_BACKLOG_NOTIFY_DING_AT_PHONES_KEY: String = POTATO_MONITOR_BACKLOG_NOTIFY_DING_PREFIX + "at.phones"
  val POTATO_MONITOR_BACKLOG_NOTIFY_DING_AT_PHONES_DEFAULT = Array.empty[String]

  val POTATO_MONITOR_BACKLOG_SERVICE_NAME = "StreamingBacklogMonitor"
}
