package org.ergoplatform.nodeView.wallet.requests

import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor, Json}
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.nodeView.wallet.ErgoAddressJsonEncoder
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.{ErgoAddress, ErgoAddressEncoder, ErgoScriptPredef, Pay2SAddress}

case class RequestsHolder(requests: Seq[TransactionRequest],
                          feeOpt: Option[Long] = None,
                          inputsRaw: Seq[String] = Seq.empty)
                         (implicit val addressEncoder: ErgoAddressEncoder) {

  // Add separate payment request with fee.
  def withFee: Seq[TransactionRequest] = {
    val address = Pay2SAddress(ErgoScriptPredef.feeProposition())
    val feeRequests = feeOpt
        .map(PaymentRequest(address, _, assets = Seq.empty, registers = Map.empty))
        .toSeq
    requests ++ feeRequests
  }

}

class RequestsHolderEncoder(settings: ErgoSettings) extends Encoder[RequestsHolder] with ApiCodecs {

  implicit val transactionRequestEncoder: TransactionRequestEncoder = new TransactionRequestEncoder(settings)
  implicit val addressEncoder: Encoder[ErgoAddress] = ErgoAddressJsonEncoder(settings).encoder

  def apply(holder: RequestsHolder): Json = {
    Json.obj(
      "requests" -> holder.requests.asJson,
      "fee" -> holder.feeOpt.asJson,
      "inputsRaw" -> holder.inputsRaw.asJson
    )
  }

}

class RequestsHolderDecoder(settings: ErgoSettings) extends Decoder[RequestsHolder] {

  implicit val transactionRequestDecoder: TransactionRequestDecoder = new TransactionRequestDecoder(settings)
  implicit val addressEncoder: ErgoAddressEncoder = new ErgoAddressEncoder(settings.chainSettings.addressPrefix)

  def apply(cursor: HCursor): Decoder.Result[RequestsHolder] = {
    for {
      requests <- cursor.downField("requests").as[Seq[TransactionRequest]]
      fee <- cursor.downField("fee").as[Option[Long]]
      inputs <- cursor.downField("inputsRaw").as[Seq[String]]
    } yield RequestsHolder(requests, fee, inputs)
  }

}
