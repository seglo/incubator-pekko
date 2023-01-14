/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.io.dns

import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

import com.typesafe.config.Config
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.{ ListContainersParam, LogsParam }
import com.spotify.docker.client.messages.{ ContainerConfig, HostConfig, PortBinding }
import org.scalatest.concurrent.Eventually

import org.apache.pekko
import pekko.testkit.PekkoSpec
import pekko.util.ccompat.JavaConverters._

abstract class DockerBindDnsService(config: Config) extends PekkoSpec(config) with Eventually {
  val client = DefaultDockerClient.fromEnv().build()

  val hostPort: Int

  var id: Option[String] = None

  def dockerAvailable() = Try(client.ping()).isSuccess

  override def atStartup(): Unit = {
    log.info("Running on port port {}", hostPort)
    super.atStartup()

    // https://github.com/sameersbn/docker-bind/pull/61
    val image = "raboof/bind:9.11.3-20180713-nochown"
    try {
      client.pull(image)
    } catch {
      case NonFatal(_) =>
        log.warning(s"Failed to pull docker image [$image], is docker running?")
        return
    }

    val containerConfig = ContainerConfig
      .builder()
      .image(image)
      .env("NO_CHOWN=true")
      .cmd("-4") // only listen on ipv4
      .hostConfig(
        HostConfig
          .builder()
          .portBindings(Map(
            "53/tcp" -> List(PortBinding.of("", hostPort)).asJava,
            "53/udp" -> List(PortBinding.of("", hostPort)).asJava).asJava)
          .binds(HostConfig.Bind
            .from(new java.io.File("actor-tests/src/test/bind/").getAbsolutePath)
            .to("/data/bind")
            .build())
          .build())
      .build()

    val containerName = "pekko-test-dns-" + getClass.getCanonicalName

    client
      .listContainers(ListContainersParam.allContainers())
      .asScala
      .find(_.names().asScala.exists(_.contains(containerName)))
      .foreach(c => {
        if ("running" == c.state()) {
          client.killContainer(c.id)
        }
        client.removeContainer(c.id)
      })

    val creation = client.createContainer(containerConfig, containerName)
    if (creation.warnings() != null)
      creation.warnings() should have(size(0))
    id = Some(creation.id())

    client.startContainer(creation.id())

    eventually(timeout(25.seconds)) {
      client.logs(creation.id(), LogsParam.stderr()).readFully() should include("all zones loaded")
    }
  }

  def dumpNameserverLogs(): Unit = {
    id.foreach(id => log.info("Nameserver std out: {} ", client.logs(id, LogsParam.stdout()).readFully()))
    id.foreach(id => log.info("Nameserver std err: {} ", client.logs(id, LogsParam.stderr()).readFully()))
  }

  override def afterTermination(): Unit = {
    super.afterTermination()
    id.foreach(client.killContainer)
    id.foreach(client.removeContainer)
  }
}
