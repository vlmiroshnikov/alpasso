package alpasso.cmdline.view

import cats.*
import cats.syntax.all.*

import alpasso.cli.Session
import alpasso.common.Converter

case class TableRowView[+R](id: Int, data: R)

case class TableView[R](rows: List[TableRowView[R]])

object TableView:

  given [R: Show]: Show[TableView[R]] = Show.show { t =>
    val rw     = t.rows.map(r => f"|${r.id}%2d | ${r.data.show}%50s |")
    val stroke = "|" + "-".repeat(56) + "|"
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

object SessionView:

  given Converter[Session, SessionView] =
    s => SessionView(s.path.getFileName.toString, s.path.toString)
  given Show[SessionView] = Show.show(s => s"${s.shortcut.show}s ${s.path.show}")

case class SessionTableView(sessions: List[SessionView])

object SessionTableView:

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
