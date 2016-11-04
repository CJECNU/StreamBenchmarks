/*
 * Copyright 2015, Yahoo Inc.
 * Licensed under the terms of the Apache License 2.0. Please see LICENSE file in the project root for terms.
 */

// scalastyle:off println

package spark.benchmark

import benchmark.common.CommonConfig
import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.json.JSONObject
import org.apache.log4j.{Level, Logger}
import org.apache.spark.streaming.dstream.DStream

import scala.collection.JavaConversions._


object SparkBenchmark {
  def main(args: Array[String]) {
    CommonConfig.initializeConfig(args(0));

    val sparkConf = new SparkConf().setAppName("SparkBenchmark").setMaster(CommonConfig.SPARK_MASTER())
    val ssc = new StreamingContext(sparkConf, Milliseconds(CommonConfig.SPARK_BATCHTIME()))

    val rootLogger = Logger.getRootLogger()
    rootLogger.setLevel(Level.ERROR)


    if (CommonConfig.BENCHMARKING_USECASE() == "KeyedWindowedAggregation")
      keyedWindowedAggregationBenchmark(ssc);
    else if (CommonConfig.BENCHMARKING_USECASE() == "WindowedJoin")
      windowedJoin(ssc);
    else throw new Exception("Please specify use-case name")

    ssc.start()
    ssc.awaitTermination()
  }

  def windowedJoin(ssc: StreamingContext) = {
    var joinSource1: DStream[String] = null;
    var joinSource2: DStream[String] = null;
    var index=0
    for (host <- CommonConfig.DATASOURCE_HOSTS()) {

      val socketDataSource_i: DStream[String] = ssc.receiverStream(new SocketReceiver(host, CommonConfig.DATASOURCE_PORT()))
      if (index % 2 == 1) {
        joinSource1 = if (joinSource1 == null) socketDataSource_i else joinSource1.union(socketDataSource_i)
      }
      else {
        joinSource2 = if (joinSource2 == null) socketDataSource_i else joinSource2.union(socketDataSource_i)
      }
      index = index + 1
    }
    val windowedStream1 = joinSource1.map(s => {
      val obj: JSONObject = new JSONObject(s)
      val price: Double = obj.getDouble("price")
      val geo: String = obj.getString("geo")
      val ts: Long =  obj.getLong("ts")
      ((geo), (ts, price))
    }).window(Milliseconds(CommonConfig.SLIDING_WINDOW_LENGTH()), Milliseconds(CommonConfig.SLIDING_WINDOW_SLIDE()))

    val windowedStream2 = joinSource2.map(s => {
      val obj: JSONObject = new JSONObject(s)
      val price: Double = obj.getDouble("price")
      val geo: String = obj.getString("geo")
      val ts: Long =  obj.getLong("ts")
      ((geo), (ts, price))
    }).window(Milliseconds(CommonConfig.SLIDING_WINDOW_LENGTH()), Milliseconds(CommonConfig.SLIDING_WINDOW_SLIDE()))


    val joinedStream = windowedStream1.join(windowedStream2).map(t => (
                                                                      t._1,
                                                                      System.currentTimeMillis() - Math.max(t._2._1._1, t._2._2._1),
                                                                      Math.abs(t._2._1._2 - t._2._2._2),
                                                                      Math.max(t._2._1._1, t._2._2._1)))

    joinedStream.saveAsTextFiles(CommonConfig.SPARK_OUTPUT());

  }

  def keyedWindowedAggregationBenchmark(ssc: StreamingContext) = {
    var socketDataSource: DStream[String] = null;
    for (host <- CommonConfig.DATASOURCE_HOSTS()) {
      val socketDataSource_i: DStream[String] = ssc.receiverStream(new SocketReceiver(host, CommonConfig.DATASOURCE_PORT()))
      socketDataSource = if (socketDataSource == null) socketDataSource_i else socketDataSource.union(socketDataSource_i)
    }

    val keyedStream = socketDataSource.map(s => {
      val obj: JSONObject = new JSONObject(s)
      val price: Double = obj.getDouble("price")
      val geo: String = obj.getString("geo")
      val ts: Long =  obj.getLong("ts") ;

      ((geo), (ts, price, 1, 1))
    })

    val windowedStream = keyedStream.window(Milliseconds(CommonConfig.SLIDING_WINDOW_LENGTH()), Milliseconds(CommonConfig.SLIDING_WINDOW_SLIDE()))
      .reduceByKey((t1, t2) => {
        val avgPrice = (t1._2 * t1._3 + t2._2 * t2._3 ) / (t1._3 + t2._3);
        val avgCount = t1._3 + t2._3;
        val ts: Long = Math.max(t1._1, t2._1)
        val elementCount = t1._4 + t2._4
        (ts, avgPrice, avgCount, elementCount)
      })

    val mappedStream = windowedStream.map(tuple => new Tuple5[String, Long, Double, Int, Long](
                                                          tuple._1,
                                                          System.currentTimeMillis() - tuple._2._1,
                                                          tuple._2._2,
                                                          tuple._2._4,
                                                          tuple._2._1))

    mappedStream.saveAsTextFiles(CommonConfig.SPARK_OUTPUT());
    // resultStream.print();

  }
}
