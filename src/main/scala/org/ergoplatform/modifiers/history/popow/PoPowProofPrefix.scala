package org.ergoplatform.modifiers.history.popow

import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.modifiers.history.Header
import org.ergoplatform.settings.{Algos, Constants}
import scorex.core.ModifierTypeId
import scorex.core.serialization.ScorexSerializer
import scorex.util.serialization.{Reader, Writer}
import scorex.util.{ModifierId, bytesToId, idToBytes}
import scorex.util.Extensions._

/**
  * A structure representing NiPoPow proof prefix as a persistent modifier.
  * @param m        - security parameter (min μ-level superchain length)
  * @param chain    - proof headers
  * @param suffixId - id of the corresponding suffix
  * @param sizeOpt  - size of the modifier
  */
final case class PoPowProofPrefix(m: Int,
                                  chain: Seq[PoPowHeader],
                                  suffixId: ModifierId,
                                  sizeOpt: Option[Int] = None)
  extends ErgoPersistentModifier {

  import PoPowAlgos._

  override type M = PoPowProofPrefix

  override val modifierTypeId: ModifierTypeId = PoPowProof.modifierTypeId

  override def serializedId: Array[Byte] = Algos.hash(bytes)

  override def serializer: ScorexSerializer[M] = PoPowProofPrefixSerializer

  override def parentId: ModifierId = chain.head.id

  def headersChain: Seq[Header] = chain.map(_.header)

  def chainOfLevel(l: Int): Seq[PoPowHeader] = chain.filter(x => maxLevelOf(x.header) >= l)

  def isBetterThan(that: PoPowProofPrefix): Boolean = {
    val (thisDivergingChain, thatDivergingChain) = lowestCommonAncestor(headersChain, that.headersChain)
      .map(h => headersChain.filter(_.height > h.height) -> that.headersChain.filter(_.height > h.height))
      .getOrElse(headersChain -> that.headersChain)
    bestArg(thisDivergingChain)(m) > bestArg(thatDivergingChain)(m)
  }

}

object PoPowProofPrefix {
  val modifierTypeId: ModifierTypeId = ModifierTypeId @@ (111: Byte)
}

object PoPowProofPrefixSerializer extends ScorexSerializer[PoPowProofPrefix] {

  override def serialize(obj: PoPowProofPrefix, w: Writer): Unit = {
    w.putUInt(obj.m)
    w.putBytes(idToBytes(obj.suffixId))
    w.putUInt(obj.chain.size)
    obj.chain.foreach { h =>
      val hBytes = h.bytes
      w.putUInt(hBytes.length)
      w.putBytes(hBytes)
    }
  }

  override def parse(r: Reader): PoPowProofPrefix = {
    val startPos = r.position
    val m = r.getUInt().toIntExact
    val suffixId = bytesToId(r.getBytes(Constants.ModifierIdSize))
    val prefixSize = r.getUInt().toIntExact
    val prefix = (0 until prefixSize).map { _ =>
      val size = r.getUInt().toIntExact
      PoPowHeaderSerializer.parseBytes(r.getBytes(size))
    }
    PoPowProofPrefix(m, prefix, suffixId, Some(r.position - startPos))
  }

}