package alpasso.cmdline.view

import cats.*
import cats.syntax.all.*

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
