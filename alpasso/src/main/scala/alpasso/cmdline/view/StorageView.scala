package alpasso.cmdline.view

import java.nio.file.Path

import scala.Console.{GREEN, RESET, YELLOW}

import cats.*
import cats.syntax.all.*

import alpasso.service.cypher.CypherAlg
import alpasso.service.git.LogRecord

case class StorageView(repoDir: Path, cypherAlg: CypherAlg)

object StorageView:

  given Show[StorageView] =
    Show.show(s => s"Storage: ${s.repoDir.toString}  engine: ${s.cypherAlg.show}")

opaque type HistoryLogView = List[LogRecord]

object HistoryLogView:
  def from(logs: List[LogRecord]): HistoryLogView = logs

  given Show[HistoryLogView] = Show.show { commits =>

    val rw = commits.map { r => f"|${r.hex}%20s | ${GREEN}${r.comment}%-40s${RESET} |" }

    val stroke = "|" + "-".repeat(84) + "|"
    (stroke +: rw :+ stroke).mkString("\n")
  }
