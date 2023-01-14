/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.cluster.sharding.typed.scaladsl

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
import pekko.actor.typed.Behavior
import pekko.actor.typed.scaladsl.Behaviors
import pekko.cluster.MemberStatus
import pekko.cluster.sharding.typed.ShardedDaemonProcessSettings
import pekko.cluster.typed.Cluster
import pekko.cluster.typed.Join

object ShardedDaemonProcessSpec {
  // single node cluster config
  def config = ConfigFactory.parseString("""
      pekko.actor.provider = cluster

      pekko.remote.classic.netty.tcp.port = 0
      pekko.remote.artery.canonical.port = 0
      pekko.remote.artery.canonical.hostname = 127.0.0.1

      pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
      
      # ping often/start fast for test
      pekko.cluster.sharded-daemon-process.keep-alive-interval = 1s

      pekko.coordinated-shutdown.terminate-actor-system = off
      pekko.coordinated-shutdown.run-by-actor-system-terminate = off
      """)

  object MyActor {
    sealed trait Command
    case object Stop extends Command

    case class Started(id: Int, selfRef: ActorRef[Command])

    def apply(id: Int, probe: ActorRef[Any]): Behavior[Command] = Behaviors.setup { ctx =>
      probe ! Started(id, ctx.self)

      Behaviors.receiveMessage {
        case Stop =>
          Behaviors.stopped
      }
    }

  }

}

class ShardedDaemonProcessSpec
    extends ScalaTestWithActorTestKit(ShardedDaemonProcessSpec.config)
    with AnyWordSpecLike
    with LogCapturing {

  import ShardedDaemonProcessSpec._

  "The ShardedDaemonSet" must {

    "have a single node cluster running first" in {
      val probe = createTestProbe()
      Cluster(system).manager ! Join(Cluster(system).selfMember.address)
      probe.awaitAssert({
          Cluster(system).selfMember.status == MemberStatus.Up
        }, 3.seconds)
    }

    "start N actors with unique ids" in {
      val probe = createTestProbe[Any]()
      ShardedDaemonProcess(system).init("a", 5, id => MyActor(id, probe.ref))

      val started = probe.receiveMessages(5)
      started.toSet.size should ===(5)
      probe.expectNoMessage()
    }

    "restart actors if they stop" in {
      val probe = createTestProbe[Any]()
      ShardedDaemonProcess(system).init("stop", 2, id => MyActor(id, probe.ref))

      val started = (1 to 2).map(_ => probe.expectMessageType[MyActor.Started]).toSet
      started.foreach(_.selfRef ! MyActor.Stop)

      // periodic ping every 1s makes it restart
      (1 to 2).map(_ => probe.expectMessageType[MyActor.Started](3.seconds))
    }

    "not run if the role does not match node role" in {
      val probe = createTestProbe[Any]()
      val settings = ShardedDaemonProcessSettings(system).withRole("workers")
      ShardedDaemonProcess(system).init("roles", 3, id => MyActor(id, probe.ref), settings, None)

      probe.expectNoMessage()
    }

  }

  object TagProcessor {
    sealed trait Command
    def apply(tag: String): Behavior[Command] = Behaviors.setup { ctx =>
      // start the processing ...
      ctx.log.debug("Starting processor for tag {}", tag)
      Behaviors.empty
    }
  }

  def docExample(): Unit = {
    // #tag-processing
    val tags = Vector("tag-1", "tag-2", "tag-3")
    ShardedDaemonProcess(system).init("TagProcessors", tags.size, id => TagProcessor(tags(id)))
    // #tag-processing
  }

}
