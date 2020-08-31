/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */
import de.heikoseeberger.sbtheader.{CommentCreator, HeaderPlugin}
import sbt._

object CopyrightHeader extends AutoPlugin {
  import HeaderPlugin.autoImport._

  override def requires = HeaderPlugin
  override def trigger = allRequirements

  override def projectSettings =
    Def.settings(Seq(Compile, Test).flatMap { config =>
      inConfig(config)(
        Seq(
          headerLicense := Some(HeaderLicense.Custom(headerFor(CurrentYear))),
          headerMappings := headerMappings.value ++ Map(
              HeaderFileType.scala -> cStyleComment,
              HeaderFileType.java -> cStyleComment)))
    })

  val CurrentYear = java.time.Year.now.getValue.toString
  val CopyrightPattern = "Copyright \\([Cc]\\) (\\d{4}(-\\d{4})?) (Lightbend|Typesafe) Inc. <.*>".r
  val CopyrightHeaderPattern = s"(?s).*${CopyrightPattern}.*".r

  def headerFor(year: String): String =
    s"Copyright (C) $year Lightbend Inc. <https://www.lightbend.com>"

  private def lightbendCommentCreator(commentCreator: CommentCreator) = new CommentCreator() {

    def updateLightbendHeader(header: String): String = header match {
      case CopyrightHeaderPattern(years, null, _) =>
        if (years != CurrentYear)
          CopyrightPattern.replaceFirstIn(header, headerFor(years + "-" + CurrentYear))
        else
          CopyrightPattern.replaceFirstIn(header, headerFor(years))
      case CopyrightHeaderPattern(years, endYears, _) =>
        CopyrightPattern.replaceFirstIn(header, headerFor(years.replace(endYears, "-" + CurrentYear)))
      case _ =>
        header
    }

    override def apply(text: String, existingText: Option[String]): String = {
      existingText.map(updateLightbendHeader).getOrElse(commentCreator(text, existingText)).trim
    }
  }
  val cStyleComment = HeaderCommentStyle.cStyleBlockComment
    .copy(commentCreator = lightbendCommentCreator(HeaderCommentStyle.cStyleBlockComment.commentCreator))
}
