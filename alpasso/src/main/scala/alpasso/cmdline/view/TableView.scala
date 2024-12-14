package alpasso.cmdline.view

import scala.Console.{GREEN, RESET}

import cats.*
import cats.syntax.all.*

import alpasso.cli.Session
import alpasso.common.Converter

case class TableView(rows: List[SecretView])

object TableView:

  given Show[TableView] = Show.show { tab =>
    val rows = tab.rows.zipWithIndex
    val rw = rows.map(r =>
      f"|${r._2}%2d | ${GREEN}${r._1.name}%-40s${RESET} | ${r._1.payload.getOrElse("*******")}%-12s | ${r._1.metadata.show}%-24s |"
    )
    val stroke = "|" + "-".repeat(88) + "|"
    (stroke +: rw :+ stroke).mkString("\n")
  }

enum SecretFilter:
  case Grep(pattern: String)
  case Empty

enum OutputFormat:
  case Tree, Table

object OutputFormat:

  def withNameInvariant(s: String): Option[OutputFormat] =
    OutputFormat.values.find(_.toString.toLowerCase == s)

  given Show[OutputFormat] = Show.show(_.toString.toLowerCase)

case class SessionView(shortcut: String, path: String)

given Converter[Session, SessionView] =
  s => SessionView(s.path.getFileName.toString, s.path.toString)

given Show[SessionView] = Show.show(s => s"${s.shortcut.show}s ${s.path.show}")

case class SessionTableView(sessions: List[SessionView])

given Converter[List[Session], SessionTableView] =
  ls => SessionTableView(ls.map(s => SessionView(s.path.getFileName.toString, s.path.toString)))

given Show[SessionTableView] = Show.show { v =>
  val rw = v
    .sessions
    .zipWithIndex
    .map((r, id) => f" ${id}%2s  ${r.shortcut.show}%10s ${r.path.show}%60s")
  val stroke = "=".repeat(80)
  (stroke +: rw :+ stroke).mkString("\n")
}
