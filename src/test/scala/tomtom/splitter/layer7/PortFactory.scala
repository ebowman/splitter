package tomtom.splitter.layer7

import java.net.Socket

import scala.util.Try

object PortFactory {

  @volatile private var nextPort = 1024

  def findPort(): Int = {
    Stream.from(nextPort).map {
      port =>
        Try {
          val socket = new Socket("localhost", port)
          socket.close()
          None
        }.getOrElse {
          nextPort = port + 1
          Some(port)
        }
    }.flatten.head
  }
}
