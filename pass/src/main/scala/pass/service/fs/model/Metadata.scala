package pass.service.fs.model

import cats.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}

import java.nio.file.Path

enum Metadata:
  case Empty

object Metadata:
  given Encoder[Metadata] = Encoder.instance[Metadata]:
    //case Metadata.UserData(tags) => Json.obj(tags.map(t => t.name -> t.value.asJson): _*)
    case Empty => Json.obj()

  given Decoder[Metadata] = Decoder.decodeJsonObject.emap { _ =>
    Metadata.Empty.asRight

//    obj
//      .toList
//      .traverse((k, v) => (k.asRight, v.as[String]).mapN(Tag.apply))
//      .map(Metadata.Empty)
//      .leftMap(_.getMessage)
  }

