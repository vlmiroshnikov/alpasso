package alpasso.presentation

import java.nio.file.Path
import java.time.format.DateTimeFormatter

import scala.Console.*

import cats.*
import cats.syntax.all.*

import alpasso.infrastructure.cypher.CypherAlg
import alpasso.infrastructure.git.LogRecord

case class StorageView(repoDir: Path, cypherAlg: CypherAlg)

object StorageView:

  given Show[StorageView] =
    Show.show(s => s"Storage: ${s.repoDir.toString}  engine: ${s.cypherAlg.show}")

opaque type HistoryLogView = List[LogRecord]

object HistoryLogView:
  def from(logs: List[LogRecord]): HistoryLogView = logs

  private val fmt = DateTimeFormatter.ISO_INSTANT

  given Show[HistoryLogView] = Show.show { commits =>
    val rw = commits.map { r =>
      f"|${r.hex}%20s | ${BLUE}${fmt.format(r.time)}%20s${RESET} | ${GREEN}${r.comment}%-40s${RESET}|"
    }

    val stroke = "+" + "-".repeat(106) + "+"
    (stroke +: rw :+ stroke).mkString("\n")
  }
