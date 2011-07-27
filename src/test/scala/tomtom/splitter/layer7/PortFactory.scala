package tomtom.splitter.layer7

import java.net.{ConnectException, Socket}

object PortFactory {

  private var nextPort = 1024

  def reservePort: Int = {
    PortFactory synchronized {
      try {
        while (true) {
          val socket = new Socket("localhost", nextPort)
          socket.close()
          nextPort += 1
        }
        throw new RuntimeException("Unreachable")
      } catch {
        case e: ConnectException =>
          val result = nextPort
          nextPort += 1
          result
      }
    }
  }
}
