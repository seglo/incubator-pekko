/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic

import scala.collection.mutable.Set
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import akka.actor.ActorSystem
import akka.util.ccompat.JavaConverters._

/**
 * The 192.0.2.1 is a Documentation IP-address and should not be used at all
 * by any network node. Therefore we assume here that the initialization of
 * the ActorSystem with the use of remoting will intentionally fail.
 */
class RemoteInitErrorSpec extends AnyWordSpec with Matchers {
  val conf = ConfigFactory.parseString("""
      akka {
        actor {
          provider = remote
        }
        remote.artery.enabled = false
        remote {
          enabled-transports = ["akka.remote.classic.netty.tcp"]
          classic.netty.tcp {
            hostname = "192.0.2.1"
            port = 12344
          }
        }
      }
    """).resolve()

  def currentThreadIds(): Set[Long] = {
    val threads = Thread.getAllStackTraces().keySet()
    threads.asScala.collect { case t: Thread if !t.isDaemon() => t.getId() }
  }

  "Remoting" must {
    "shut down properly on RemoteActorRefProvider initialization failure" in {
      val start = currentThreadIds()
      try {
        ActorSystem("duplicate", ConfigFactory.parseString("akka.loglevel=OFF").withFallback(conf))
        fail("initialization should fail due to invalid IP address")
      } catch {
        case NonFatal(_) => {
          eventually(timeout(30 seconds), interval(800 milliseconds)) {
            val current = currentThreadIds()
            // no new threads should remain compared to the start state
            (current.diff(start)) should be(empty)
          }
        }
      }
    }
  }
}
