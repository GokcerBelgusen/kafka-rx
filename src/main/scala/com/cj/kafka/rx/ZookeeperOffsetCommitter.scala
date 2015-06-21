package com.cj.kafka.rx

import com.google.common.base.Charsets
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.locks.InterProcessMutex

class ZookeeperLock(zk: CuratorFramework, path: String) extends PartitionLock {
  val lock = new InterProcessMutex(zk, path)
  def acquire(): Unit = lock.acquire()
  def release(): Unit = lock.release()
}

class ZookeeperOffsetCommitter(group: String, zk: CuratorFramework) extends OffsetCommitter with SynchronizedCommitter {

  override def start() = {
    if (zk.getState != CuratorFrameworkState.STARTED) {
      zk.start()
      zk.blockUntilConnected()
    }
  }

  override def stop() = zk.close()

  def getOffsets(topicPartitions: Iterable[TopicPartition]): OffsetMap = {
     topicPartitions.flatMap { topicPartition =>
       val (topic, partition) = topicPartition
       val path = getPartitionPath(group, topic, partition)
       Option(zk.checkExists.forPath(path)) match {
         case None => List()
         case Some(filestats) =>
           val bytes = zk.getData.forPath(path)
           val str = new String(bytes, Charsets.UTF_8).trim
           val offset = java.lang.Long.parseLong(str)
           List(topicPartition -> offset)
       }
     }.toMap
  }

  def setOffsets(offsets: OffsetMap): OffsetMap = {
    offsets foreach { case (topicPartition, offset) =>
      val (topic,partition) = topicPartition
      val nodePath = getPartitionPath(group, topic, partition)
      val bytes = offset.toString.getBytes(Charsets.UTF_8)
      Option(zk.checkExists.forPath(nodePath)) match {
        case None =>
          zk.create.creatingParentsIfNeeded.forPath(nodePath, bytes)
        case Some(fileStats) =>
          zk.setData().forPath(nodePath, bytes)
      }
    }
    getOffsets(offsets.keys)
  }

  def getPartitionLock(topicPartition:TopicPartition) : PartitionLock = {
    val (topic,partition) = topicPartition
    val lockPath = s"/locks/kafka-rx/$topic.$group.$partition"
    new ZookeeperLock(zk, lockPath)
  }

  def commit(offsets: OffsetMap, merge: OffsetMerge): OffsetMap = {
    withPartitionLocks(offsets.keys) {
      val zkOffsets = getOffsets(offsets.keys)
      val nextOffsets = merge(zkOffsets, offsets) map { case (topicPartition, offset) =>
        // zookeeper stores the *next* offset
        // while kafka messages contain their offset, shift forward 1 for zk format
        topicPartition -> (offset + 1)
      }
      setOffsets(nextOffsets)
    }
  }

}