package tomtom.splitter.layer7

import java.net.Socket

import scala.util.Try

object PortFactory {

  @volatile var portStream: Stream[Int] = Stream.from(1024).flatMap {
      port =>
        Try {
          val socket = new Socket("localhost", port)
          socket.close()
          Seq.empty[Int]
        }.getOrElse {
          Seq(port)
        }
    }

  def findPort(): Int = {
    this.synchronized {
      val port = portStream.head
      portStream = portStream.tail
      port
    }
  }
}
