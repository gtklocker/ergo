package org.ergoplatform.nodeView.history

import org.ergoplatform.modifiers.history._
import org.ergoplatform.modifiers.history.popow.{PoPowAlgos, PoPowHeader, PoPowProof}
import org.ergoplatform.modifiers.state.UTXOSnapshotChunk
import org.ergoplatform.modifiers.{BlockSection, ErgoFullBlock, ErgoPersistentModifier}
import org.ergoplatform.nodeView.history.components._
import org.ergoplatform.nodeView.history.storage.StorageKeys.LastProofIdKey
import org.ergoplatform.settings.{ErgoSettings, PoPowParams}
import scorex.core.consensus.History._
import scorex.core.consensus.{HistoryReader, ModifierSemanticValidity}
import scorex.core.utils.ScorexEncoding
import scorex.util.{ModifierId, bytesToId, idToBytes}

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * Read-only copy of ErgoHistory
  */
trait ErgoHistoryReader
  extends HistoryReader[ErgoPersistentModifier, ErgoSyncInfo]
    with HeadersProcessor
    with ChainSyncProcessor
    with BasicReaders
    with UTXOSnapshotChunkProcessor
    with BlockSectionProcessor
    with NodeProcessor
    with Persistence
    with Logging
    with ScorexEncoding {

  protected val settings: ErgoSettings

  /**
    * Is there's no history, even genesis block
    */
  def isEmpty: Boolean = bestHeaderIdOpt.isEmpty

  /**
    * Complete block of the best chain with transactions.
    * Always None for an SPV mode, Some(fullBLock) for fullnode regime after initial bootstrap.
    */
  def bestFullBlockOpt: Option[ErgoFullBlock] =
    bestFullBlockIdOpt.flatMap(id => typedModifierById[Header](id)).flatMap(getFullBlock)

  /**
    * @param id - modifier id
    * @return semantically valid ErgoPersistentModifier with the given id it is in history
    */
  override def modifierById(id: ModifierId): Option[ErgoPersistentModifier] =
    if (isSemanticallyValid(id) != ModifierSemanticValidity.Invalid) {
      storage.modifierById(id)
    } else {
      None
    }

  /** Get modifier of expected type by its identifier
    *
    * @tparam T - expected Type
    * @param id - modifier id
    * @return semantically valid ErgoPersistentModifier of type T with the given id it is in history
    */
  def typedModifierById[T <: ErgoPersistentModifier : ClassTag](id: ModifierId): Option[T] = modifierById(id) match {
    case Some(m: T) => Some(m)
    case _ => None
  }

  override def contains(id: ModifierId): Boolean = storage.contains(id)

  /**
    * Id of best block to mine
    */
  override def openSurfaceIds(): Seq[ModifierId] = bestFullBlockIdOpt.orElse(bestHeaderIdOpt).toSeq

  /**
    * Check, that it's possible to apply modifier to history
    */
  def applicable(modifier: ErgoPersistentModifier): Boolean = applicableTry(modifier).isSuccess

  /**
    * Whether another's node syncinfo shows that another node is ahead or behind ours
    *
    * @param info other's node sync info
    * @return Equal if nodes have the same history, Younger if another node is behind, Older if a new node is ahead
    */
  override def compare(info: ErgoSyncInfo): HistoryComparisonResult = {
    bestHeaderIdOpt match {
      case Some(id) if info.lastHeaderIds.lastOption.contains(id) =>
        //Our best header is the same as other node best header
        Equal
      case Some(id) if info.lastHeaderIds.contains(id) =>
        //Our best header is in other node best chain, but not at the last position
        Older
      case Some(_) if info.lastHeaderIds.isEmpty =>
        //Other history is empty, our contain some headers
        Younger
      case Some(_) =>
        //We are on different forks now.
        if (info.lastHeaderIds.view.reverse.exists(m => contains(m) || m == PreGenesisHeader.id)) {
          //Return Younger, because we can send blocks from our fork that other node can download.
          Fork
        } else {
          //We don't have any of id's from other's node sync info in history.
          //We don't know whether we can sync with it and what blocks to send in Inv message.
          //Assume it is older and far ahead from us
          Older
        }
      case None if info.lastHeaderIds.isEmpty =>
        //Both nodes do not keep any blocks
        Equal
      case None =>
        //Our history is empty, other contain some headers
        Older
    }
  }

  /**
    * @param info other's node sync info
    * @param size max return size
    * @return Ids of headers a node with `info` should download and apply in order to synchronize
    */
  override def continuationIds(info: ErgoSyncInfo, size: Int): ModifierIds =
    if (isEmpty) {
      info.startingPoints
    } else if (info.lastHeaderIds.isEmpty) {
      val heightFrom = Math.min(bestHeaderHeight, size + ErgoHistory.EmptyHistoryHeight)
      headerIdsAtHeight(heightFrom).headOption.toSeq.flatMap { startId =>
        typedModifierById[Header](startId).toSeq.flatMap { startHeader =>
          val headers = headerChainBack(size, startHeader, _ => false)
            .ensuring(_.exists(_.isGenesis), "Should always contain genesis header")
          headers.flatMap(h => Seq((Header.modifierTypeId, h.id)))
        }
      }
    } else {
      val ids = info.lastHeaderIds
      val branchingPointOpt: Option[ModifierId] = ids.view.reverse
        .find(m => isInBestChain(m))
        .orElse(if (ids.contains(PreGenesisHeader.id)) Some(PreGenesisHeader.id) else None)
      branchingPointOpt.toSeq.flatMap { branchingPoint =>
        val otherNodeHeight = heightOf(branchingPoint).getOrElse(PreGenesisHeader.height)
        val heightFrom = Math.min(bestHeaderHeight, otherNodeHeight + size)
        val startId = headerIdsAtHeight(heightFrom).head
        val startHeader = typedModifierById[Header](startId).get
        val headerIds = headerChainBack(size, startHeader, _.parentId == branchingPointOpt)
          .map(Header.modifierTypeId -> _.id)
        headerIds
      }
    }

  /**
    *
    * @param header     - header to start
    * @param withFilter - condition to satisfy
    * @return all possible forks, starting from specified header and satisfying withFilter condition
    */
  protected[history] def continuationHeaderChains(header: Header, withFilter: Header => Boolean): Seq[Seq[Header]] = {
    @tailrec
    def loop(currentHeight: Option[Int], acc: Seq[Seq[Header]]): Seq[Seq[Header]] = {
      val nextLevelHeaders = currentHeight.toList
        .flatMap { h => headerIdsAtHeight(h + 1) }
        .flatMap { id => typedModifierById[Header](id) }
        .filter(withFilter)
      if (nextLevelHeaders.isEmpty) {
        acc.map(_.reverse)
      } else {
        val updatedChains = nextLevelHeaders.flatMap { h =>
          acc.find(chain => chain.nonEmpty && (h.parentId == chain.head.id)).map(c => h +: c)
        }
        val nonUpdatedChains = acc.filter(chain => !nextLevelHeaders.exists(_.parentId == chain.head.id))
        loop(currentHeight.map(_ + 1), updatedChains ++ nonUpdatedChains)
      }
    }

    loop(heightOf(header.id), Seq(Seq(header)))
  }

  /**
    * Generates [[ErgoSyncInfo]] depending on history state.
    */
  override def syncInfo: ErgoSyncInfo = if (isEmpty) {
    ErgoSyncInfo(Seq.empty)
  } else {
    val startingPoints = lastHeaders(ErgoSyncInfo.MaxBlockIds)
    if (startingPoints.headOption.exists(_.isGenesis)) {
      ErgoSyncInfo((PreGenesisHeader +: startingPoints).map(_.id))
    } else {
      ErgoSyncInfo(startingPoints.map(_.id))
    }
  }

  /**
    * Return last count headers from best headers chain if exist or chain up to genesis otherwise
    */
  def lastHeaders(count: Int, offset: Int = 0): Seq[Header] = bestHeaderOpt
    .map(bestHeader => headerChainBack(count, bestHeader, _ => false).drop(offset))
    .getOrElse(Array.empty[Header])

  /**
    * @return ids of count headers starting from offset
    */
  def headerIdsAt(offset: Int = 0, limit: Int): Seq[ModifierId] = (offset until (limit + offset))
    .flatMap(h => headerIdsAtHeight(h).headOption)

  override def applicableTry(modifier: ErgoPersistentModifier): Try[Unit] = {
    modifier match {
      case header: Header =>
        validate(header)
      case m: BlockSection =>
        validate(m)
      case chunk: UTXOSnapshotChunk =>
        validate(chunk)
      case m: Any =>
        Failure(new Error(s"Modifier $m has incorrect type"))
    }
  }

  final def getFullBlock(header: Header): Option[ErgoFullBlock] = {
    (typedModifierById[BlockTransactions](header.transactionsId),
      typedModifierById[Extension](header.extensionId),
      typedModifierById[ADProofs](header.ADProofsId)) match {
      case (Some(txs), Some(ext), Some(proofs)) => Some(ErgoFullBlock(header, txs, ext, Some(proofs)))
      case (Some(txs), Some(ext), None) if !nodeSettings.stateType.requireProofs =>
        Some(ErgoFullBlock(header, txs, ext, None))
      case _ => None
    }
  }

  /**
    * Return headers, required to apply to reach header2 if you are at header1 position.
    *
    * @param startHeaderOpt - initial position
    * @param finalHeader    - header you should reach
    * @return (Modifier it required to rollback first, header chain to apply)
    */
  final def chainToHeader(startHeaderOpt: Option[Header], finalHeader: Header): (Option[ModifierId], Seq[Header]) = {
    startHeaderOpt match {
      case Some(h1) =>
        val (prevChain, newChain) = commonBlockThenSuffixes(h1, finalHeader)
        (prevChain.headOption.map(_.id), newChain.tail)
      case None =>
        (None, headerChainBack(finalHeader.height + 1, finalHeader, _ => false))
    }
  }

  /**
    * Find common block and subchains from common block to header1 and header2
    *
    * @param header1 : Header - header in first subchain
    * @param header2 : Header - header in second subchain
    * @return (chain from common block to header1, chain from common block to header2)
    */
  protected[history] def commonBlockThenSuffixes(header1: Header, header2: Header): (Seq[Header], Seq[Header]) = {
    assert(contains(header1) && contains(header2), "Should never call this function for non-existing headers")
    val heightDiff = Math.max(header1.height - header2.height, 0)

    @scala.annotation.tailrec
    def loop(numberBack: Int, otherChain: Seq[Header]): (Seq[Header], Seq[Header]) = {
      val r = commonBlockThenSuffixes(otherChain, header1, numberBack + heightDiff)
      if (r._1.head == r._2.head) {
        r
      } else {
        if (!otherChain.head.isGenesis) {
          val biggerOther = headerChainBack(numberBack, otherChain.head, _ => false) ++ otherChain.tail
          loop(biggerOther.size, biggerOther)
        } else {
          (PreGenesisHeader +: r._1, PreGenesisHeader +: r._2)
        }
      }
    }

    loop(2, Seq(header2))
  }

  protected[history] def commonBlockThenSuffixes(otherChain: Seq[Header],
                                                 startHeader: Header,
                                                 limit: Int): (Seq[Header], Seq[Header]) = {
    def until(h: Header): Boolean = otherChain.exists(_.id == h.id)

    val ourChain = headerChainBack(limit, startHeader, until)
    val commonBlock = ourChain.head
    val commonBlockThenSuffixes = HeaderChain.dropBefore(otherChain)(commonBlock)
    (ourChain, commonBlockThenSuffixes)
  }

  override final def isSemanticallyValid(modifierId: ModifierId): ModifierSemanticValidity = {
    storage.getIndex(validityKey(modifierId)) match {
      case Some(b) if b.headOption.contains(1.toByte) => ModifierSemanticValidity.Valid
      case Some(b) if b.headOption.contains(0.toByte) => ModifierSemanticValidity.Invalid
      case None if contains(modifierId) => ModifierSemanticValidity.Unknown
      case None => ModifierSemanticValidity.Absent
      case m =>
        log.error(s"Incorrect validity status: $m")
        ModifierSemanticValidity.Absent
    }
  }

  /**
    * Get PoPow version of a headeer with the given `headerId`.
    * @param headerId - identifier of the header
    * @return PoPowHeader(header + interlinks) or None if header of extension of a corresponding block are not available
    */
  final def getPoPowHeader(headerId: ModifierId): Option[PoPowHeader] =
    typedModifierById[Header](headerId).flatMap { h =>
      typedModifierById[Extension](h.extensionId).flatMap { ext =>
        PoPowAlgos.unpackInterlinks(ext.fields).toOption.map(PoPowHeader(h, _))
      }
    }

  /**
    * Constructs popow header (header + interlinks) for a best header at given height
    * @param height - height
    * @return PoPowHeader(header + interlinks) or None if header of extension of a corresponding block are not available
    */
  final def getPoPowHeader(height: Int): Option[PoPowHeader] =
    bestHeaderIdAtHeight(height).flatMap(getPoPowHeader)

  /**
    * Generate PoPow proof from current chain according to a given `params`.
    */
  final def proveSuffix(params: PoPowParams): Try[PoPowProof] =
    getLastProof.fold(makeNewProof(params)(None)) { proof =>
      bestHeaderOpt.fold[Try[PoPowProof]](Failure(new Exception("Empty chain"))) { bestHeader =>
        val bestHeaderInProof = proof.chain.last.header
        if (bestHeaderInProof.id == bestHeader.id) Success(proof) else makeNewProof(params)(Some(bestHeader))
      }
    }

  /**
    * Generate PoPow proof for a header with a given `headerId` according to a given `params`.
    */
  final def proveInfix(headerId: ModifierId, params: PoPowParams): Try[PoPowProof] =
    typedModifierById[Header](headerId).fold[Try[PoPowProof]](
      Failure(new Exception(s"Header with $headerId not found"))
    ) { header => makeNewProof(params)(Some(header)) }

  /**
    * Generates new NiPoPow proof for the current chain and saves it to the storage,
    * or simply fetches it from the storage in case proof for the current chain was
    * created earlier.
    */
  private def makeNewProof(params: PoPowParams)
                          (prefetchedHeaderOpt: Option[Header]) =
    (prefetchedHeaderOpt orElse bestHeaderOpt)
      .fold[Try[PoPowProof]](Failure(new Exception("Empty chain"))) { bestHeader =>
        val chain = headerChainBack(Int.MaxValue, bestHeader, _.isGenesis)
        val poPowChain = chain.flatMap(h => getInterlinksFor(h).map(PoPowHeader(h, _)))
        Try(PoPowAlgos.prove(poPowChain)(params))
      }
      .map { proof =>
        getLastProof.foreach(proof => storage.remove(Seq(proof.id)))
        storage.update(Seq(LastProofIdKey -> idToBytes(proof.id)), Seq(proof))
        proof
      }

  private[history] final def getLastProof = storage.getIndex(LastProofIdKey)
    .flatMap(id => typedModifierById[PoPowProof](bytesToId(id)))

  private def getInterlinksFor(header: Header) =
    storage.get(header.extensionId)
      .flatMap(x => ExtensionSerializer.parseBytesTry(x.tail).toOption)
      .flatMap(ext => PoPowAlgos.unpackInterlinks(ext.fields).toOption)

}
