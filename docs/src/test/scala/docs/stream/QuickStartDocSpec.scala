/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream

//#stream-imports
import org.apache.pekko
import pekko.stream._
import pekko.stream.scaladsl._
//#stream-imports

//#other-imports
import pekko.{ Done, NotUsed }
import pekko.actor.ActorSystem
import pekko.util.ByteString
import scala.concurrent._
import scala.concurrent.duration._
import java.nio.file.Paths
//#other-imports

import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.concurrent._

//#main-app
object Main extends App {
  implicit val system: ActorSystem = ActorSystem("QuickStart")
  // Code here
}
//#main-app

class QuickStartDocSpec extends AnyWordSpec with BeforeAndAfterAll with ScalaFutures {
  implicit val patience: PatienceConfig = PatienceConfig(5.seconds)

  def println(any: Any) = () // silence printing stuff

  "demonstrate Source" in {
    implicit val system = ActorSystem("QuickStart")

    // #create-source
    val source: Source[Int, NotUsed] = Source(1 to 100)
    // #create-source

    // #run-source
    source.runForeach(i => println(i))
    // #run-source

    // #transform-source
    val factorials = source.scan(BigInt(1))((acc, next) => acc * next)

    val result: Future[IOResult] =
      factorials.map(num => ByteString(s"$num\n")).runWith(FileIO.toPath(Paths.get("factorials.txt")))
    // #transform-source

    // #use-transformed-sink
    factorials.map(_.toString).runWith(lineSink("factorial2.txt"))
    // #use-transformed-sink

    // #add-streams
    factorials
      .zipWith(Source(0 to 100))((num, idx) => s"$idx! = $num")
      .throttle(1, 1.second)
      // #add-streams
      .take(3)
      // #add-streams
      .runForeach(println)
    // #add-streams

    // #run-source-and-terminate
    val done: Future[Done] = source.runForeach(i => println(i))

    implicit val ec = system.dispatcher
    done.onComplete(_ => system.terminate())
    // #run-source-and-terminate

    done.futureValue
  }

  // #transform-sink
  def lineSink(filename: String): Sink[String, Future[IOResult]] =
    Flow[String].map(s => ByteString(s + "\n")).toMat(FileIO.toPath(Paths.get(filename)))(Keep.right)
  // #transform-sink

}
