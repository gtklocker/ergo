package org.ergoplatform.modifiers.history.popow

import org.ergoplatform.modifiers.history.{Header, HeaderSerializer}
import scorex.core.serialization.{BytesSerializable, ScorexSerializer}
import scorex.util.Extensions._
import scorex.util.serialization.{Reader, Writer}
import scorex.util.{ModifierId, bytesToId, idToBytes}

/** Header with unpacked interlinks.
  */
final case class PoPowHeader(header: Header, interlinks: Seq[ModifierId])
  extends BytesSerializable {

  override type M = PoPowHeader

  override def serializer: ScorexSerializer[M] = PoPowHeaderSerializer

  def id: ModifierId = header.id

  def height: Int = header.height
}

object PoPowHeaderSerializer extends ScorexSerializer[PoPowHeader] {

  override def serialize(obj: PoPowHeader, w: Writer): Unit = {
    val headerBytes = obj.header.bytes
    w.putUInt(headerBytes.length)
    w.putBytes(headerBytes)
    w.putUInt(obj.interlinks.size)
    obj.interlinks.foreach(x => w.putBytes(idToBytes(x)))
  }

  override def parse(r: Reader): PoPowHeader = {
    val headerSize = r.getUInt().toIntExact
    val header = HeaderSerializer.parseBytes(r.getBytes(headerSize))
    val linksQty = r.getUInt().toIntExact
    val interlinks = (0 until linksQty).map(_ => bytesToId(r.getBytes(32)))
    PoPowHeader(header, interlinks)
  }

}